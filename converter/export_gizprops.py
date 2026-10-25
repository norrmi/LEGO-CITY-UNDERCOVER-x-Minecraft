"""LEGO objects placed as gizmos: chairs, desks, barriers, coffee machines, tables, tennis nets, catapults...

Besides the CityPropTemplate props (export_props.py) the game places many objects as gizmos in a level's .GIZ:
    u8 len + instance name + NUL, u8 len + "GizObstacle" or "GIZSIMPLEPROPOBJECT" + NUL, u8 len + type + NUL,
    3 f32 position (game units, little endian), i16 yaw (65536 = full turn), ...
The type names a model in one of the builders the level loads (its LEGOSETS.TXT: Level "Builder_..."): a DISP
special called like the type without its _Ob suffix, its finished _OPTM version, or its numbered build pieces.
The model sits in the builder scene at its special's matrix; placing it is inverse(that matrix), then the
gizmo's yaw and position.
"""
import glob
import os
import re
import struct

import numpy as np

import export_lcu as e
import export_parkour
import lcu_gsc as g

KINDS = (b"GizObstacle", b"GIZSIMPLEPROPOBJECT")
SKIP = re.compile(r"(?i)door|lift|trigger|null|collision|camera|marker|minicut|mcut|token|stud|vfx|anim|dummy|"
                  r"blowup|target|spawn|light_on|switch")


def read_gizmos(path):
    """[(type, game position, yaw radians)] of the GizObstacle and simple-prop records of a .GIZ file."""
    d = open(path, "rb").read()
    out = []
    for kind in KINDS:
        for m in re.finditer(bytes([len(kind) + 1]) + re.escape(kind) + rb"\x00([\x02-\x60])", d):
            p = m.end() - 1
            n = d[p]
            t = d[p + 1:p + n]
            if d[p + n] != 0 or not re.fullmatch(rb"[ -~]+", t):
                continue
            x, y, z = struct.unpack_from("<3f", d, p + n + 1)
            if not all(np.isfinite([x, y, z])) or max(abs(x), abs(y), abs(z)) > 1e5:
                continue
            yaw = struct.unpack_from("<h", d, p + n + 13)[0] / 65536 * 2 * np.pi
            out.append((t.decode(), np.array([x, y, z]), yaw))
    return out


def builders_of(folder):
    """Builder names from the LEGOSETS.TXT of a level folder and its parent."""
    names = []
    for f in (os.path.join(folder, "LEGOSETS.TXT"), os.path.join(os.path.dirname(folder), "LEGOSETS.TXT")):
        if os.path.exists(f):
            names += re.findall(r'(?i)^\s*Level\s+"(Builder_[^"]+)"', open(f, encoding="latin1").read(), re.M)
    return list(dict.fromkeys(n.upper() for n in names))


