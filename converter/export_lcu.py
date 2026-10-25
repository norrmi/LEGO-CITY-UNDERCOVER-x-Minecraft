"""Converts Cherry Tree Hills (SF_RESIDENTIAL) and Little Italy (NY_LITTLE_ITALY) where the game has them, the
police station inside, the NOON sky and the NOON lighting from the user's own
LEGO City Undercover install into the data the Minecraft mod loads.

    python export_lcu.py "<LEGO City Undercover folder>" <output folder> [<shader pack folder>]

Output (nothing from the game is ever shipped; this runs on the player's PC):
    meshes/<tile>.lcum   batched meshes per texture, already in Minecraft coordinates
    sky.lcum             sky dome, centred on the camera
    textures/<id>.png    decoded textures
    collision.bin        solid voxels per chunk (the mod turns them into barrier blocks)
    lighting.json        sun, sky, fog and post-effect values from LEGO_CITY_NOON_LIGHTING.LED
    world.json           scale, origin, spawn
"""
import glob
import io
import json
import mmap
import os
import re
import struct
import sys

import numpy as np
from PIL import Image

import lcu_gsc as g
import led

SCALE = 4.0               # blocks per game unit (same as LB2 x Minecraft; minifig ~1.8 blocks)
ORIGIN_Y = 64.0           # game y = 0 lands on Minecraft Y 64
LCU_EXPOSURE = 0.55       # see write_shader_constants; the mod uses the same value (LcuRenderer.EXPOSURE)
WORLD_SHIFT = (-639.0, ORIGIN_Y, 740.0)  # Minecraft offset of the district (centred on 0/0 in v0.1)
SLOT_DIFFUSE = 5          # UMTL 0xB2 localTID slot of the colour layer
SLOT_NORMAL = 11          # UMTL 0xB2 localTID slot of the colour layer's normal map (tangent space)
TID_BEFORE_NAME = 586     # UMTL 0xB2: localTID array starts this many bytes before the material name
TINT_AFTER_TID = 341      # UMTL 0xB2: colour of the colour layer, B G R (A) bytes, this far after localTID
EXCLUDE = ("_DRC_", "_ULD_", "_3DS_", "_TECH_", "NEW DIVIDE")  # Wii U pad, far LOD, 3DS, tech variants;
# NEW DIVIDE: a mission-state copy of Little Italy tile 2416 that would cover the canal and roofs in white

CITY = os.path.join("LEVELS", "LEGO_CITY", "LEGO_CITY")
HELPER = "#helper"           # draw id of helper meshes: collision only, never rendered
TEX_NAMES = {"white": "white"}  # texture id -> game texture name, written to textures.json


# ---------------------------------------------------------------------------
# textures
# ---------------------------------------------------------------------------
class SharedTextures:
    """LEGO_CITY_SHARED_TEXTURES: GUID -> DDS blob, read lazily from the 800 MB texture file."""

    def __init__(self, city):
        base = os.path.join(city, "LEGO_CITY_SHARED_TEXTURES_DX11")
        gsc = open(base + ".GSC", "rb").read()
        self.entries = txgh_entries(gsc)
        f = open(base + ".NXG_TEXTURES", "rb")
        self.mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
        starts, o = [], 4 + struct.unpack_from(">I", self.mm, 0)[0]
        while (o := self.mm.find(b"DDS |", o)) >= 0:
            starts.append(o)
            o += 4
        self.spans = list(zip(starts, starts[1:] + [len(self.mm)]))
        local = [e for e in self.entries if e[1]]
        if len(local) != len(self.spans):
            raise ValueError(f"shared textures: {len(local)} named entries but {len(self.spans)} DDS")
        self.by_guid = {e[0]: i for i, e in enumerate(local)}

    def name(self, guid):
        i = self.by_guid.get(guid)
        return [e for e in self.entries if e[1]][i][1] if i is not None else ""

    def get(self, guid):
        i = self.by_guid.get(guid)
        if i is None:
            return None
        s, e = self.spans[i]
        return self.mm[s:e]


def txgh_entries(data):
    """[(guid bytes, name, external index)] per TXGH entry. Named entries are stored in the file's own
    texture list; unnamed ones point (index >> 16, 1-based) into the RES file's external GUID list."""
    r = g.Reader(data, data.index(b"HGXT"))
    r.tag("TXGH")
    r.u32()
    r.tag("VTOR")
    [r.u32() for _ in range(r.u32())]
    r.tag("VTOR")
    out = []
    for _ in range(r.u32()):
        guid = r.bytes(16)
        name = r.str32().replace("\\", "/").split("/")[-1].rsplit(".", 1)[0].lower()
        ext = r.u32() >> 16
        out.append((guid, name, ext))
    return out


RES_TEXTURE = re.compile(rb"\x00\x00\x00\x05\x00\x00\x00.\x00\x00\x00\x01(.{16})", re.S)


def res_guids(res, known=None):
    """External texture GUIDs from a .GSC.RES, one per texture record in file order (records end with
    u32 type 5, u32 name index, u32 1, 16-byte GUID). Duplicates and GUIDs from other texture sets stay in the
    list: TXGH's external indices count records, so dropping one would shift every later texture (Little Italy
    tiles 2217/2218 list some textures twice and drew normal maps as colour)."""
    return [m.group(1) for m in RES_TEXTURE.finditer(res)]


