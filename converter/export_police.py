"""Interiors you walk into from the districts: the police station (LEVELS/STANDALONES/POLICE_STATION, floor by
floor) and the Ice Cream Parlour (LEVELS/STANDALONES/ICECREAM_PARLOUR/ICECREAM01_PARLOUR).

The game loads each of them as its own level; here every room gets its own place in the world and the mod moves
players there (interiors.json): stepping on a site's entry point leads to its first room, and every room's lift
spot opens a menu with the site's other rooms and the way out.

- police station: far from the districts (X ~3000, Y ~200), one floor every ROOM_STEP blocks; the lift spots come
  from the floors' lift doors (Lift_Door_* specials of *_TECH_DX11.GSC), the line-up room (no lift) uses its middle
- ice cream parlour: right under Little Italy, below the sea floor; the mod clears the stone there ("carve")

A spot stands on the floor below it (a downward ray through the room) and faces the room.
"""
import glob
import os

import numpy as np

import export_lcu as e
import lcu_gsc as g

SITES = [
    {"id": "police", "name": "Police Station", "base": ("STANDALONES", "POLICE_STATION"),
     "entry": [85.5, 77.0, -151.5],          # Cherry Tree Hills door (asked for: 85 77 -152)
     "exit": [85.5, 78.0, -148.0, 0.0],      # the street there is at Y 77.4: arrive just above it
     "origin": [3000.0, 200.0, 3000.0], "step": [0.0, 0.0, 600.0],  # floors with window scenery: ~330 blocks
     "floors": [("lobby", "Lobby", "POLICE_STATION_LOBBY"),
                ("briefing", "Briefing Room", "POLICE_STATION_BRIEFINGROOM"),
                ("lineup", "Line-up Room", "POLICE_STATION_LINE_UP_ROOM"),
                ("garage", "Garage", "POLICE_STATION_GARAGE")]},
    {"id": "icecream", "name": "Ice Cream Parlour", "base": ("STANDALONES", "ICECREAM_PARLOUR"),
     "entry": [-1714.5, 74.0, 545.5],        # Little Italy (asked for: -1715 74 545)
     "exit": [-1714.5, 75.0, 548.5, 0.0],
     "under": [-1714.5, 545.5],              # right under the entry, below the sea floor (stone up to Y 58)
     "floors": [("parlour", "Ice Cream Parlour", "ICECREAM01_PARLOUR")]},
]
INTO_ROOM = 1.6     # blocks from the lift doors towards the room
ROOM_MARGIN = 8.0   # blocks kept around the bulk of a room's triangles
# interiors are lit like the game: their baked light dimmed, plus the room's lamps (<room>_LIGHTING.LED
# DynamicLight + DynamicLightDesc: neon, spots, candles) baked into the vertex colours
BASE_LIGHT = np.array([0.29, 0.24, 0.19])  # the baked light that stays: dim and warm, as between the game's lamps
LAMP_LIGHT = 0.013   # vertex colour per unit of lamp intensity (colours are stored halved, see export_gsc)
LAMP_REACH = 1.8    # the rooms' walls and floors are big triangles with few vertices: lamps light them as soft,
                    # wide points (no spot cone) so their warmth reaches the vertices around them
GLOWING = ("neon", "sign", "glow", "light", "lamp", "candle", "flame", "screen", "menu")  # stay bright
UNDER_TOP = 56.0    # an underground room's core ends here; Y 57 stays air, Y 58 stone keeps the sea out
CARVE_MARGIN = 3.0


def lift_doors(folder):
    """Game position of the lift doors of a floor, or None."""
    for f in glob.glob(os.path.join(folder, "*TECH_DX11.GSC")):
        specials, _ = g.read_specials(open(f, "rb").read())
        pts = [m[3, :3] for n, m, c in specials if n.startswith("Lift_Door")]
        if pts:
            return np.mean(pts, axis=0)
    return None


