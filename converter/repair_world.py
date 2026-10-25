"""Repairs a Cherry Tree Hills save whose sea floor was dug through, and makes the floor stone.

    python repair_world.py "<saves>/Cherry Tree Hills"

Earlier worlds had a one-block sand floor at Y 58 over 122 blocks of air. Breaking it let the sea pour into the
air below, and the flood of water updates stopped the world from loading. For every saved chunk this:
  - fills Y -64..58 with stone (bedrock at Y -64), so the floor can't be dug into a void again;
  - puts still water back at Y 59-64 where there is air, water or sand (player blocks are kept);
  - drops pending water/block updates and the stored light, so the game relights the chunk on load;
and changes level.dat's flat world layers the same way (bedrock 1, stone 122, water 6) for new chunks.
Back the world up first.
"""
import gzip
import io
import os
import struct
import sys
import zlib

FLOOR_TOP = 58          # last stone Y
WATER = (59, 64)        # still water, inclusive
LAYERS = [("minecraft:bedrock", 1), ("minecraft:stone", 122), ("minecraft:water", 6)]


# typed NBT: every value is (tag id, payload) so files round-trip unchanged
def read_tag(t, f):
    if t == 1: return struct.unpack(">b", f.read(1))[0]
    if t == 2: return struct.unpack(">h", f.read(2))[0]
    if t == 3: return struct.unpack(">i", f.read(4))[0]
    if t == 4: return struct.unpack(">q", f.read(8))[0]
    if t == 5: return struct.unpack(">f", f.read(4))[0]
    if t == 6: return struct.unpack(">d", f.read(8))[0]
    if t == 7:
        n = struct.unpack(">i", f.read(4))[0]
        return f.read(n)
    if t == 8:
        n = struct.unpack(">H", f.read(2))[0]
        return f.read(n).decode("utf-8")
    if t == 9:
        et, n = struct.unpack(">bi", f.read(5))
        return (et, [read_tag(et, f) for _ in range(n)])
    if t == 10:
        d = {}
        while True:
            ct = f.read(1)[0]
            if ct == 0:
                return d
            n = struct.unpack(">H", f.read(2))[0]
            name = f.read(n).decode("utf-8")
            d[name] = (ct, read_tag(ct, f))
    if t == 11:
        n = struct.unpack(">i", f.read(4))[0]
        return list(struct.unpack(f">{n}i", f.read(4 * n)))
    if t == 12:
        n = struct.unpack(">i", f.read(4))[0]
        return list(struct.unpack(f">{n}q", f.read(8 * n)))
    raise ValueError(f"bad tag {t}")


def write_tag(t, v, f):
    if t == 1: f.write(struct.pack(">b", v))
    elif t == 2: f.write(struct.pack(">h", v))
    elif t == 3: f.write(struct.pack(">i", v))
    elif t == 4: f.write(struct.pack(">q", v))
    elif t == 5: f.write(struct.pack(">f", v))
    elif t == 6: f.write(struct.pack(">d", v))
    elif t == 7: f.write(struct.pack(">i", len(v)) + v)
    elif t == 8:
        b = v.encode("utf-8")
        f.write(struct.pack(">H", len(b)) + b)
    elif t == 9:
        et, items = v
        f.write(struct.pack(">bi", et if items else (et or 0), len(items)))
        for x in items:
            write_tag(et, x, f)
    elif t == 10:
        for name, (ct, cv) in v.items():
            b = name.encode("utf-8")
            f.write(struct.pack(">bH", ct, len(b)) + b)
            write_tag(ct, cv, f)
        f.write(b"\0")
    elif t == 11: f.write(struct.pack(f">i{len(v)}i", len(v), *v))
    elif t == 12: f.write(struct.pack(f">i{len(v)}q", len(v), *v))
    else: raise ValueError(f"bad tag {t}")


def load(data):
    f = io.BytesIO(data)
    t = f.read(1)[0]
    n = struct.unpack(">H", f.read(2))[0]
    name = f.read(n).decode("utf-8")
    return name, read_tag(t, f)


def dump(name, root):
    f = io.BytesIO()
    b = name.encode("utf-8")
    f.write(struct.pack(">bH", 10, len(b)) + b)
    write_tag(10, root, f)
    return f.getvalue()


def block(name, **props):
    d = {"Name": (8, name)}
    if props:
        d["Properties"] = (10, {k: (8, v) for k, v in props.items()})
    return d


STONE, BEDROCK, WATER_SRC = block("minecraft:stone"), block("minecraft:bedrock"), block("minecraft:water", level="0")