GLASS_WORDS = ("glass", "window")
# additive glows in the game (light cones under lamps, halos): black means nothing there. Drawn blended with
# alpha = brightness, never solid (they were black boxes you could not walk through)
GLOW_WORDS = ("glow_gradient", "lightbeam", "light_beam", "lightshaft", "light_shaft", "godray", "lightcone",
              "light_cone", "glow_")


def is_glow(tid):
    return any(w in TEX_NAMES.get(tid.partition("#")[0].split("+")[0], "") for w in GLOW_WORDS)
DECAL_WORDS = ("puddle", "decal", "dirtalpha", "stain", "smear", "_mask_c")
PUDDLE_ALPHA = 0.3        # puddle masks as a light wet darkening


def save_texture(blob, path, name=""):
    """DDS -> PNG. Returns how the texture is drawn: "cutout", "translucent" or "" (opaque).

    Alpha is often data in LCU: *_diffh textures keep a height map there for layer blending, so asphalt, rock
    and grass would get holes punched in them. Cut-out only for real masks: plenty of fully transparent texels
    and few in-between values, and never for height textures. Glass (name says glass/window, alpha mostly in
    between, e.g. police_interior_windows at 0.3-0.5) is translucent and keeps its alpha."""
    glass = any(w in name for w in GLASS_WORDS)
    decal = any(w in name for w in DECAL_WORDS)
    if any(w in name for w in GLOW_WORDS):
        img = Image.open(io.BytesIO(bytes(blob)))
        img.load()
        a = np.asarray(img.convert("RGBA")).copy()
        a[:, :, 3] = (a[:, :, 3].astype(np.float32) * a[:, :, :3].max(2) / 255).astype(np.uint8)
        Image.fromarray(a, "RGBA").save(path)
        return "translucent"
    if decal:
        # soft decals (puddle masks, dirt, stains) overlay the surface below: keep their alpha, draw blended.
        # The puddle mask is black where it is wet; in the game it drives a wet shine, here a light darkening.
        img = Image.open(io.BytesIO(bytes(blob)))
        img.load()
        img = img.convert("RGBA")
        if "puddle" in name or name.endswith("_mask_c"):
            a = np.asarray(img).copy()
            a[:, :, 3] = (a[:, :, 3].astype(np.float32) * PUDDLE_ALPHA).astype(np.uint8)
            img = Image.fromarray(a, "RGBA")
        img.save(path)
        return "translucent"
    if not os.path.exists(path) or (glass and Image.open(path).mode != "RGBA"):
        img = Image.open(io.BytesIO(bytes(blob)))
        img.load()
        img.convert("RGBA" if img.mode == "RGBA" else "RGB").save(path)
    img = Image.open(path)
    if img.mode != "RGBA":
        return ""
    if name.endswith("_diffh") or name.endswith("_h"):
        img.convert("RGB").save(path)
        return ""
    a = np.asarray(img)[:, :, 3]
    between = ((a > 16) & (a < 240)).mean()
    if glass and between > 0.3:
        return "translucent"
    if (a <= 16).mean() > 0.01 and between < 0.35:
        return "cutout"
    img.convert("RGB").save(path)  # alpha was data: drop it, every shader discards low alpha
    return ""


def save_normal_map(blob, path):
    """DDS -> RGB PNG. LCU normal maps are plain tangent-space RGB (mostly DXT1, ~0.5/0.5/0.9 on average)."""
    if not os.path.exists(path):
        img = Image.open(io.BytesIO(bytes(blob)))
        img.load()
        img.convert("RGB").save(path)


# ---------------------------------------------------------------------------
# materials
# ---------------------------------------------------------------------------
def material_textures(data, ntex):
    """(diffuse texture index or None, tint (r, g, b) 0..1, normal map index or None) per UMTL material.

    The tint is the colour-layer colour that the game multiplies into the texture (shader constant c0 in LB2):
    the Cherry Tree Hills houses share near-white plank and plaster textures and get their paint from it.

    A material is recognised by its localTID array (only texture indices or 0xFFFFFFFF) followed,
    TID_BEFORE_NAME bytes later, by its name (u16 length incl. NUL, then the text)."""
    o = data.index(b"LTMU")
    count = struct.unpack_from(">I", data, o + 12)[0]
    end = data.index(b"TDML")
    found = []
    for t in range(o + 16, end - TID_BEFORE_NAME):
        n = struct.unpack_from(">H", data, t + TID_BEFORE_NAME - 2)[0]
        if not 2 <= n <= 256 or data[t + TID_BEFORE_NAME + n - 1] != 0 or 0 in data[t + TID_BEFORE_NAME:t + TID_BEFORE_NAME + n - 1]:
            continue
        slots = struct.unpack_from(">18I", data, t)
        if all(x < ntex or x == 0xFFFFFFFF for x in slots) and slots[17] == 0xFFFFFFFF:
            b, g_, r = data[t + TINT_AFTER_TID:t + TINT_AFTER_TID + 3]
            # applied in full: the fourth byte is not a strength (at full tint the _Wood_Shiplap houses come out
            # orange, blue and brown as in the game)
            found.append((slots, (r / 255, g_ / 255, b / 255)))
    if len(found) != count:
        # a few Little Italy tiles have materials laid out differently: draw those untextured instead of failing
        print(f"  UMTL: {len(found)} of {count} materials recognised, the rest drawn white")
        found = (found + [((0xFFFFFFFF,) * 18, (1.0, 1.0, 1.0))] * count)[:count]
    return [(s[SLOT_DIFFUSE] if s[SLOT_DIFFUSE] < ntex else None, tint,
             s[SLOT_NORMAL] if s[SLOT_NORMAL] < ntex else None) for s, tint in found]


