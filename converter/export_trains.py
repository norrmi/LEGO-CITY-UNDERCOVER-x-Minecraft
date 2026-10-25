"""The elevated railway of Cherry Tree Hills: the track's path (traced from the game's own rail meshes) and a
train (cars from CHARS/VEHICLES/TRAINS, the same ones the game's AI uses: AITYPES.TXT "cydonia",
"cydoniacarriage", "cydoniarear").

    python export_trains.py "<LEGO City Undercover folder>" <converter output folder>

The game moves its trains along the line at runtime; the line's path is not stored as a spline, so it is traced
from the rails: step along the rail vertices (only ahead) and re-centre on them. The line is a single track. Writes <out>/trains/<car>.lcum (car-local, Minecraft axes, front = +X) and
<out>/trains.json (both tracks as point lists, the train's car order and lengths).
"""
import glob
import json
import os
import struct
import sys

import numpy as np

import export_lcu as e

RAIL_TEXTURES = ("mt_rail_track", "rail_track")
TRAIN = [("cab", "TRAINS_CYDONIA_CAB1"), ("carriage", "TRAINS_CYDONIA_CARRIAGE"),
         ("carriage", "TRAINS_CYDONIA_CARRIAGE"), ("rear", "TRAINS_CYDONIA_CAB2")]
STEP = 1.0  # blocks between path points
CTH_MIN_X = -700.0


def rail_points(out):
    """Rail vertices (Minecraft coordinates) from the converted meshes."""
    names = json.load(open(os.path.join(out, "textures.json")))
    pts = []
    for f in glob.glob(os.path.join(out, "meshes", "*.lcum")):
        d = open(f, "rb").read()
        p = 12
        for _ in range(struct.unpack_from("<I", d, 8)[0]):
            n = struct.unpack_from("<H", d, p)[0]
            tid = d[p + 2:p + 2 + n].decode()
            p += 2 + n
            fl, nv, ni = struct.unpack_from("<III", d, p)
            p += 12
            if names.get(tid.split("+")[0].split("#")[0], "") in RAIL_TEXTURES:
                pts.append(np.frombuffer(d, "<f4", nv * 7, p).reshape(nv, 7)[:, :3].copy())
            p += nv * 28 + ni * 4
    P = np.concatenate(pts)
    return P[P[:, 0] > CTH_MIN_X]  # Cherry Tree Hills' line only (Little Italy lies ~1200 blocks west)


def trace(P):
    """Centre line of the double track: from the western end, step 3 blocks ahead, re-centre on the rails there."""
    start = P[P[:, 0] < P[:, 0].min() + 3].mean(0)
    cur, d = start, np.array([1.0, 0.0, 0.0])
    path = [cur]
    for _ in range(4000):
        probe = cur + d * 3
        rel = (P - cur)[:, [0, 2]]
        near = P[(np.linalg.norm((P - probe)[:, [0, 2]], axis=1) < 4.5) & (rel @ d[[0, 2]] > 1.0)]  # ahead only
        if len(near) < 4:
            break
        nxt = near.mean(0)
        nd = nxt - cur
        nd[1] = 0
        if np.linalg.norm(nd) < 0.5:
            break
        d = 0.5 * d + 0.5 * nd / np.linalg.norm(nd)
        d /= np.linalg.norm(d)
        cur = cur + d * 3
        cur[1] = nxt[1]
        path.append(cur.copy())
    path = np.array(path)
    # smooth and resample every STEP blocks
    k = np.ones(5) / 5
    sm = np.c_[[np.convolve(np.pad(path[:, i], 2, mode="edge"), k, "valid") for i in range(3)]].T
    seg = np.r_[0, np.cumsum(np.linalg.norm(np.diff(sm, axis=0), axis=1))]
    s = np.arange(0, seg[-1], STEP)
    return np.c_[[np.interp(s, seg, sm[:, i]) for i in range(3)]].T


def track_offset(P, path):
    """Half the distance between the two tracks: rail vertices projected across the line, split at the centre."""
    side = []
    for i in range(5, len(path) - 5, 10):
        t = path[i + 1] - path[i - 1]
        t[1] = 0
        t /= np.linalg.norm(t)
        n = np.array([-t[2], 0, t[0]])
        near = P[np.linalg.norm((P - path[i])[:, [0, 2]], axis=1) < 3]
        side += list((near - path[i]) @ n)
    side = np.array(side)
    left, right = side[side < 0], side[side > 0]
    return float((right.mean() - left.mean()) / 2) if len(left) and len(right) else 1.5


def export_car(game, shared, texdir, name, out_file):
    """One train car: its GHG as .lcum, centred on its wheels' footprint, front along +X. Returns its length."""
    f = glob.glob(os.path.join(game, "CHARS", "VEHICLES", "TRAINS", "**", name + "_DX11.GHG"), recursive=True)[0]
    draws = e.export_gsc(f, shared, np.zeros(3), texdir, lightmap=False)
    P = np.concatenate([d[1] for d in draws])
    lo, hi = P.min(0), P.max(0)
    # cars are modelled along the game's Z axis; put the long axis on +X
    long_x = (hi[0] - lo[0]) > (hi[2] - lo[2])
    centre = np.array([(lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2])
    out = []
    for tid, pos, uv, col, nrm, idx in draws:
        p, n = pos - centre, nrm.copy()
        if not long_x:  # rotate +Z onto +X: (x, z) -> (z, -x)
            p = np.c_[p[:, 2], p[:, 1], -p[:, 0]]
            n = np.c_[n[:, 2], n[:, 1], -n[:, 0]]
        out.append((tid, p, uv, col, n, idx))
    e.write_lcum(out_file, e.batch(out))
    return float(max(hi[0] - lo[0], hi[2] - lo[2]))


def main(game, out):
    city = os.path.join(game, e.CITY)
    shared = e.SharedTextures(city)
    P = rail_points(out)
    path = trace(P)
    # a single track (the measured half-spacing is the rail gauge, ~1.4 blocks): the train runs on the centre line
    # and shuttles end to end (it has a driver's cab at both ends)
    off = track_offset(P, path)
    tracks = [path]
    os.makedirs(os.path.join(out, "trains"), exist_ok=True)
    cars, done = [], {}
    for role, name in TRAIN:
        if name not in done:
            done[name] = export_car(game, shared, os.path.join(out, "textures"), name,
                                    os.path.join(out, "trains", name.lower() + ".lcum"))
        cars.append({"model": name.lower(), "role": role, "length": round(done[name], 3)})
    json.dump({"tracks": [[[round(float(x), 3) for x in p] for p in tr] for tr in tracks],
               "cars": cars, "speed": 9.0, "gap": 0.6},
              open(os.path.join(out, "trains.json"), "w"))
    print(f"railway: {len(path)} points ({len(path) * STEP:.0f} blocks), rail half-gauge {off:.2f}; "
          f"train: {[c['model'] for c in cars]} lengths {[c['length'] for c in cars]}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
