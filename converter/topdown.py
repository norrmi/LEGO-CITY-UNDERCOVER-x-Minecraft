"""Top-down height preview of every Cherry Tree Hills tile (checks placement and scale)."""
import glob, os, sys
import numpy as np
from PIL import Image
import lcu_gsc as g

root = sys.argv[1]
files = sorted(f for f in glob.glob(os.path.join(root, "**", "*_DX11.GSC"), recursive=True)
               if "_DRC_" not in f and "_ULD_" not in f)
pts = []
for f in files:
    d = open(f, "rb").read()
    _, parts, _ = g.read_mesh_block(d)
    for pi, m, _, _ in g.build_scene(d):
        geo = g.part_geometry(parts[pi])
        pos = geo["position"]
        w = np.c_[pos, np.ones(len(pos))] @ m
        pts.append(w[:, :3])
    print(os.path.basename(f), "ok")
P = np.concatenate(pts)
lo, hi = P.min(0), P.max(0)
print("bounds min", lo, "max", hi, "verts", len(P))
res = 1.0
W, H = int((hi[0] - lo[0]) / res) + 1, int((hi[2] - lo[2]) / res) + 1
img = np.full((H, W), -1e9)
x = ((P[:, 0] - lo[0]) / res).astype(int); z = ((P[:, 2] - lo[2]) / res).astype(int)
np.maximum.at(img, (z, x), P[:, 1])
v = np.where(img > -1e9, (img - lo[1]) / (hi[1] - lo[1]), 0)
Image.fromarray((v * 255).astype(np.uint8)).save(sys.argv[2])
print("image", W, H)