# ---------------------------------------------------------------------------
# lightmaps
# ---------------------------------------------------------------------------
LM_MAX_DEPTH = 3          # each split quarters a triangle; 3 levels = up to 64 pieces
LM_MIN_EDGE = 2.0         # blocks: do not split triangles smaller than this
LM_SPLIT = 0.12           # split where the lightmap changes more than this (0..1) across a triangle


def bilinear(img, uv):
    """img (h, w, 3) float, uv (n, 2) -> (n, 3); clamped (lightmap charts must not wrap)."""
    h, w = img.shape[:2]
    x = np.clip(uv[:, 0] * w - 0.5, 0, w - 1)
    y = np.clip(uv[:, 1] * h - 0.5, 0, h - 1)
    x0, y0 = np.floor(x).astype(int), np.floor(y).astype(int)
    x1, y1 = np.minimum(x0 + 1, w - 1), np.minimum(y0 + 1, h - 1)
    fx, fy = (x - x0)[:, None], (y - y0)[:, None]
    return (img[y0, x0] * (1 - fx) * (1 - fy) + img[y0, x1] * fx * (1 - fy)
            + img[y1, x0] * (1 - fx) * fy + img[y1, x1] * fx * fy)


def subdivide_lit(tri, lm):
    """tri: (n, 3, k) corner attributes [pos 0:3, lmuv 3:5, rest...]. Splits triangles (4-way, midpoints) where
    the lightmap varies across them, so baked shadows survive per-vertex lighting. Returns (m, 3, k)."""
    done = []
    for _ in range(LM_MAX_DEPTH):
        if len(tri) == 0:
            break
        a, b, c = tri[:, 0], tri[:, 1], tri[:, 2]
        ab, bc, ca = (a + b) / 2, (b + c) / 2, (c + a) / 2
        edge = np.max(np.linalg.norm(np.stack([a - b, b - c, c - a])[..., :3], axis=2), axis=0)
        probe = np.stack([a, b, c, ab, bc, ca, (a + b + c) / 3], 1)[..., 3:5]
        lum = bilinear(lm, probe.reshape(-1, 2)).mean(1).reshape(len(tri), 7)
        split = (edge > LM_MIN_EDGE) & ((lum.max(1) - lum.min(1)) > LM_SPLIT)
        done.append(tri[~split])
        t = tri[split]
        a, b, c = t[:, 0], t[:, 1], t[:, 2]
        ab, bc, ca = (a + b) / 2, (b + c) / 2, (c + a) / 2
        tri = np.concatenate([np.stack(x, 1) for x in ((a, ab, ca), (ab, b, bc), (ca, bc, c), (ab, bc, ca))])
    done.append(tri)
    return np.concatenate(done)


def decode_lightmap(blob):
    img = Image.open(io.BytesIO(bytes(blob)))
    img.load()
    return np.asarray(img.convert("RGB"), dtype=np.float32) / 255


# ---------------------------------------------------------------------------
# geometry
# ---------------------------------------------------------------------------
def to_minecraft(p, origin):
    """Game (left handed, y up, units) -> Minecraft blocks: mirror X, scale, move to the world origin."""
    out = p * SCALE
    out[:, 0] = -out[:, 0]
    return out + origin


