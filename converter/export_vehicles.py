"""Cars: models you can drive and Chase McCain's car at the police station.

    python export_vehicles.py "<LEGO City Undercover folder>" <converter output folder>

- models: CHARS/VEHICLES/**/<NAME>_DX11.GHG, written like the train cars (export_trains.export_car): centred, floor
  at y 0, front along +X -> vehicles/<name>.lcum
- more models for /lcu car: the cars AITYPES.TXT lists for Cherry Tree Hills (Vehicle.Residential)
- parked: Chase's Narym (MUSCLECARS_NARYM) on bay 04 next to the police station: the "04" of car_park_numbers
  (lower right quarter of the texture) with the white concrete stairs beside it
Writes <out>/vehicles.json.
"""
import json
import os
import re
import struct
import sys

import numpy as np

import export_lcu as e
import export_trains

# more cars for /lcu car: the ones AITYPES.TXT lists for Cherry Tree Hills (Vehicle.Residential)
TRAFFIC = ["SALOONCARS_ALOM", "SALOONCARS_ANCESTOR", "SALOONCARS_ARBALEST", "SALOONCARS_CABRAKAN",
           "SALOONCARS_GALATEA", "SALOONCARS_HESTIA", "SALOONCARS_INDULGA", "SALOONCARS_JALOPY"]
CHASE = "MUSCLECARS_NARYM"
BAY_04 = np.array([63.5, 77.4, -154.3])   # centre of the "04" decal (Minecraft), found from car_park_numbers


def car_file(game, name):
    for root, _, files in os.walk(os.path.join(game, "CHARS", "VEHICLES")):
        for f in files:
            if f.upper() == name + "_DX11.GHG":
                return os.path.join(root, f)
    return None


def export_model(game, shared, texdir, name, out):
    """vehicles/<name>.lcum and its size (length along +X, width, height), or None."""
    f = car_file(game, name)
    if f is None:
        print(f"  vehicle {name}: no model")
        return None
    draws = e.export_gsc(f, shared, np.zeros(3), texdir, lightmap=False)
    if not draws:
        return None
    P = np.concatenate([d[1] for d in draws])
    lo, hi = P.min(0), P.max(0)
    long_x = (hi[0] - lo[0]) > (hi[2] - lo[2])
    centre = np.array([(lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2])
    placed = []
    for tid, pos, uv, col, nrm, idx in draws:
        p, n = pos - centre, nrm.copy()
        if not long_x:  # +Z (the game's forward) onto +X
            p = np.c_[p[:, 2], p[:, 1], -p[:, 0]]
            n = np.c_[n[:, 2], n[:, 1], -n[:, 0]]
        placed.append((tid, p, uv, col, n, idx))
    os.makedirs(os.path.join(out, "vehicles"), exist_ok=True)
    e.write_lcum(os.path.join(out, "vehicles", name.lower() + ".lcum"), e.batch(placed))
    size = hi - lo
    length, width = (size[0], size[2]) if long_x else (size[2], size[0])
    return [round(float(length), 3), round(float(width), 3), round(float(size[1]), 3)]


def bay_yaw(out):
    """Minecraft yaw along bay 04: the direction the digits' "up" points (from the decal's UVs)."""
    import glob
    names = json.load(open(os.path.join(out, "textures.json")))
    for f in glob.glob(os.path.join(out, "meshes", "sf_residential_*.lcum")):
        d = open(f, "rb").read()
        p = 12
        for _ in range(struct.unpack_from("<I", d, 8)[0]):
            n = struct.unpack_from("<H", d, p)[0]
            tid = d[p + 2:p + 2 + n].decode()
            p += 2 + n
            fl, nv, ni = struct.unpack_from("<III", d, p)
            p += 12
            v = np.frombuffer(d, "<f4", nv * 7, p).reshape(nv, 7)
            idx = np.frombuffer(d, "<u4", ni, p + nv * 28).reshape(-1, 3)
            p += nv * 28 + ni * 4
            if names.get(tid.split("#")[0].split("+")[0]) != "car_park_numbers":
                continue
            for t in idx:
                tri = v[t]
                if np.linalg.norm((tri[:, :3].mean(0) - BAY_04)[[0, 2]]) > 2:
                    continue
                # world-space direction in which the texture's v decreases (image "up") on this triangle
                e1, e2 = tri[1, [0, 2]] - tri[0, [0, 2]], tri[2, [0, 2]] - tri[0, [0, 2]]
                d1, d2 = tri[1, 3:5] - tri[0, 3:5], tri[2, 3:5] - tri[0, 3:5]
                m = np.array([d1, d2])
                if abs(np.linalg.det(m)) < 1e-9:
                    continue
                grad = np.linalg.solve(m, np.array([e1, e2]))  # rows: d(world xz)/du, d(world xz)/dv
                up = -grad[1]
                return float(np.degrees(np.arctan2(-up[0], up[1])))
    return 0.0


def main(game, out):
    shared = e.SharedTextures(os.path.join(game, e.CITY))
    texdir = os.path.join(out, "textures")
    e.TEX_NAMES.update(json.load(open(os.path.join(out, "textures.json"))))
    models = {}
    for name in [CHASE] + TRAFFIC:
        size = export_model(game, shared, texdir, name, out)
        if size:
            models[name.lower()] = {"size": size}
    json.dump(e.TEX_NAMES, open(os.path.join(out, "textures.json"), "w"), indent=0)
    yaw = bay_yaw(out)
    data = {"models": models,
            "parked": [{"model": CHASE.lower(), "pos": [float(x) for x in BAY_04], "yaw": round(yaw, 1),
                        "name": "Chase McCain's Narym"}] if CHASE.lower() in models else []}
    json.dump(data, open(os.path.join(out, "vehicles.json"), "w"))
    print(f"vehicles: {len(models)} models, Narym on bay 04 facing yaw {yaw:.0f}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