def decode(states):
    palette = states["palette"][1][1]
    if "data" not in states or len(palette) == 1:
        return palette, [0] * 4096
    bits = max(4, (len(palette) - 1).bit_length())
    per, mask, data = 64 // bits, (1 << bits) - 1, states["data"][1]
    return palette, [((data[i // per] & 0xFFFFFFFFFFFFFFFF) >> (bits * (i % per))) & mask for i in range(4096)]


def encode(palette, idx):
    used = sorted(set(idx))
    remap = {o: n for n, o in enumerate(used)}
    palette = [palette[o] for o in used]
    states = {"palette": (9, (10, palette))}
    if len(palette) > 1:
        bits = max(4, (len(palette) - 1).bit_length())
        per = 64 // bits
        longs = [0] * ((4096 + per - 1) // per)
        for i, p in enumerate(idx):
            longs[i // per] |= remap[p] << (bits * (i % per))
        states["data"] = (12, [l - (1 << 64) if l >= 1 << 63 else l for l in longs])
    return states


def fix_section(sec):
    """Returns True if the section was changed."""
    sy = sec["Y"][1]
    lo, hi = sy * 16, sy * 16 + 15
    if hi < -64 or lo > WATER[1] or "block_states" not in sec:
        return False
    palette, idx = decode(sec["block_states"][1])
    palette = list(palette)

    def pid(b):
        for i, p in enumerate(palette):
            if p == b:
                return i
        palette.append(b)
        return len(palette) - 1

    stone, bedrock, water = pid(STONE), pid(BEDROCK), pid(WATER_SRC)
    replaceable = {i for i, p in enumerate(palette)
                   if p["Name"][1] in ("minecraft:air", "minecraft:cave_air", "minecraft:water", "minecraft:sand",
                                       "minecraft:flowing_water", "minecraft:gravel")}
    for y in range(max(lo, -64), min(hi, WATER[1]) + 1):
        base = (y - lo) * 256
        for i in range(base, base + 256):
            if y == -64:
                idx[i] = bedrock
            elif y <= FLOOR_TOP:
                idx[i] = stone
            elif idx[i] in replaceable:
                idx[i] = water
    sec["block_states"] = (10, encode(palette, idx))
    sec.pop("BlockLight", None)
    sec.pop("SkyLight", None)
    return True


def fix_chunk(root):
    changed = False
    for sec in root.get("sections", (9, (10, [])))[1][1]:
        changed |= fix_section(sec)
    if changed:
        root["fluid_ticks"] = (9, (10, []))
        root["block_ticks"] = (9, (10, []))
        root["isLightOn"] = (1, 0)
    return changed


def fix_region(path):
    data = open(path, "rb").read()
    if len(data) < 8192:
        return 0
    chunks, stamps, n = {}, data[4096:8192], 0
    for i in range(1024):
        off = int.from_bytes(data[i * 4:i * 4 + 3], "big")
        if off == 0:
            continue
        p = off * 4096
        length, kind = struct.unpack(">ib", data[p:p + 5])
        raw = data[p + 5:p + 4 + length]
        if kind == 2:
            nbt = zlib.decompress(raw)
        elif kind == 1:
            nbt = gzip.decompress(raw)
        else:
            chunks[i] = data[p:p + 4 + length]  # leave anything unusual as it is
            continue
        name, root = load(nbt)
        if fix_chunk(root):
            n += 1
        body = zlib.compress(dump(name, root))
        chunks[i] = struct.pack(">ib", len(body) + 1, 2) + body
    out = bytearray(8192)
    out[4096:8192] = stamps
    for i, c in sorted(chunks.items()):
        sectors = (len(c) + 4095) // 4096
        out[i * 4:i * 4 + 4] = ((len(out) // 4096) << 8 | sectors).to_bytes(4, "big")
        out += c + b"\0" * (sectors * 4096 - len(c))
    tmp = path + ".tmp"
    open(tmp, "wb").write(out)
    os.replace(tmp, path)
    return n


def fix_level(path):
    name, root = load(gzip.decompress(open(path, "rb").read()))
    gen = root["Data"][1]["WorldGenSettings"][1]["dimensions"][1]["minecraft:overworld"][1]["generator"][1]
    gen["settings"][1]["layers"] = (9, (10, [{"block": (8, b), "height": (3, h)} for b, h in LAYERS]))
    open(path, "wb").write(gzip.compress(dump(name, root)))


def main(world):
    if os.path.exists(os.path.join(world, "session.lock")):
        try:  # Minecraft keeps the lock open while the world is loaded
            os.rename(os.path.join(world, "session.lock"), os.path.join(world, "session.lock"))
        except OSError:
            sys.exit("the world is open in Minecraft - close the game first")
    total = 0
    region = os.path.join(world, "region")
    for f in sorted(os.listdir(region)):
        if f.endswith(".mca"):
            n = fix_region(os.path.join(region, f))
            total += n
            print(f"{f}: {n} chunks repaired")
    fix_level(os.path.join(world, "level.dat"))
    print(f"done: {total} chunks, level.dat layers -> {LAYERS}")


if __name__ == "__main__":
    main(sys.argv[1])
