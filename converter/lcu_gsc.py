"""Parser for LEGO City Undercover (PC, DX11) .GSC scene files: NU20 file version 0x43, MESH 0xA9.

Based on the LB2 parser in SchaustOmat/LEGO-Batman-2-x-Minecraft (converter/gsc.py) and the block
descriptions in JaanDev/lego-tt-nxg-formats. Structures are big endian; vertex and index data are
little endian (DX11 native).
"""
import struct

import numpy as np

# bytes per vertex attribute type
ATTR_SIZE = {0: 0, 1: 4, 2: 8, 3: 12, 4: 16, 5: 4, 6: 8, 7: 4, 8: 4, 9: 4}


class Reader:
    def __init__(self, data, pos=0):
        self.d = data
        self.p = pos

    def u8(self):
        v = self.d[self.p]
        self.p += 1
        return v

    def u16(self):
        v = struct.unpack_from(">H", self.d, self.p)[0]
        self.p += 2
        return v

    def u32(self):
        v = struct.unpack_from(">I", self.d, self.p)[0]
        self.p += 4
        return v

    def f32(self):
        v = struct.unpack_from(">f", self.d, self.p)[0]
        self.p += 4
        return v

    def bytes(self, n):
        v = self.d[self.p:self.p + n]
        self.p += n
        return v

    def tag(self, expected=None):
        t = self.d[self.p:self.p + 4][::-1].decode("latin1")
        if expected and t != expected:
            raise ValueError(f"expected {expected}, found {t!r} at {self.p:#x}")
        self.p += 4
        return t

    def str32(self):
        return self.bytes(self.u32()).rstrip(b"\0").decode("latin1")


class VertexBuffer:
    def __init__(self, count, attribs, raw):
        self.count = count
        self.attribs = attribs  # (semantic, type, offset)
        self.stride = sum(ATTR_SIZE[t] for _, t, _ in attribs)
        self.raw = raw

    def decode(self, semantic):
        """float32 array (count, n) for the attribute, or None."""
        for sem, typ, off in self.attribs:
            if sem == semantic:
                break
        else:
            return None
        buf = np.frombuffer(self.raw, dtype=np.uint8).reshape(self.count, self.stride)
        chunk = np.ascontiguousarray(buf[:, off:off + ATTR_SIZE[typ]])
        if typ in (2, 3, 4):
            return chunk.view("<f4").astype(np.float32).reshape(self.count, typ)
        if typ == 5:
            return chunk.view("<f2").astype(np.float32).reshape(self.count, 2)
        if typ == 6:
            return chunk.view("<f2").astype(np.float32).reshape(self.count, 4)
        if typ in (7, 9):
            c = chunk.astype(np.float32).reshape(self.count, 4) / 255.0
            # DX11 colours are B8G8R8A8: without the swap baked light turns blue (warm 0.82/0.78/0.72 read as
            # 0.72/0.78/0.82)
            return c[:, [2, 1, 0, 3]] if semantic in (2, 4) else c
        if typ == 8:  # signed mini vec4, -1..1
            return (chunk.astype(np.float32).reshape(self.count, 4) - 127.5) / 127.5
        return None


class BufferRef:
    def __init__(self, ref_id, extra):
        self.ref_id = ref_id
        self.extra = extra


def read_vertex_buffer(r, buffers):
    """0 = none, 1 = new buffer, bit 31 = reference to an earlier buffer."""
    flag = r.u32()
    if flag == 0:
        return None
    if flag & 0x80000000:
        return BufferRef(flag & 0x3FFFFFFF, r.u32())
    r.u32()  # flags
    count = r.u32()
    r.tag("VTXD")
    r.u32()  # version, same as MESH
    attribs = [(r.u8(), r.u8(), r.u8()) for _ in range(r.u32())]
    r.bytes(6)  # unknown, zero in every Cherry Tree Hills tile
    stride = sum(ATTR_SIZE[t] for _, t, _ in attribs)
    vb = VertexBuffer(count, attribs, r.bytes(count * stride))
    buffers.append(vb)
    return vb


