"""Top-down colour preview of exported .lcum meshes (texture average colour x vertex light), Minecraft axes."""
import glob, os, struct, sys
import numpy as np
from PIL import Image

out_dir = sys.argv[1]
avg = {}
def tex_avg(name):
    if name not in avg:
        im = Image.open(os.path.join(out_dir, "textures", name + ".png")).convert("RGB").resize((1, 1), Image.BOX)
        avg[name] = np.array(im.getpixel((0, 0)), float) / 255
    return avg[name]

P, C = [], []
for f in glob.glob(os.path.join(out_dir, "meshes", "*.lcum")):
    d = open(f, "rb").read(); n = struct.unpack_from("<I", d, 8)[0]; p = 12
    for _ in range(n):
        ln = struct.unpack_from("<H", d, p)[0]; name = d[p + 2:p + 2 + ln].decode(); p += 2 + ln
        flags, nv, ni = struct.unpack_from("<III", d, p); p += 12
        v = np.frombuffer(d, dtype=[("p", "<f4", 3), ("u", "<f4", 2), ("c", "u1", 4), ("n", "i1", 4)], count=nv, offset=p)
        p += nv * 28
        idx = np.frombuffer(d, "<u4", ni, p).reshape(-1, 3); p += ni * 4
        tri = v["p"][idx]
        # splat triangle samples
        w = np.random.dirichlet([1, 1, 1], size=(len(idx), 6))
        pts = np.einsum("tkj,tjc->tkc", w, tri).reshape(-1, 3)
        col = (v["c"][idx][:, :, :3].mean(1) / 255)[:, None, :].repeat(6, 1).reshape(-1, 3) * tex_avg(name)
        P.append(pts); C.append(col)
P = np.concatenate(P); C = np.concatenate(C)
lo, hi = P.min(0), P.max(0)
print("MC bounds", lo.round(), hi.round())
res = 1.0
W, H = int((hi[0] - lo[0]) / res) + 1, int((hi[2] - lo[2]) / res) + 1
x = ((P[:, 0] - lo[0]) / res).astype(int); z = ((P[:, 2] - lo[2]) / res).astype(int)
order = np.argsort(P[:, 1])  # higher wins
img = np.zeros((H, W, 3))
img[z[order], x[order]] = C[order]
Image.fromarray((np.clip(img, 0, 1) ** (1 / 1.0) * 255).astype(np.uint8)).save(sys.argv[2])
print("image", W, H)