def export_gsc(path, shared, origin, texdir, tints=True, lightmap=True, tag_items=False):
    """The file's draws as [(texture id, pos, uv, col, nrm, idx)] in Minecraft coordinates.
    tints=False for the sky: its materials are coloured by the game's sky shader (fog colours), their tint is 0."""
    draws = []
    data = open(path, "rb").read()
    entries = txgh_entries(data)
    res = open(path + ".RES", "rb").read() if os.path.exists(path + ".RES") else b""
    ext = res_guids(res, shared.by_guid) if shared else []
    local = g.read_dds(open(path[:-4] + ".NXG_TEXTURES", "rb").read()) \
        if os.path.exists(path[:-4] + ".NXG_TEXTURES") else g.read_dds(data)
    # texture index -> (id, blob)
    tex, li = [], 0
    for guid, name, e in entries:
        if name:
            tex.append((guid.hex(), local[li] if li < len(local) else None))
            TEX_NAMES[guid.hex()] = name
            li += 1
        elif 0 < e <= len(ext):
            tex.append((ext[e - 1].hex(), shared.get(ext[e - 1])))
            TEX_NAMES[ext[e - 1].hex()] = shared.name(ext[e - 1])
        else:
            tex.append((None, None))
    mats = material_textures(data, len(entries))
    _, parts, _ = g.read_mesh_block(data)
    lightmaps = g.read_lightmaps(data) if lightmap else []
    item_lm = g.item_lightmaps(data) if lightmap else {}
    lm_cache = {}
    for pi, m, item, mi in g.build_scene(data):
        geo = g.part_geometry(parts[pi])
        if "position" not in geo or len(geo["indices"]) < 3:
            continue
        ti, tint, ni = mats[mi] if mi is not None and mi < len(mats) else (None, (1.0, 1.0, 1.0), None)
        tid, blob = tex[ti] if ti is not None else (None, None)
        if tints and (tid is None or blob is None) and "uv0" not in geo and "color0" in geo \
                and geo["color0"].min() > 0.999:
            # (district only: the sky's horizon bowl is built the same way and coloured by the sky shader)
            # helper mesh: no texture, no UVs, no baked light (vertex colour exactly 1) - terrain/collision shells
            # and the shadow imposter. The game never draws these as coloured surfaces; collision only.
            tid = HELPER
        elif tid is None or blob is None:
            tid = "white"
        else:
            png = os.path.join(texdir, tid + ".png")
            try:
                mode = save_texture(blob, png, TEX_NAMES.get(tid, ""))
            except Exception as ex:  # unsupported DDS format: fall back to white
                print("  texture", tid, "skipped:", ex)
                tid, mode = "white", ""
            # the material's tangent-space normal map (slot 11) travels with the colour texture: "colour+normal"
            nid, nblob = tex[ni] if ni is not None else (None, None)
            if tid != "white" and nid is not None and nblob is not None:
                try:
                    save_normal_map(nblob, os.path.join(texdir, nid + ".png"))
                    tid += "+" + nid
                except Exception as ex:
                    print("  normal map", nid, "skipped:", ex)
            if mode:
                tid += "#" + mode
        n = len(geo["position"])
        pos = np.c_[geo["position"], np.ones(n)] @ m
        pos = to_minecraft(pos[:, :3], origin)
        nrm = geo.get("normal")
        if nrm is not None:
            nrm = nrm[:, :3] @ m[:3, :3]
            nrm[:, 0] = -nrm[:, 0]
            nrm /= np.maximum(np.linalg.norm(nrm, axis=1, keepdims=True), 1e-6)
        else:
            nrm = np.tile([0.0, 1.0, 0.0], (n, 1))
        uv = geo["uv0"][:, :2] if "uv0" in geo else np.zeros((n, 2))
        # baked vertex light: the game draws texture * 2 * colour (same as LB2)
        col = 2 * geo["color0"][:, :3] if "color0" in geo else np.ones((n, 3))  # clipped after the lightmap
        if tints:
            col = col * np.array(tint)
        idx = geo["indices"].reshape(-1, 3)[:, [0, 2, 1]]  # mirrored X -> flip winding
        if idx.max() >= n:
            continue
        # baked lightmap (LMDT): light = 2 x vertex colour x lightmap, sampled per vertex on subdivided triangles
        e = item_lm.get(item)
        lm_tex = lightmaps[e][0] if e is not None and e < len(lightmaps) else None
        lmuv = geo["uv1"][:, :2] if "uv1" in geo else geo["uv0"][:, 2:4] if "uv0" in geo and geo["uv0"].shape[1] == 4             else None
        if lm_tex is not None and (lmuv is None or tex[lm_tex][1] is None):
            lm_tex = None
        if lm_tex is not None:
            if lm_tex not in lm_cache:
                lm_cache[lm_tex] = decode_lightmap(tex[lm_tex][1])
            _, off, sc = lightmaps[e]
            lmuv = lmuv * np.array(sc) + np.array(off)
            # parts without their own lightmap UV set keep tiling texture coordinates in uv0.zw; those (and
            # charts that land on the atlas's black background) keep their vertex light
            if lmuv.min() < -0.01 or lmuv.max() > 1.01 or bilinear(lm_cache[lm_tex], lmuv).mean() < 0.05:
                lm_tex = None
        if lm_tex is not None:
            lm = lm_cache[lm_tex]
            corner = np.concatenate([pos, lmuv, uv, col, nrm], 1)[idx]          # (t, 3, 13)
            corner = subdivide_lit(corner, lm).reshape(-1, 13)
            pos, lmuv, uv, col, nrm = (corner[:, 0:3], corner[:, 3:5], corner[:, 5:7], corner[:, 7:10],
                                       corner[:, 10:13])
            col = col * bilinear(lm, lmuv)  # col is 2 x vertex colour x tint
            # weld the split corners back into shared vertices
            key = np.round(np.concatenate([pos * 64, uv * 1024, col * 255], 1)).astype(np.int64)
            _, first, inverse = np.unique(key, axis=0, return_index=True, return_inverse=True)
            pos, uv, col, nrm = pos[first], uv[first], col[first], nrm[first]
            idx = inverse.reshape(-1, 3)
            n = len(pos)
        # the game's light goes above 1 (texture x 2 x light x tint; the tintable textures are mid grey):
        # store half, 0..2 range; the mod and the shader pack double it (vertex alpha 128 marks this)
        draw = (tid, pos, uv, np.clip(col / 2 if tints else col, 0, 1), nrm, idx)
        draws.append(draw + (item,) if tag_items else draw)  # tag_items: + display item (export_props)
    return draws


def batch(draws):
    """Merges draws per texture: {texture id: [pos, uv, col, nrm, idx, vertex count]}."""
    batches = {}
    for tid, pos, uv, col, nrm, idx in draws:
        b = batches.setdefault(tid, [[], [], [], [], [], 0])
        b[0].append(pos); b[1].append(uv); b[2].append(col); b[3].append(nrm); b[4].append(idx + b[5])
        b[5] += len(pos)
    return batches