def read_indices(r, buffers):
    flag = r.u32()
    if flag == 0:
        return None
    if flag & 0x80000000:
        return BufferRef(flag & 0x3FFFFFFF, r.u32())
    r.u32()  # flags
    count = r.u32()
    size = r.u32()
    arr = np.frombuffer(r.bytes(count * size), dtype="<u2" if size == 2 else "<u4").astype(np.uint32)
    buffers.append(arr)
    return arr


class Part:
    pass


def read_mesh_block(data, trace=False):
    r = Reader(data, data.index(b"HSEM"))
    r.tag("MESH")
    ver = r.u32()
    r.tag("VTOR")
    n = r.u32()
    vbufs, ibufs, parts = [], [], []
    for i in range(n):
        start = r.p
        p = Part()
        p.vbs = []
        for _ in range(r.u32()):
            vb = read_vertex_buffer(r, vbufs)
            p.vbs.append((vb, r.u32()))
        p.blend_vbs = []
        for _ in range(r.u32()):
            vb = read_vertex_buffer(r, vbufs)
            p.blend_vbs.append((vb, r.u32()))
        p.ib = read_indices(r, ibufs)
        p.ib_offset = r.u32()
        p.ib_count = r.u32()
        p.ib_base = r.u32()
        p.prim_type = r.u16()
        p.vb_used = r.u32()
        p.vb_inst_bits = r.u32()
        tail = r.p
        r.bytes(r.u32())  # skinMtxMap
        dyn = r.u32()
        if dyn > 0:
            r.u32()
            poses = 0
            while r.u32() != 0:
                poses += 1
                r.u32()
            for _ in range(poses):
                r.bytes(r.u32() * 12)
        r.u32()  # optFlags
        p.bounds = (r.bytes(16), r.bytes(16))
        r.f32()  # densityDiscDiameter
        r.u32()  # depthBits
        read_vertex_buffer(r, vbufs)
        r.u32()
        r.u32()
        read_indices(r, ibufs)
        r.u32(); r.u32(); r.u32()
        if trace:
            print(f"part {i} @ {start:#x} tail @ {tail:#x} end @ {r.p:#x} ib {p.ib_offset}+{p.ib_count} "
                  f"base {p.ib_base} used {p.vb_used} prim {p.prim_type}")
        parts.append(p)
    _resolve_refs(parts)
    return ver, parts, r.p


def _resolve_refs(parts):
    """Replaces BufferRef by the real buffer (sequential object IDs, start value found per file)."""
    objs, refs, rel = {}, [], 0
    for p in parts:
        for vb, _ in p.vbs + p.blend_vbs:
            if isinstance(vb, BufferRef):
                refs.append((vb, VertexBuffer))
            elif vb is not None:
                objs[rel] = vb
                rel += 1
        if isinstance(p.ib, BufferRef):
            refs.append((p.ib, np.ndarray))
        elif p.ib is not None:
            objs[rel] = p.ib
            rel += 1
        rel += 1  # the part itself
    if not refs:
        return
    # the base must also give each reference a buffer of the right kind (vertex vs index)
    for base in range(0, 100000):
        if all(isinstance(objs.get(x.ref_id - base), kind) for x, kind in refs):
            break
    else:
        raise ValueError("buffer references cannot be resolved")

    def get(x):
        return objs[x.ref_id - base] if isinstance(x, BufferRef) else x

    for p in parts:
        p.vbs = [(get(vb), off) for vb, off in p.vbs]
        p.blend_vbs = [(get(vb), off) for vb, off in p.blend_vbs]
        p.ib = get(p.ib)


if __name__ == "__main__":
    import sys
    d = open(sys.argv[1], "rb").read()
    ver, parts, end = read_mesh_block(d, trace=True)
    print("MESH", hex(ver), len(parts), "parts, ends", hex(end), "UMTL at", hex(d.index(b"LTMU")))