def floor_below(tris, x, z, y_from):
    """Height where a straight drop from (x, y_from, z) first hits the geometry (Minecraft coordinates), or None:
    the surface a player put there lands on."""
    t = tris[(tris[:, :, 0].min(1) <= x) & (tris[:, :, 0].max(1) >= x)
             & (tris[:, :, 2].min(1) <= z) & (tris[:, :, 2].max(1) >= z)]
    best = None
    for a, b, c in t:
        d = (b[2] - c[2]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[2] - c[2])
        if abs(d) < 1e-9:
            continue
        w1 = ((b[2] - c[2]) * (x - c[0]) + (c[0] - b[0]) * (z - c[2])) / d
        w2 = ((c[2] - a[2]) * (x - c[0]) + (a[0] - c[0]) * (z - c[2])) / d
        w3 = 1 - w1 - w2
        if min(w1, w2, w3) < 0:
            continue
        y = w1 * a[1] + w2 * b[1] + w3 * c[1]
        if y <= y_from and (best is None or y > best):
            best = y
    return None if best is None else float(best)


def lamps(folder):
    """[(game position, colour * intensity, falloff start, falloff range, spot direction or None, cos inner, cos outer)]."""
    import led
    f = os.path.join(folder, os.path.basename(folder) + "_LIGHTING.LED")
    if not os.path.exists(f):
        return []
    objs = led.read_objects(open(f, "rb").read())
    descs = {n: fl for n, (c, fl) in objs.items() if c == "DynamicLightDesc"}
    out = []
    for name, (cls, fl) in objs.items():
        if cls != "DynamicLight" or not fl.get("Instance Enabled", 1):
            continue
        d = descs.get(fl.get("LightName"))
        if not d or not d.get("Enabled", 1) or d.get("Type") not in (1, 2) or d.get("Negative Light (hidden)"):
            continue
        m = np.array(fl["Mtx"]).reshape(4, 4)
        colour = np.array(d["Base Color"]) * d["Intensity"]
        spot = None
        if d["Type"] == 2:
            axis = m[2, :3]
            spot = -axis / max(np.linalg.norm(axis), 1e-6)  # spots shine down their -Z
        out.append((m[3, :3], colour, d["Light Falloff Start"], max(d["Light Falloff Range"], 1e-3), spot,
                    np.cos(np.radians(d.get("Spot Cone Inner FOV", 45) / 2)), np.cos(np.radians(d.get("Spot FOV", 50) / 2))))
    return out


def light_room(draws, lights, offset=np.zeros(3)):
    """Dims the baked light and adds the lamps (draws in Minecraft coordinates, the room's game origin at offset)."""
    if not lights:
        return draws
    out = []
    for tid, pos, uv, col, nrm, idx in draws:
        g = (pos - offset) / e.SCALE
        g[:, 0] = -g[:, 0]
        n = nrm.copy()
        n[:, 0] = -n[:, 0]
        add = np.zeros((len(pos), 3))
        for at, colour, start, rng, spot, cin, cout in lights:
            v = at - g
            d = np.linalg.norm(v, axis=1)
            l = v / np.maximum(d, 1e-6)[:, None]
            fall = np.clip(1 - (d - start * LAMP_REACH) / (rng * LAMP_REACH), 0, 1) ** 2
            ndl = np.clip((l * n).sum(1), 0, 1) if np.any(n) else 1.0
            add += (fall * (0.4 + 0.6 * ndl))[:, None] * colour[None, :]
        c = col.copy()
        if any(w in e.TEX_NAMES.get(tid.partition("#")[0].split("+")[0], "") for w in GLOWING):
            out.append((tid, pos, uv, col, nrm, idx))  # signs, neon and lamps glow as they are
            continue
        c[:, :3] = np.clip(col[:, :3] * BASE_LIGHT + add * LAMP_LIGHT, 0, 1)
        out.append((tid, pos, uv, c, nrm, idx))
    return out


