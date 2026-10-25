"""Places LCU's city props (lamp posts, hydrants, benches, vending machines, trees, signs...) in Cherry Tree Hills.

The chain, all from the user's own game files:
    tile *_GZ2.LED       CoreGroupInstance: 4x4 matrix + "CityPropTemplate;Builder_Street:Lampost_01_Tall"
    builder *_GZ2.LED    CityPropTemplate "Lampost_01_Tall": the group's origin inside the builder scene
    builder *_DX11.GSC   DISP specials "Lampost_01_Tall_OPTM" (finished model) or "_1".."_N" (its build pieces),
                         each pointing at its draws through a clip
World position = builder vertex x inverse(template origin) x instance matrix (row vectors, game units).

export_props() returns draws in export_gsc's format (Minecraft axes, origin 0), so export_lcu treats them like
one more tile: far filter, batching, foliage rules and collision all apply.
"""
import glob
import os
import re
import struct

import numpy as np

import export_lcu as e
import lcu_gsc as g

SKIP_PARTS = ("collision", "null", "_base", "locator", "_dummy")


def read_instances(path):
    """Tile *_GZ2.LED: [(builder, template, 4x4 matrix)] for every placed city prop."""
    d = open(path, "rb").read()
    out = []
    for m in re.finditer(rb"MOBJ\0", d):
        q = m.end()
        # u32 ? + u32 name length + "PRM\0", then f32 clip distance, u8 locked, 16 f32 matrix (little endian)
        k = d.find(b"PRM\0", q, q + 16)
        if k < 0:
            continue
        p = k + 4 + 4 + 1
        mtx = np.array(struct.unpack_from("<16f", d, p)).reshape(4, 4)
        p += 64
        n = struct.unpack_from("<I", d, p)[0]
        p += 4 + n + 2                                          # instance name, platform, layer
        n = struct.unpack_from("<I", d, p)[0]
        group = d[p + 4:p + 4 + n].rstrip(b"\0").decode("latin1")
        if not group.startswith("CityPropTemplate;") or ":" not in group:
            continue
        builder, template = group.split(";", 1)[1].split(":", 1)
        if np.allclose(mtx[:, 3], [0, 0, 0, 1], atol=1e-4):
            out.append((builder, template, mtx))
    return out


def read_templates(path):
    """Builder *_GZ2.LED: {template name: 4x4 origin matrix} of the CityPropTemplates."""
    d = open(path, "rb").read()
    out = {}
    for m in re.finditer(rb"MOBJ\0", d):
        q = m.end()
        for k in range(q, q + 12):
            n = struct.unpack_from("<I", d, k)[0]
            if 1 < n < 80 and re.fullmatch(rb"[ -~]+\0", d[k + 4:k + 4 + n]):
                name = d[k + 4:k + 3 + n].decode("latin1")
                p = k + 4 + n + 1                               # platform byte, then the matrix
                mtx = np.array(struct.unpack_from("<16f", d, p)).reshape(4, 4)
                if np.allclose(mtx[:, 3], [0, 0, 0, 1], atol=1e-4) and name != "PRM":
                    out.setdefault(name, mtx)
                break
    return out


def object_items(specials, clips, template):
    """Display items that draw a template: its finished model (_OPTM), otherwise its numbered build pieces."""
    t = template.lower()
    own = [(n, c) for n, m, c in specials if n.lower().startswith(t + "_")]
    optm = [c for n, c in own if n.lower() == t + "_optm"]
    chosen = optm or [c for n, c in own if not any(s in n.lower() for s in SKIP_PARTS)]
    return {item for c in chosen for item in clips[c][2]}


def to_game(pos):
    """export_gsc output (Minecraft axes, origin 0) -> game units."""
    p = pos / e.SCALE
    p[:, 0] = -p[:, 0]
    return p


def export_props(game, shared, texdir, tiles):
    """Draws (export_gsc format, origin 0) of every city prop placed in the given tile folders."""
    builders_dir = os.path.join(game, "LEVELS", "BUILDER")
    instances = []
    for t in tiles:
        for f in glob.glob(os.path.join(t, "*_GZ2.LED")):
            instances += read_instances(f)
    by_builder = {}
    for b, tmpl, mtx in instances:
        by_builder.setdefault(b, []).append((tmpl, mtx))
    draws, placed, missing = [], 0, set()
    for b, insts in sorted(by_builder.items()):
        folder = os.path.join(builders_dir, b.upper())
        gsc = [f for f in glob.glob(os.path.join(folder, "*_DX11.GSC")) if "_DRC_" not in f.upper()]
        gz2 = glob.glob(os.path.join(folder, "*_GZ2.LED"))
        if not gsc or not gz2:
            missing.add(b)
            continue
        data = open(gsc[0], "rb").read()
        specials, clips = g.read_specials(data)
        templates = read_templates(gz2[0])
        bdraws = e.export_gsc(gsc[0], shared, np.zeros(3), texdir, lightmap=False, tag_items=True)
        for tmpl, inst in insts:
            items = object_items(specials, clips, tmpl)
            origin = templates.get(tmpl)
            if not items or origin is None:
                missing.add(f"{b}:{tmpl}")
                continue
            xf = np.linalg.inv(origin) @ inst
            for tid, pos, uv, col, nrm, idx, item in bdraws:
                if item not in items:
                    continue
                p = np.c_[to_game(pos.copy()), np.ones(len(pos))] @ xf
                p = e.to_minecraft(p[:, :3], np.zeros(3))
                n = nrm.copy()
                n[:, 0] = -n[:, 0]
                n = n @ xf[:3, :3]
                n[:, 0] = -n[:, 0]
                n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-6)
                if np.linalg.det(xf[:3, :3]) < 0:   # mirrored instance: keep the faces pointing out
                    idx = idx[:, [0, 2, 1]]
                draws.append((tid, p, uv, col, n, idx))
            placed += 1
    print(f"props: {placed} of {len(instances)} placed from {len(by_builder)} builders"
          + (f"; not found: {sorted(missing)[:12]}" if missing else ""))
    return draws