def write_lcum(path, batches, hdr=True, interior=False):
    """LCUM v1: 'LCUM' u32 version u32 batches; per batch: u16 len + texture id, u32 flags (1 = cutout, 2 = translucent glass,
    4 = interior: lit only by its own baked light, vertex alpha 124 instead of 128),
    u32 vertex count, u32 index count, vertices (3f pos, 2f uv, 4u8 colour, 4i8 normal), u32 indices.
    Little endian."""
    with open(path, "wb") as f:
        f.write(b"LCUM" + struct.pack("<II", 1, len(batches)))
        for tid, (P, U, C, N, I, nv) in batches.items():
            name, _, mode = tid.partition("#")
            P, U, C, N, I = (np.concatenate(x) for x in (P, U, C, N, I))
            v = np.zeros(len(P), dtype=[("p", "<f4", 3), ("u", "<f4", 2), ("c", "u1", 4), ("n", "i1", 4)])
            v["p"], v["u"] = P, U
            v["c"][:, :3] = (C * 255).round()
            v["c"][:, 3] = (124 if interior else 128) if hdr else 255  # colour stored halved (see export_gsc)
            v["n"][:, :3] = (N * 127).round()
            nb = name.encode()
            f.write(struct.pack("<H", len(nb)) + nb)
            f.write(struct.pack("<III", {"cutout": 1, "translucent": 2}.get(mode, 0) | (4 if interior else 0),
                                len(P), I.size))
            f.write(v.tobytes())
            f.write(I.astype("<u4").tobytes())


# ---------------------------------------------------------------------------
# collision
# ---------------------------------------------------------------------------
def voxelize(tris):
    """Solid voxels touched by the triangles (sampled at <= 0.4 block spacing). Returns int array (n, 3)."""
    vox = []
    for T in tris:
        a, b, c = T[:, 0], T[:, 1], T[:, 2]
        edge = np.maximum.reduce([np.linalg.norm(b - a, axis=1), np.linalg.norm(c - a, axis=1),
                                  np.linalg.norm(c - b, axis=1)])
        steps = np.ceil(edge / 0.4).astype(int) + 1
        for k in np.unique(steps):
            sel = steps == k
            i, j = np.meshgrid(np.arange(k + 1), np.arange(k + 1))
            keep = (i + j) <= k
            u, w = i[keep] / k, j[keep] / k
            pts = (a[sel, None] * (1 - u - w)[None, :, None] + b[sel, None] * u[None, :, None]
                   + c[sel, None] * w[None, :, None])
            vox.append(np.unique(np.floor(pts.reshape(-1, 3)).astype(np.int32), axis=0))
    return np.unique(np.concatenate(vox), axis=0)


FOLIAGE_WORDS = ("leaf", "leaves", "bush", "plant", "ivy", "fern", "palm", "flower", "branch", "hedge", "grassy",
                 "treeclump", "tree_leaves", "curlys")


def write_collision_mesh(path, tris):
    """'LCUT' u32 version u32 count, then count x 9 float32 (three corners, Minecraft coordinates). Degenerate
    triangles (area ~0) are left out."""
    t = np.concatenate(tris).astype(np.float32)
    area = np.linalg.norm(np.cross(t[:, 1] - t[:, 0], t[:, 2] - t[:, 0]), axis=1)
    t = t[area > 1e-6]
    with open(path, "wb") as f:
        f.write(b"LCUT" + struct.pack("<II", 1, len(t)))
        f.write(t.astype("<f4").tobytes())


SEA_WORDS = ("bm_sea", "waterfoaming", "seaweed_boundary", "tidefoam")  # sea plane + shoreline decals
SEA_Y = ORIGIN_Y + 0.35 * SCALE   # the game's sea plane: game y ~0.35 -> Minecraft Y ~65.4
SEA_MARGIN = 160.0                # blocks of sea kept around the district
# swimmable water is part of the world itself (make_world.py: flat layers, water Y 59-64)
WATER_SURFACE = 64 + 8 / 9        # top of a Minecraft water source at Y 64
SEA_RGB = np.array([0.19, 0.24, 0.24])  # replaced by bm_sea02's average when the sea is exported
SEA_ALPHA = 215                    # the game's water surface over Minecraft's water: mostly opaque
SEA_LIGHT = 1.0                    # vertex light of the water surface (2.0 after the HDR doubling)


def write_collision(path, vox):
    """'LCUC' u32 version u32 chunks; per chunk: i32 cx, i32 cz, u32 count, count x (u8 x<<4|z, i16 y)."""
    cx, cz = vox[:, 0] >> 4, vox[:, 2] >> 4
    order = np.lexsort((vox[:, 1], cz, cx))
    vox, cx, cz = vox[order], cx[order], cz[order]
    keys = np.c_[cx, cz]
    starts = np.r_[0, np.flatnonzero(np.any(keys[1:] != keys[:-1], axis=1)) + 1, len(vox)]
    with open(path, "wb") as f:
        f.write(b"LCUC" + struct.pack("<II", 1, len(starts) - 1))
        for s, e in zip(starts, starts[1:]):
            f.write(struct.pack("<iiI", cx[s], cz[s], e - s))
            v = np.zeros(e - s, dtype=[("xz", "u1"), ("y", "<i2")])
            v["xz"] = ((vox[s:e, 0] & 15) << 4) | (vox[s:e, 2] & 15)
            v["y"] = vox[s:e, 1]
            f.write(v.tobytes())


# ---------------------------------------------------------------------------
# lighting
# ---------------------------------------------------------------------------
TIMES = ("noon", "dawn", "dusk")


def _obj(objs, *names):
    """Object by name, ignoring spaces/underscores/case (DUSK writes "Sun Light", NOON "Sun_Light")."""
    key = {k.replace(" ", "").replace("_", "").lower(): v for k, v in objs.items()}
    for n in names:
        if n in key:
            return key[n][1]
    raise KeyError(names)


