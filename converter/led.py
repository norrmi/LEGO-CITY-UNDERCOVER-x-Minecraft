"""Reader for LCU's self-describing .LED streams (lighting, fog, post effects). Little endian.

Schema: TypeList, then ClassList; a class starts with u32 6 "Class\\0" u32 n <name>, then
"Version" and "Types", then field records
    u32 type, u32 name length, name\\0, u32 offset, u32, u32, i32 -1
Instances: "MOBJ" blocks with u32 length + object name, followed by the field data.
"""
import re
import struct

TYPES = ["Char", "Short", "Int", "Int64", "Float", "String", "Colour3", "NuHSpecial", "Vec3", "Vec4", "Mtx",
         "Ptr", "ClassObject", "ClassObjectRef", "Half", "Data", "HalfVec3"]
CLASS = struct.pack("<I", 6) + b"Class\0"


def read_schema(d):
    """{class name: [(field name, type name, offset)]} in file order."""
    classes, cur = {}, None
    p = d.index(b"ClassList")
    end = d.index(b"MOBJ")
    while p < end - 8:
        if d[p:p + 10] == CLASS:
            m = struct.unpack_from("<I", d, p + 10)[0]
            cur = d[p + 14:p + 14 + m].rstrip(b"\0").decode()
            classes[cur] = []
            p += 14 + m
            continue
        t, n = struct.unpack_from("<II", d, p)
        name = d[p + 8:p + 8 + n]
        if t < len(TYPES) and 1 < n < 80 and re.fullmatch(rb"[ -~]+\0", name):
            s = name[:-1].decode()
            q = p + 8 + n
            off = struct.unpack_from("<I", d, q)[0]
            if cur and s not in ("Version", "Types"):
                classes[cur].append((s, TYPES[t], off & 0xFFFF))
            p = q + 16
            continue
        p += 1
    return classes


def objects(d):
    """[(object name, data start)] for every MOBJ block."""
    out = []
    for m in re.finditer(rb"MOBJ\0", d):
        for q in range(m.end(), m.end() + 12):
            n = struct.unpack_from("<I", d, q)[0]
            if 1 < n < 80 and re.fullmatch(rb"[ -~]+\0", d[q + 4:q + 4 + n]):
                out.append((d[q + 4:q + 3 + n].decode(), q + 4 + n))
                break
    return out


def value(d, typ, at):
    if typ == "Float":
        return struct.unpack_from("<f", d, at)[0]
    if typ in ("Colour3", "Vec3"):
        return struct.unpack_from("<3f", d, at)
    if typ == "Vec4":
        return struct.unpack_from("<4f", d, at)
    if typ == "Int":
        return struct.unpack_from("<i", d, at)[0]
    if typ == "Char":
        return d[at]
    return None


if __name__ == "__main__":
    import sys
    d = open(sys.argv[1], "rb").read()
    sch = read_schema(d)
    for c, f in sch.items():
        print(c, len(f), f[:8])
    for name, at in objects(d):
        print(name, hex(at))


SIZE = {"Char": 1, "Short": 2, "Int": 4, "Int64": 8, "Float": 4, "Colour3": 12, "Vec3": 12, "Vec4": 16, "Mtx": 64,
        "Half": 2, "HalfVec3": 6}


def decode(d, schema, cls, at):
    """Fields of one object, written one after another in schema order. Returns (dict, end)."""
    out = {}
    for name, typ, _ in schema[cls]:
        if name == "Placeable":  # nested: Platform, Mtx, Layer (the name is the MOBJ name)
            out["Platform"] = d[at]
            out["Mtx"] = struct.unpack_from("<16f", d, at + 1)
            out["Layer"] = d[at + 65]
            at += 66
        elif name == "Animatable":
            at += 4
        elif typ == "String":
            n = struct.unpack_from("<I", d, at)[0]
            out[name] = d[at + 4:at + 4 + n].rstrip(b"\0").decode("latin1")
            at += 4 + n
        elif typ in SIZE:
            out[name] = value(d, typ, at) if typ not in ("Mtx", "Short", "Int64", "Half", "HalfVec3") else \
                d[at:at + SIZE[typ]]
            at += SIZE[typ]
        else:
            raise ValueError(f"{cls}.{name}: type {typ} not supported")
    return out, at


def read_objects(d):
    """{object name: (class, fields)} for the light and post-effect objects."""
    sch = read_schema(d)
    out = {}
    for name, at in objects(d):
        for cls in ("DynamicLightDesc", "DynamicLight", "PostEffect"):
            try:
                f, end = decode(d, sch, cls, at)
            except (ValueError, struct.error, UnicodeDecodeError):
                continue
            nxt = d.find(b"MOBJ", at)
            nxt2 = d.find(b"OLST", at)
            stop = min(x for x in (nxt, nxt2, len(d)) if x >= 0)
            if end <= stop and stop - end < 64:
                out[name] = (cls, f)
                break
    return out
