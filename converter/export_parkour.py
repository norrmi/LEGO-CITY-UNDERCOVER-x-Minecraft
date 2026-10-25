"""The blue-and-white free-running pieces of the districts: climbing walls ("parkour ladders"), drainpipes, bar
hops, brick grabs and ceiling climbs.

The tiles place them as obstacle gizmos (<tile>.GIZ, GizObstacle records):
    u8 len + instance name + NUL, u8 len + "GizObstacle" + NUL, u8 len + type + NUL,
    3 f32 position (game units, little endian), i16 yaw (65536 = full turn), ...
and their models are DISP specials of LEVELS/BUILDER/BUILDER_PARKOUR (one special per model, its matrix is where
the model sits in the builder scene).

export_parkour() returns draws in export_gsc's format (Minecraft axes, origin 0) plus the climbable boxes
(Minecraft axes, origin 0): the mod lets players climb inside them like on a ladder.
"""
import glob
import os
import re
import struct

import numpy as np

import export_lcu as e
import lcu_gsc as g

# obstacle type -> (BUILDER_PARKOUR model, climbable)
MODELS = {
    "WallClimb_Section_NOWALL": ("Free_Climb_Middle_NOWALL", True),
    "WallClimb_Section": ("Free_Climb_Middle_NOWALL", True),
    "WallClimb_Bottom": ("Free_Climb_Bottom", True),
    "DrainPipe_04_Ob": ("DrainPipe_04", True),
    "DrainPipe_05_Ob": ("DrainPipe_05", True),
    "DrainPipe_06_Ob": ("DrainPipe_06", True),
    "DrainPipe_Base": ("DrainPipe_Base", True),
    "BarHop_01": ("Bar_Hop_01", False),
    "BarHop_02": ("Bar_Hop_02", False),
    "BrickGrab": ("Brick_Grab", False),
    "BrickGrabTop": ("Brick_Grab", False),
    "BrickGrabBottom": ("Brick_Grab", False),
    "CeilingClimbSection": ("Ceiling_Climb_Middle_NOWALL", False),
    "CeilingClimbSection_NoWall": ("Ceiling_Climb_Middle_NOWALL", False),
}
CLIMB_PAD = 0.35  # blocks added around a climbable piece so a player touching its face counts as on it


def read_obstacles(path):
    """[(type, game position, yaw radians)] of the GizObstacle records in a .GIZ file."""
    d = open(path, "rb").read()
    out = []
    for m in re.finditer(rb"\x0cGizObstacle\x00([\x02-\x60])", d):
        p = m.end() - 1
        n = d[p]
        t = d[p + 1:p + n]
        if d[p + n] != 0 or not re.fullmatch(rb"[ -~]+", t):
            continue
        x, y, z = struct.unpack_from("<3f", d, p + n + 1)
        yaw = struct.unpack_from("<h", d, p + n + 13)[0] / 65536 * 2 * np.pi
        out.append((t.decode(), np.array([x, y, z]), yaw))
    return out


def models(game, shared, texdir):
    """{model name: draws in the model's own space (game units, row vectors)} for the models in MODELS."""
    f = os.path.join(game, "LEVELS", "BUILDER", "BUILDER_PARKOUR", "BUILDER_PARKOUR_DX11.GSC")
    data = open(f, "rb").read()
    specials, clips = g.read_specials(data)
    draws = e.export_gsc(f, shared, np.zeros(3), texdir, lightmap=False, tag_items=True)
    wanted = {m for m, _ in MODELS.values()}
    out = {}
    for name, mtx, clip in specials:
        if name not in wanted:
            continue
        items = set(clips[clip][2])
        inv = np.linalg.inv(mtx)
        local = []
        for tid, pos, uv, col, nrm, idx, item in draws:
            if item in items:
                p = np.c_[to_game(pos), np.ones(len(pos))] @ inv
                n = nrm.copy()
                n[:, 0] = -n[:, 0]
                local.append((tid, p[:, :3], uv, col, n @ inv[:3, :3], idx))
        out[name] = local
    return out


def to_game(pos):
    p = pos / e.SCALE
    p[:, 0] = -p[:, 0]
    return p


def export_parkour(game, shared, texdir, tiles):
    """(draws, climb boxes) for every parkour piece placed in the given tile folders."""
    found = []
    for t in tiles:
        for f in glob.glob(os.path.join(t, "*.GIZ")):
            found += [o for o in read_obstacles(f) if o[0] in MODELS]
    lib = models(game, shared, texdir)
    draws, climbs, missing = [], [], set()
    for typ, at, yaw in found:
        model, climbable = MODELS[typ]
        if not lib.get(model):
            missing.add(model)
            continue
        c, s = np.cos(yaw), np.sin(yaw)
        rot = np.array([[c, 0, -s], [0, 1, 0], [s, 0, c]])  # row vectors: yaw about the game's Y axis
        lo, hi = np.full(3, np.inf), np.full(3, -np.inf)
        for tid, p, uv, col, n, idx in lib[model]:
            w = p @ rot + at
            mc = w * e.SCALE
            mc[:, 0] = -mc[:, 0]
            nn = n @ rot
            nn[:, 0] = -nn[:, 0]
            nn /= np.maximum(np.linalg.norm(nn, axis=1, keepdims=True), 1e-6)
            draws.append((tid, mc, uv, col, nn, idx[:, [0, 2, 1]] if np.linalg.det(rot) < 0 else idx))
            lo, hi = np.minimum(lo, mc.min(0)), np.maximum(hi, mc.max(0))
        if climbable and np.isfinite(lo).all():
            climbs.append((lo - CLIMB_PAD, hi + CLIMB_PAD))
    print(f"parkour: {len(found)} pieces, {len(climbs)} climbable" + (f"; no model: {sorted(missing)}" if missing else ""))
    return draws, climbs