def read_lighting(city, tod):
    d = open(os.path.join(city, f"LEGO_CITY_{tod.upper()}_LIGHTING.LED"), "rb").read()
    objs = led.read_objects(d)
    sun, sky, fog = _obj(objs, "sunmainlight"), _obj(objs, "skyfilllight"), _obj(objs, "fog01", "fog")
    bloom = _obj(objs, "bloom")
    mtx = np.array(_obj(objs, "sunlight")["Mtx"]).reshape(4, 4)
    d_game = -mtx[2, :3]                       # light shines along +row 2; the sun sits opposite
    d_mc = np.array([-d_game[0], d_game[1], d_game[2]])
    d_mc /= np.linalg.norm(d_mc)

    def clean(f):
        return {k: (round(v, 5) if isinstance(v, float) else [round(x, 5) for x in v] if isinstance(v, tuple)
                    else v) for k, v in f.items() if isinstance(v, (int, float, tuple, str))}

    return {
        "source": f"LEGO_CITY_{tod.upper()}_LIGHTING.LED",
        "sun_direction": [round(x, 5) for x in d_mc],   # towards the sun, Minecraft axes
        "sun_colour": list(sun["Base Color"]), "sun_intensity": sun["Intensity"],
        "sun_shadow_intensity": sun["Shadow Intensity"],
        "sky_colour": list(sky["Base Color"]), "sky_intensity": sky["Intensity"],
        "fog_colour": list(fog["Fog Colour"]), "lower_fog_colour": list(fog["Lower Fog Colour"]),
        "fog_near": fog["Fog Near"] * SCALE, "fog_far": fog["Fog Far"] * SCALE,
        "bloom_threshold": bloom["Bloom Threshold (Last-Gen)"],
        "bloom_intensity": bloom["Bloom Intensity (Last-Gen)"],
        "objects": {k: {"class": c, **clean(f)} for k, (c, f) in objs.items()},
    }


def export_lighting(city, out_dir):
    """lighting_<time>.json for NOON, DAWN and DUSK (+ lighting.json = noon for older mod versions).

    The tiles' lightmaps are baked for noon only, so dawn and dusk re-colour that baked light: "grade" is
    (sky fill + sun) at that time divided by the same at noon. That ratio is this converter's approximation;
    the game relights with its dynamic sun instead."""
    lights = {t: read_lighting(city, t) for t in TIMES}

    def light(l):
        return np.array(l["sky_colour"]) * 0.5 + np.array(l["sun_colour"]) * l["sun_intensity"]

    for t, l in lights.items():
        g = light(l) / light(lights["noon"])
        # keep the hue, not the drop in brightness: the game's auto exposure brightens dawn and dusk again
        l["grade"] = [round(x, 5) for x in g / g.max()]
        json.dump(l, open(os.path.join(out_dir, f"lighting_{t}.json"), "w"), indent=1)
    json.dump(lights["noon"], open(os.path.join(out_dir, "lighting.json"), "w"), indent=1)
    return lights


# ---------------------------------------------------------------------------
AREAS = ("SF_RESIDENTIAL", "NY_LITTLE_ITALY")  # districts, each where the game has it (same world shift)


def in_boxes(c, boxes):
    """Rows of c (points) inside any of the (lo, hi) boxes."""
    ok = np.zeros(len(c), dtype=bool)
    for lo, hi in boxes:
        ok |= np.all((c >= lo) & (c <= hi), axis=1)
    return ok


