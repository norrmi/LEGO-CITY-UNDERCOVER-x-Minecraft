"""Installs LEGO City Undercover x Minecraft into a Minecraft 1.18.2 Fabric instance.

    python install.py --game "<LEGO City Undercover folder>" --instance "<Minecraft instance folder>"

Everything the mod shows is converted here, on your PC, from your own copy of LEGO City Undercover; nothing from
the game is shipped with this project. Steps:
  1. converts the game's Cherry Tree Hills and Little Italy, the police station, the ice cream parlour, the sky,
     lighting, sounds and cars into <instance>/lcu_data (converter/)
  2. copies the shader pack to <instance>/shaderpacks/LCU_CherryTreeHills (its lighting constants come from step 1)
  3. copies the mod (dist/lcucraft-*.jar) to <instance>/mods, replacing an older version
  4. creates the world "Cherry Tree Hills" in <instance>/saves (only if it does not exist yet)

The instance needs Fabric Loader for Minecraft 1.18.2 and Fabric API; Iris + Sodium for the shader pack.
"""
import argparse
import glob
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, "converter"))

PACK = "LCU_CherryTreeHills"
WORLD = "Cherry Tree Hills"


def check_game(game):
    city = os.path.join(game, "LEVELS", "LEGO_CITY", "LEGO_CITY")
    if not os.path.isdir(os.path.join(city, "SF_RESIDENTIAL")):
        sys.exit(f"Not a LEGO City Undercover folder (LEVELS/LEGO_CITY/LEGO_CITY/SF_RESIDENTIAL missing): {game}")


def check_instance(instance):
    if not os.path.isdir(instance):
        sys.exit(f"Instance folder not found: {instance}")
    mods = os.path.join(instance, "mods")
    os.makedirs(mods, exist_ok=True)
    names = [f.lower() for f in os.listdir(mods)]
    if not any(n.startswith("fabric-api") for n in names):
        print("! No Fabric API in mods/ - install Fabric Loader for 1.18.2 and Fabric API (0.77.0+1.18.2) first")
    if not any(n.startswith("iris") for n in names):
        print("  (no Iris in mods/: the shader pack needs Iris and Sodium; the mod works without them)")


def mod_jar():
    jars = sorted(glob.glob(os.path.join(ROOT, "dist", "lcucraft-*.jar")))
    if not jars:
        sys.exit("dist/lcucraft-*.jar missing - download the release, or build it: cd mod && gradlew build")
    return jars[-1]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--game", required=True, help="LEGO City Undercover folder (the one with LEVELS, CHARS, AUDIO)")
    ap.add_argument("--instance", required=True, help="Minecraft instance folder (the one with mods, saves)")
    ap.add_argument("--skip-convert", action="store_true", help="keep the existing lcu_data (only update mod/pack)")
    a = ap.parse_args()
    check_game(a.game)
    check_instance(a.instance)

    data = os.path.join(a.instance, "lcu_data")
    pack = os.path.join(a.instance, "shaderpacks", PACK)
    print("1/4 shader pack ->", pack)
    if os.path.isdir(pack):
        shutil.rmtree(pack)
    shutil.copytree(os.path.join(ROOT, "shaderpack", PACK), pack)

    if not a.skip_convert:
        print("2/4 converting the game (a few minutes) ->", data)
        import export_audio
        import export_lcu
        os.makedirs(data, exist_ok=True)
        export_lcu.main(a.game, data, pack)
        export_audio.main(a.game, data)
    elif not os.path.isfile(os.path.join(pack, "shaders", "lcu_lighting.glsl")):
        sys.exit("--skip-convert needs a converted lcu_data; run without it once")

    jar = mod_jar()
    mods = os.path.join(a.instance, "mods")
    for old in glob.glob(os.path.join(mods, "lcucraft-*.jar")):
        os.remove(old)
    shutil.copy2(jar, mods)
    print("3/4 mod ->", os.path.join(mods, os.path.basename(jar)))

    saves = os.path.join(a.instance, "saves")
    if os.path.isdir(os.path.join(saves, WORLD)):
        print("4/4 world", WORLD, "already exists - left as it is")
    else:
        import make_world
        os.makedirs(saves, exist_ok=True)
        make_world.main(data, saves)
        print("4/4 world ->", os.path.join(saves, WORLD))
    print("\nDone. Start the instance, open the world", repr(WORLD) + ", and pick the shader pack", PACK,
          "under Options > Video Settings > Shader Packs if you use Iris.")


if __name__ == "__main__":
    main()