# ---------------------------------------------------------------------------
# DISP: display list (which part is drawn with which matrix)
# ---------------------------------------------------------------------------
CMD_MATRIX, CMD_GEO, CMD_LIGHTMAP = 0x83, 0xB3, 0xB0


def read_names(data):
    """NTBL name table, names are addressed by byte offset."""
    r = Reader(data, data.index(b"LBTN"))
    r.tag("NTBL")
    r.u32()
    blob = r.bytes(r.u32())
    return lambda off: blob[off:blob.index(b"\0", off)].decode("latin1")


def read_disp(data, trace=False):
    r = Reader(data, data.index(b"PSID"))
    r.tag("DISP")
    ver = r.u32()
    r.str32()

    def arr(fn, what):
        r.tag("VTOR")
        n = r.u32()
        if trace:
            print(f"  {what}: {n} @ {r.p:#x}")
        return [fn() for _ in range(n)]

    cmds = arr(lambda: (r.u8(), r.u8(), r.u32()), "cmds")
    clips = arr(lambda: (r.u16(), [r.u32() for _ in range(r.u32())], [r.u32() for _ in range(r.u32())]), "clips")
    return ver, r, cmds, clips


def read_disp_matrices(data, nmat, after):
    """Matrix table near the end of DISP 0x17: u32 count + count row-major 4x3 float matrices, followed by the
    next sub-array's tag ("ROTV" in the file) or, in every Cherry Tree Hills file, 24 bytes before TANB. Found
    by that signature instead of decoding every 0x17 sub-array; the count can exceed the highest index the
    display list uses. (Some builders, e.g. BUILDER_ICECREAM_PARLOUR, have non-empty arrays after the table.)"""
    end = data.index(b"BNAT")
    for q in range(end - 28, after - 1, -1):
        n = struct.unpack_from(">I", data, q)[0]
        if n < max(nmat, 1) or n > 200000 or q + 4 + n * 48 > end:
            continue
        t = q + 4 + n * 48
        if data[t:t + 4] != b"ROTV" and t != end - 24:
            continue
        m = np.frombuffer(data, ">f4", 12 * n, q + 4)
        if np.all(np.isfinite(m)) and np.abs(m).max() < 1e6:
            return m.astype(np.float64).reshape(n, 4, 3)
    raise ValueError("DISP matrix table not found")


def build_scene(data):
    """List of (part index, 4x4 row-vector matrix, display item, material index) in game coordinates."""
    _, r, cmds, clips = read_disp(data)
    nmat = max((i for c, _, i in cmds if c == CMD_MATRIX), default=-1) + 1
    mats = read_disp_matrices(data, nmat, r.p) if nmat else []
    mtl_of = {item: m for _, mtls, items in clips for m, item in zip(mtls, items)}
    out, cur = [], np.eye(4)
    for i, (cmd, _, idx) in enumerate(cmds):
        if cmd == CMD_MATRIX:
            cur = np.eye(4)
            cur[:, :3] = mats[idx]
            if abs(np.linalg.det(cur[:3, :3])) < 1e-9:
                # skinned models (vehicle .GHG) leave these zero: their parts are already in model space
                cur = np.eye(4)
        elif cmd == CMD_GEO:
            out.append((idx, cur.copy(), i, mtl_of.get(i)))
    return out


def part_geometry(p):
    """dict of position/normal/uv0/color0 (float32) and indices (uint32, relative) of one part."""
    n = p.vb_used
    out = {"indices": p.ib[p.ib_offset:p.ib_offset + p.ib_count].astype(np.uint32)}
    for vb, off in p.vbs:
        if vb is None:
            continue
        s = off // vb.stride + p.ib_base
        for sem, name in ((0, "position"), (1, "normal"), (5, "uv0"), (7, "uv1"), (2, "color0"), (4, "color1")):
            if name in out:
                continue
            a = vb.decode(sem)
            if a is not None:
                out[name] = a[s:s + n, :3] if name == "position" else a[s:s + n]
    return out


