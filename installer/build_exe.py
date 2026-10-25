"""Builds dist/LCU-Installer.exe: one file with Python, the converter, the mod jar and the shader pack inside.

    python -m pip install pyinstaller -r installer/requirements.txt
    python installer/build_exe.py

Build the mod first (cd mod && gradlew build, then copy the jar to dist/). Nothing from the game goes in: the
shader pack is staged without its generated lighting file.
"""
import glob
import os
import shutil
import sys

import PyInstaller.__main__

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
STAGE = os.path.join(ROOT, "build", "exe")
PACK = "LCU_CherryTreeHills"
CONVERTER = ["export_lcu", "export_audio", "make_world", "export_props", "export_gizprops", "export_parkour",
             "export_police", "export_trains", "export_vehicles", "lcu_gsc", "led"]


def main():
    jars = sorted(glob.glob(os.path.join(ROOT, "dist", "lcucraft-*.jar")))
    if not jars:
        sys.exit("dist/lcucraft-*.jar missing: build the mod first")
    pack = os.path.join(STAGE, "shaderpack", PACK)
    if os.path.isdir(STAGE):
        shutil.rmtree(STAGE)
    shutil.copytree(os.path.join(ROOT, "shaderpack", PACK), pack, ignore=shutil.ignore_patterns("lcu_lighting.glsl"))
    icon_png = os.path.join("mod", "src", "main", "resources", "assets", "lcucraft")
    data = [
        (jars[-1], "dist"),
        (pack, os.path.join("shaderpack", PACK)),
        (os.path.join(HERE, "icon.ico"), "installer"),
        (os.path.join(ROOT, icon_png, "icon.png"), icon_png),
    ]
    args = [os.path.join(HERE, "gui.py"), "--onefile", "--windowed", "--noconfirm", "--clean",
            "--name", "LCU-Installer", "--icon", os.path.join(HERE, "icon.ico"),
            "--paths", os.path.join(ROOT, "converter"), "--paths", HERE,
            "--distpath", os.path.join(ROOT, "dist"), "--workpath", os.path.join(STAGE, "work"),
            "--specpath", STAGE]
    for name in CONVERTER:
        args += ["--hidden-import", name]
    for src, dst in data:
        args += ["--add-data", f"{src}{os.pathsep}{dst}"]
    PyInstaller.__main__.run(args)
    print("built", os.path.join(ROOT, "dist", "LCU-Installer.exe"))


if __name__ == "__main__":
    main()