def main(game, out, shaderpack=None):
    city = os.path.join(game, CITY)
    os.makedirs(os.path.join(out, "meshes"), exist_ok=True)
    texdir = os.path.join(out, "textures")
    os.makedirs(texdir, exist_ok=True)
    Image.new("RGB", (4, 4), (255, 255, 255)).save(os.path.join(texdir, "white.png"))
    shared = SharedTextures(city)
    print("shared textures:", len(shared.spans))

    import export_parkour
    import export_props
    per_file, boxes, tiles = {"zz_props": [], "parkour": []}, [], []
    margin = 40 * SCALE
    for name in AREAS:
        area = os.path.join(city, name)
        files = sorted(f for f in glob.glob(os.path.join(area, "**", "*_DX11.GSC"), recursive=True)
                       if not any(x in os.path.basename(f).upper() for x in EXCLUDE))
        area_draws = {os.path.basename(f)[:-len("_DX11.GSC")].lower(): export_gsc(f, shared, np.zeros(3), texdir)
                      for f in files}
        per_file.update(area_draws)
        area_tiles = sorted({os.path.dirname(f) for f in files})
        tiles += area_tiles
        # city props (lamp posts, benches, trees, signs, playground...) placed by the tiles' *_GZ2.LED
        per_file["zz_props"] += export_props.export_props(game, shared, texdir, area_tiles)
        # a few draws sit kilometres away from the district (props parked far off): keep what lies inside the
        # 1st..99th percentile box of the district's draw centres plus a margin
        cen = np.array([d[1].mean(0) for ds in area_draws.values() for d in ds])
        boxes.append((np.percentile(cen, 1, axis=0) - margin, np.percentile(cen, 99, axis=0) + margin))
        print(name, len(files), "tiles, box", boxes[-1][0].round(), boxes[-1][1].round())
    # LEGO objects the tiles place as gizmos (tables, nets, catapults...): with the props, so they switch off too
    import export_gizprops
    gizlib = export_gizprops.Library(game, shared, texdir)
    per_file["zz_props"] += export_gizprops.export_gizprops(gizlib, tiles)
    # the blue-and-white free-running pieces; the climbable ones also go to climb.json
    per_file["parkour"], climbs = export_parkour.export_parkour(game, shared, texdir, tiles)
    lo = np.min([b[0] for b in boxes], axis=0) + margin
    hi = np.max([b[1] for b in boxes], axis=0) - margin
    # world origin: centre of the kept draws on X/Z 0/0, game y 0 on Y 64
    # fixed since v0.1: saved worlds store barrier blocks at these coordinates, so the origin must never drift
    # (the percentile box above moves by a block when lightmap subdivision changes the draw centres)
    shift = np.array(WORLD_SHIFT, dtype=float)
    print("draw box", lo.round(), hi.round(), "shift", shift)

    tris, prop_tris, legacy, manifest, dropped, helpers = [], [], [], [], 0, 0
    for name, ds in per_file.items():
        kept = []
        for tid, pos, uv, col, nrm, idx in ds:
            # per triangle as well: some parts carry a cluster of triangles kilometres away (tile 0820)
            c = pos[idx].mean(1)
            ok = in_boxes(c, boxes)
            dropped += int((~ok).sum())
            idx = idx[ok]
            if len(idx) == 0:
                continue
            pos = pos + shift
            colour, _, mode = tid.partition("#")
            if name.startswith("sf_residential"):
                legacy.append(pos[idx])  # barriers of old versions were only ever placed in Cherry Tree Hills
            if not is_glow(tid) and not (mode == "cutout" and any(w in TEX_NAMES.get(colour.split("+")[0], "") for w in FOLIAGE_WORDS)):
                # foliage and glows stay walk-through, as in the game; props apart so the mod can switch them off
                (prop_tris if name == "zz_props" else tris).append(pos[idx])
            if tid == HELPER:
                helpers += len(idx)
                continue
            kept.append((tid, pos, uv, col, nrm, idx))
        batches = batch(kept)
        write_lcum(os.path.join(out, "meshes", name + ".lcum"), batches)
        nv = sum(b[5] for b in batches.values())
        manifest.append({"name": name, "batches": len(batches), "vertices": int(nv)})
        print(f"{name}: {len(batches)} batches, {nv} vertices")
    print("dropped far triangles:", dropped, "- helper triangles (collision only):", helpers)
    json.dump([[round(float(v), 2) for v in np.r_[a + shift, b + shift]] for a, b in climbs],
              open(os.path.join(out, "climb.json"), "w"))

    # interiors: the police station's floors far from the districts, the ice cream parlour under Little Italy
    import export_police
    sites = []
    for site, rooms in export_police.export_interiors(game, shared, texdir, gizlib):
        for fid, draws in rooms:
            kept = []
            for tid, pos, uv, col, nrm, idx in draws:
                colour, _, mode = tid.partition("#")
                if not is_glow(tid) and not (mode == "cutout" and any(w in TEX_NAMES.get(colour.split("+")[0], "") for w in FOLIAGE_WORDS)):
                    tris.append(pos[idx])
                if tid != HELPER:
                    kept.append((tid, pos, uv, col, nrm, idx))
            batches = batch(kept)
            name = f"interior_{site['id']}_{fid}"
            write_lcum(os.path.join(out, "meshes", name + ".lcum"), batches, interior=True)
            manifest.append({"name": name, "batches": len(batches),
                             "vertices": int(sum(b[5] for b in batches.values()))})
        sites.append(site)
    json.dump({"sites": sites}, open(os.path.join(out, "interiors.json"), "w"), indent=1)
    for old in glob.glob(os.path.join(out, "meshes", "police_*.lcum")) + [os.path.join(out, "police.json")]:
        if os.path.exists(old):
            os.remove(old)  # v0.6 names
    origin = shift + [0, 0, 0]

    for t in TIMES:
        sky = batch(export_gsc(os.path.join(city, f"LEGO_CITY_{t.upper()}_DX11.GSC"), shared, np.zeros(3), texdir,
                               tints=False, lightmap=False))
        write_lcum(os.path.join(out, f"sky_{t}.lcum"), sky, hdr=False)
        if t == "noon":
            write_lcum(os.path.join(out, "sky.lcum"), sky, hdr=False)
        print(f"sky {t}:", len(sky), "batches")

    # the mod collides against the triangles themselves (collision_mesh.bin); the voxels are only kept so it can
    # remove the barrier blocks that versions before 0.4 placed in existing worlds
    write_collision_mesh(os.path.join(out, "collision_mesh.bin"), tris)
    write_collision_mesh(os.path.join(out, "collision_props.bin"), prop_tris or [np.zeros((0, 3, 3))])
    vox = voxelize(legacy)  # all geometry, like the barriers were placed (foliage included)

    # the sea: LEGO_CITY_DX11.GSC has one plane (bm_sea02) at game height ~0.35 plus shoreline foam and seaweed
    # edges; keep what lies around the district at sea level
    # around each district, not the empty sea between them
    sea_boxes = [((a + shift)[[0, 2]] - SEA_MARGIN + margin, (b + shift)[[0, 2]] + SEA_MARGIN - margin) for a, b in boxes]
    sea = []
    for tid, pos, uv, col, nrm, idx in export_gsc(os.path.join(city, "LEGO_CITY_DX11.GSC"), shared, np.zeros(3), texdir):
        name = TEX_NAMES.get(tid.partition("#")[0].split("+")[0], "")
        if not any(w in name for w in SEA_WORDS):
            continue
        if name.startswith("bm_sea"):
            # the flat sea plane: the shader pack draws Minecraft's water in the game's sea colour instead
            # (reflections, waves); keep its average colour for that
            global SEA_RGB
            src = os.path.join(texdir, tid.partition("#")[0].split("+")[0] + ".png")
            SEA_RGB = np.asarray(Image.open(src).convert("RGB")).reshape(-1, 3).mean(0) / 255
            # ...and the game's own water surface on top of it: its sea texture, a little see-through, just above
            # Minecraft's water (which stays for swimming)
            img = Image.open(src).convert("RGBA")
            a = np.asarray(img).copy()
            a[:, :, 3] = SEA_ALPHA
            Image.fromarray(a, "RGBA").save(os.path.join(texdir, "lcu_sea.png"))
            TEX_NAMES["lcu_sea"] = "lcu_sea"
            pos = pos + shift
            c = pos[idx].mean(1)
            ok = in_boxes(c[:, [0, 2]], sea_boxes) & (np.abs(c[:, 1] - SEA_Y) < 1.5)
            if ok.any():
                pos[:, 1] = WATER_SURFACE + 0.02
                # the game colours its sea in the sea shader, the plane's vertex colours are near black:
                # neutral light (stored halved, see export_gsc) so the sea texture shows as it is
                col = np.full_like(col, SEA_LIGHT)
                sea.append(("lcu_sea#translucent", pos, uv, col, nrm, idx[ok]))
            continue
        pos = pos + shift
        pos[:, 1] = np.where(np.abs(pos[:, 1] - SEA_Y) < 1.5, WATER_SURFACE + 0.05, pos[:, 1])  # foam on the water
        c = pos[idx].mean(1)
        ok = in_boxes(c[:, [0, 2]], sea_boxes) & (np.abs(c[:, 1] - SEA_Y) < 1.5)
        if ok.any():
            sea.append((tid, pos, uv, col, nrm, idx[ok]))
    write_lcum(os.path.join(out, "meshes", "zz_sea.lcum"), batch(sea))
    print(f"sea: {len(sea)} draws, {sum(len(s[5]) for s in sea)} triangles")
    write_collision(os.path.join(out, "collision.bin"), vox)
    print("collision triangles:", sum(len(t) for t in tris), "- legacy barrier voxels:", len(vox))

    # spawn: highest solid voxel in the column nearest the centre
    near = vox[np.argmin(np.abs(vox[:, 0]) + np.abs(vox[:, 2]))]
    col = vox[(vox[:, 0] == near[0]) & (vox[:, 2] == near[2])]
    spawn = [int(near[0]), int(col[:, 1].max()) + 1, int(near[2])]

    json.dump(TEX_NAMES, open(os.path.join(out, "textures.json"), "w"), indent=0)
    lights = export_lighting(city, out)
    light = lights["noon"]
    json.dump({"scale": SCALE, "origin": origin.tolist(), "spawn": spawn, "tiles": manifest,
               "sun_direction": light["sun_direction"]}, open(os.path.join(out, "world.json"), "w"), indent=1)
    print("spawn", spawn)
    import export_trains  # the elevated railway is traced from the meshes written above
    export_trains.main(game, out)
    import export_vehicles  # drivable cars, traffic road graph, Chase's car on bay 04
    export_vehicles.main(game, out)
    if shaderpack:
        write_shader_constants(lights, os.path.join(shaderpack, "shaders", "lcu_lighting.glsl"))
        print("shader constants written to", shaderpack)