def read_texture_names(data):
    """Texture names from TXGH (ver 10: same layout as LB2)."""
    r = Reader(data, data.index(b"HGXT"))
    r.tag("TXGH")
    ver = r.u32()
    if ver != 10:
        raise ValueError(f"TXGH version {ver} not supported")
    r.tag("VTOR")
    [r.u32() for _ in range(r.u32())]
    r.tag("VTOR")
    names = []
    for _ in range(r.u32()):
        r.bytes(16)
        name = r.str32().replace("\\", "/").split("/")[-1]
        names.append(name.rsplit(".", 1)[0].lower())
        r.bytes(4)
    return names


def read_dds(blob):
    """Embedded DDS files in file order (GSC tail or .NXG_TEXTURES)."""
    starts, o = [], 4 + struct.unpack_from(">I", blob, 0)[0]
    while (o := blob.find(b"DDS |", o)) >= 0:
        starts.append(o)
        o += 4
    return [blob[s:e] for s, e in zip(starts, starts[1:] + [len(blob)])]


def read_lightmaps(data):
    """LMDT ver 2: per entry (colour lightmap texture index or None, uv offset, uv scale). Each entry names
    5 textures; slots 3 and 4 are the lightmap pair (3 = baked light with shadows, 4 = sun visibility).
    Indices 0-2 are the forced dummy textures: no lightmap."""
    r = Reader(data, data.index(b"TDML"))
    r.tag("LMDT")
    r.u32()
    out = []
    for _ in range(r.u32()):
        r.u32(), r.u32()  # type, meshInstanceID
        tids = [r.u32() for _ in range(5)]
        off, scale = (r.f32(), r.f32()), (r.f32(), r.f32())
        out.append((tids[3] if 2 < tids[3] < 0xFFFFFFFF else None, off, scale))
    return out


def item_lightmaps(data):
    """Lightmap entry per display item: the last LightMap command (0xB0) before it counts."""
    _, _, cmds, _ = read_disp(data)
    cur, out = None, {}
    for i, (cmd, _, idx) in enumerate(cmds):
        if cmd == CMD_LIGHTMAP:
            cur = idx
        elif cmd == CMD_GEO:
            out[i] = cur
    return out


def read_specials(data):
    """DISP specials (named objects of a scene): [(name, 4x4 row-vector matrix, clip index)].

    Same record order as LB2 (name, mtx, drawMtx, bounds, clip, flags, float list, ...), but DISP 0x17 has extra
    arrays in front, so records are found by their shape: a name that starts an NTBL string, an affine matrix
    (last column 0,0,0,1) and a clip index in range."""
    _, r, cmds, clips = read_disp(data)
    rn = Reader(data, data.index(b"LBTN"))
    rn.tag("NTBL")
    rn.u32()
    blob = rn.bytes(rn.u32())
    starts = {0} | {i + 1 for i, c in enumerate(blob) if c == 0}
    end = data.index(b"BNAT")
    out, q = [], r.p
    while q < end - 192:
        n = struct.unpack_from(">I", data, q)[0]
        if n in starts and n < len(blob):
            m = struct.unpack_from(">16f", data, q + 4)
            clip = struct.unpack_from(">I", data, q + 4 + 64 + 64 + 48)[0]
            if (m[3], m[7], m[11], m[15]) == (0.0, 0.0, 0.0, 1.0) and clip < len(clips) \
                    and all(abs(x) < 1e5 for x in m):
                name = blob[n:blob.index(b"\0", n)].decode("latin1")
                out.append((name, np.array(m).reshape(4, 4), clip))
                q += 4 + 64 + 64 + 48 + 8
                continue
        q += 4
    return out, clips