class Library:
    """Builder models by name, loaded on first use."""

    def __init__(self, game, shared, texdir):
        self.game, self.shared, self.texdir = game, shared, texdir
        self.builders = {}
        self.index = None  # special name (lower case) -> builder, over every builder (fallback)

    def builder(self, name):
        if name not in self.builders:
            folder = os.path.join(self.game, "LEVELS", "BUILDER", name)
            gsc = [f for f in glob.glob(os.path.join(folder, "*_DX11.GSC"))
                   if not any(x in os.path.basename(f).upper() for x in e.EXCLUDE)]
            if not gsc:
                self.builders[name] = None
            else:
                try:
                    data = open(gsc[0], "rb").read()
                    specials, clips = g.read_specials(data)
                    draws = e.export_gsc(gsc[0], self.shared, np.zeros(3), self.texdir, lightmap=False, tag_items=True)
                    self.builders[name] = (specials, clips, draws)
                except Exception as ex:  # a few builders use scene layouts the reader does not know
                    print(f"  builder {name} skipped: {ex}")
                    self.builders[name] = None
        return self.builders[name]

    def names(self, typ):
        """Model names to look for: the type without its _Ob / _SIMP / _CMPLX / _OPTM endings."""
        base = typ
        while True:
            b = re.sub(r"(?i)_(ob|simp|cmplx|optm|anim)$", "", base)
            if b == base:
                return base
            base = b

    @staticmethod
    def variants(typ, base):
        """Names a gizmo type's model may have: IceMachine -> Ice_Machine; editor exports also double names
        (Parlour_Statue -> Parlour_Statue_Parlour_Statue)."""
        split = re.sub(r"(?<=[a-z])(?=[A-Z])", "_", base)
        bare = re.sub(r"_[0-9]+$", "", base)  # Piano_Seat_01 -> Piano_Seat
        return list(dict.fromkeys((base + "_optm", base, typ, split, split + "_optm", base + "_" + base,
                                   split + "_" + split, bare, bare + "_optm")))

    def builders_with(self, base):
        """Builders that have a special called base, base_OPTM or base_<n> (index of every builder's specials)."""
        if self.index is None:
            self.index = {}
            for f in glob.glob(os.path.join(self.game, "LEVELS", "BUILDER", "*", "*_DX11.GSC")):
                if any(x in os.path.basename(f).upper() for x in e.EXCLUDE):
                    continue
                try:
                    specials, _ = g.read_specials(open(f, "rb").read())
                except Exception:
                    continue
                b = os.path.basename(os.path.dirname(f))
                for n, m, c in specials:
                    self.index.setdefault(re.sub(r"_(optm|\d+)$", "", n.lower()), []).append(b)
            print(f"  builder index: {len(self.index)} model names")
        return list(dict.fromkeys(b for v in self.variants(base, base) for b in self.index.get(v.lower(), [])))

    def model(self, typ, builders):
        """The model's draws in its own space (game units), or None. The level's builders first, then any."""
        base = self.names(typ)
        return self._model(typ, base, builders) or self._model(typ, base, self.builders_with(base))

    def _model(self, typ, base, builders):
        for b in builders:
            lib = self.builder(b)
            if lib is None:
                continue
            specials, clips, draws = lib
            by_name = {n.lower(): (m, c) for n, m, c in specials}
            pick = None
            for cand in self.variants(typ, base):  # whole model first (finished _OPTM, plain name)
                if cand.lower() in by_name:
                    m, c = by_name[cand.lower()]
                    pick = (m, set(clips[c][2]))
                    break
            for cand in ([] if pick else self.variants(typ, base)):  # then its numbered build pieces
                pieces = [(m, c) for n, m, c in specials if re.fullmatch(re.escape(cand.lower()) + r"_\d+", n.lower())]
                if pieces:
                    anchor = np.eye(4)
                    anchor[3, :3] = np.mean([m[3, :3] for m, c in pieces], axis=0)
                    anchor[3, 1] = min(m[3, 1] for m, c in pieces)
                    pick = (anchor, {i for m, c in pieces for i in clips[c][2]})
                    break
            if pick is None:
                continue
            anchor, items = pick
            inv = np.linalg.inv(anchor)
            local = []
            for tid, pos, uv, col, nrm, idx, item in draws:
                if item in items:
                    p = np.c_[export_parkour.to_game(pos.copy()), np.ones(len(pos))] @ inv
                    n = nrm.copy()
                    n[:, 0] = -n[:, 0]
                    local.append((tid, p[:, :3], uv, col, n @ inv[:3, :3], idx))
            if local:
                return local
        return None


def place(model, at, yaw, offset=np.zeros(3)):
    """A model's draws at a gizmo (game position and yaw) in Minecraft coordinates (+ offset)."""
    c, s = np.cos(yaw), np.sin(yaw)
    rot = np.array([[c, 0, -s], [0, 1, 0], [s, 0, c]])
    out = []
    for tid, p, uv, col, n, idx in model:
        mc = (p @ rot + at) * e.SCALE
        mc[:, 0] = -mc[:, 0]
        nn = n @ rot
        nn[:, 0] = -nn[:, 0]
        nn /= np.maximum(np.linalg.norm(nn, axis=1, keepdims=True), 1e-6)
        out.append((tid, mc + offset, uv, col, nn, idx))
    return out


def export_gizprops(lib, folders, offset=np.zeros(3)):
    """Draws of every gizmo-placed LEGO object in the given level folders (Minecraft coordinates + offset)."""
    draws, placed, missing = [], 0, set()
    for folder in folders:
        builders = builders_of(folder)
        for f in glob.glob(os.path.join(folder, "*.GIZ")):
            for typ, at, yaw in read_gizmos(f):
                if typ in export_parkour.MODELS or SKIP.search(typ):
                    continue
                model = lib.model(typ, builders)
                if model is None:
                    missing.add(typ)
                    continue
                draws += place(model, at, yaw, offset)
                placed += 1
    print(f"gizmo objects: {placed} placed" + (f"; no model for {len(missing)} types, e.g. {sorted(missing)[:10]}"
                                                if missing else ""))
    return draws