def room(gsc, shared, texdir):
    """A room's draws around its game origin (Minecraft axes), without the far-off cutscene sets, and the core box
    (5th..95th percentile of its triangles: the room without the scenery behind its windows)."""
    raw = e.export_gsc(gsc, shared, np.zeros(3), texdir)
    cen = np.concatenate([d[1][d[5]].mean(1) for d in raw])
    lo, hi = np.percentile(cen, 2, axis=0) - ROOM_MARGIN, np.percentile(cen, 98, axis=0) + ROOM_MARGIN
    draws = []
    for tid, pos, uv, col, nrm, idx in raw:
        c = pos[idx].mean(1)
        idx = idx[np.all((c >= lo) & (c <= hi), axis=1)]
        if len(idx):
            draws.append((tid, pos, uv, col, nrm, idx))
    cen = np.concatenate([d[1][d[5]].mean(1) for d in draws])
    return draws, (np.percentile(cen, 5, axis=0), np.percentile(cen, 95, axis=0))


def export_interiors(game, shared, texdir, gizlib=None):
    """[(site dict for interiors.json, [(room id, draws placed in the world)])]."""
    import export_gizprops
    out = []
    for site in SITES:
        base = os.path.join(game, "LEVELS", *site["base"])
        floors, rooms, carve = [], [], []
        for i, (fid, label, folder) in enumerate(site["floors"]):
            folder = os.path.join(base, folder)
            draws, (core_lo, core_hi) = room(os.path.join(folder, os.path.basename(folder) + "_DX11.GSC"), shared, texdir)
            room_lamps = lamps(folder)
            draws = light_room(draws, room_lamps)
            if "under" in site:
                offset = np.array([site["under"][0], UNDER_TOP - core_hi[1], site["under"][1]])
            else:
                offset = np.array(site["origin"]) + i * np.array(site["step"])
            draws = [(tid, pos + offset, uv, col, nrm, idx) for tid, pos, uv, col, nrm, idx in draws]
            tris = np.concatenate([d[1][d[5]] for d in draws])  # the room itself: spots stand on its floor
            if gizlib is not None:  # the room's LEGO objects (chairs, desks, barriers, machines...)
                draws += light_room(export_gizprops.export_gizprops(gizlib, [folder], offset), room_lamps, offset)
            lo, hi = core_lo + offset, core_hi + offset
            centre = (lo + hi) / 2
            inside = offset.copy()   # the room is built around its game origin
            inside[1] = centre[1]
            doors = lift_doors(folder)
            if doors is not None:
                spot = doors * e.SCALE
                spot[0] = -spot[0]
                spot = spot + offset
                d = inside - spot
                d[1] = 0
                spot = spot + d / max(np.linalg.norm(d), 1e-6) * INTO_ROOM
            else:
                spot = centre.copy()
            y = floor_below(tris, spot[0], spot[2], spot[1] + 1.0 if doors is not None else centre[1] + 2.0)
            spot[1] = (y if y is not None else lo[1]) + 0.05
            look = inside - spot
            if np.hypot(look[0], look[2]) < 0.5:
                look = centre - spot
            yaw = float(np.degrees(np.arctan2(-look[0], look[2])))  # Minecraft yaw facing the room
            floors.append({"id": fid, "name": label, "lift": [round(float(v), 2) for v in spot] + [round(yaw, 1)]})
            rooms.append((fid, draws))
            if "under" in site:
                c_lo, c_hi = lo - CARVE_MARGIN, hi + CARVE_MARGIN
                c_hi[1] = min(c_hi[1], UNDER_TOP + 1)
                carve.append([int(np.floor(v)) for v in c_lo] + [int(np.ceil(v)) for v in c_hi])
            print(f"{site['id']} {fid}: {sum(len(d[5]) for d in draws)} triangles, {len(room_lamps)} lamps, spot {floors[-1]['lift']}"
                  + ("" if doors is not None else " (no lift: room centre)"))
        out.append(({"id": site["id"], "name": site["name"], "entry": site["entry"], "exit": site["exit"],
                     "floors": floors, "carve": carve}, rooms))
    return out