def write_shader_constants(lights, path):
    """lcu_lighting.glsl for the shader pack: one set of values per time of day (Minecraft axes, blocks).
    common.glsl blends them by worldTime (dawn 0, noon 6000, dusk 12000)."""
    v3 = lambda a: "vec3(%.5f, %.5f, %.5f)" % tuple(a)
    src = ["// written by converter/export_lcu.py from your LEGO_CITY_<TIME>_LIGHTING.LED files - do not edit"]
    for t, l in lights.items():
        T = t.upper()
        src += [f"#define LCU_SUN_DIR_{T} {v3(l['sun_direction'])}",
                f"#define LCU_SUN_COLOUR_{T} {v3(l['sun_colour'])}",
                f"#define LCU_SUN_INTENSITY_{T} {l['sun_intensity']:.5f}",
                f"#define LCU_SUN_SHADOW_{T} {l['sun_shadow_intensity']:.5f}",
                f"#define LCU_SKY_COLOUR_{T} {v3(l['sky_colour'])}",
                f"#define LCU_FOG_{T} {v3(l['fog_colour'])}",
                f"#define LCU_LOWER_FOG_{T} {v3(l['lower_fog_colour'])}",
                f"#define LCU_FOG_NEAR_{T} {l['fog_near']:.3f}",
                f"#define LCU_FOG_FAR_{T} {l['fog_far']:.3f}",
                f"#define LCU_GRADE_{T} {v3(l['grade'])}"]
    noon = lights["noon"]
    # fixed stand-in for the game's auto exposure (ideal luminance 0.375, clamped 0.5..2): the converted city's
    # sunlit surfaces average ~0.68 luminance before exposure, so 0.375 / 0.68 = 0.55
    src += [f"#define LCU_EXPOSURE {LCU_EXPOSURE:.3f}", f"#define LCU_SEA_COLOUR {v3(SEA_RGB)}"]
    src += [f"#define LCU_BLOOM_THRESHOLD {noon['bloom_threshold']:.5f}",
            f"#define LCU_BLOOM_INTENSITY {noon['bloom_intensity']:.5f}", ""]
    open(path, "w").write(chr(10).join(src))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
