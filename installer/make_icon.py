"""Draws the project icon (a blue toy brick with "LCU" on it) for the mod and the installer.

    python make_icon.py

Writes mod/src/main/resources/assets/lcucraft/icon.png and installer/icon.ico. Drawn from scratch, nothing from
the game.
"""
import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SIZE = 256
BLUE, DARK, LIGHT = (24, 86, 196), (14, 52, 128), (88, 146, 236)


def draw(size=SIZE):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = size / 256
    # studs on top of the brick
    for cx in (72, 128, 184):
        x0, x1 = (cx - 22) * s, (cx + 22) * s
        d.rounded_rectangle((x0, 38 * s, x1, 80 * s), radius=6 * s, fill=DARK)
        d.ellipse((x0, 26 * s, x1, 50 * s), fill=LIGHT)
    # brick body with a darker lower edge
    d.rounded_rectangle((20 * s, 64 * s, 236 * s, 228 * s), radius=18 * s, fill=DARK)
    d.rounded_rectangle((20 * s, 64 * s, 236 * s, 212 * s), radius=18 * s, fill=BLUE)
    try:
        font = ImageFont.truetype("arialbd.ttf", int(84 * s))
    except OSError:
        font = ImageFont.load_default()
    text = "LCU"
    box = d.textbbox((0, 0), text, font=font)
    w, h = box[2] - box[0], box[3] - box[1]
    pos = ((size - w) / 2 - box[0], 138 * s - h / 2 - box[1])
    d.text((pos[0] + 4 * s, pos[1] + 4 * s), text, font=font, fill=DARK)
    d.text(pos, text, font=font, fill=(255, 255, 255))
    return img


if __name__ == "__main__":
    icon = draw()
    png = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "lcucraft", "icon.png")
    os.makedirs(os.path.dirname(png), exist_ok=True)
    icon.resize((128, 128), Image.LANCZOS).save(png)
    icon.save(os.path.join(HERE, "icon.ico"), sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print("icon written:", png)
