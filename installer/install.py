"""Installs LEGO City Undercover x Minecraft into a Minecraft 1.18.2 Fabric instance.

    python install.py --game "<LEGO City Undercover folder>" --instance "<Minecraft instance folder>"

LCU-Installer.exe (gui.py) runs the same steps with a window. Everything the mod shows is converted here, on your
PC, from your own copy of LEGO City Undercover; nothing from the game is shipped with this project. Steps:
  1. copies the shader pack to <instance>/shaderpacks/LCU_CherryTreeHills
  2. converts every district of LEGO City (and the game's own far-away versions of them), the police station, the
     ice cream parlour, the sky, lighting, sounds and cars into <instance>/lcu_data (converter/); the pack's
     lighting constants come from here
  3. copies the mod (dist/lcucraft-*.jar, Fabric API inside) to <instance>/mods, replacing an older version, and
     with --extras downloads Sodium and Iris for 1.18.2 if they are missing
  4. creates the world "Cherry Tree Hills" in <instance>/saves (only if it does not exist yet)

The instance needs Fabric Loader for Minecraft 1.18.2.
"""
import argparse
import glob
import hashlib
import os
import shutil
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
# the exe unpacks dist/, shaderpack/ and converter/ next to itself
ROOT = getattr(sys, "_MEIPASS", os.path.dirname(HERE))
sys.path.insert(0, os.path.join(ROOT, "converter"))

PACK = "LCU_CherryTreeHills"
WORLD = "Cherry Tree Hills"
LIGHTING = os.path.join("shaders", "lcu_lighting.glsl")
# the versions the mod and shader pack were tested with (Modrinth CDN, checked by SHA-512)
EXTRAS = [
    ("sodium", "sodium-fabric-mc1.18.2-0.4.1+build.15.jar",
     "https://cdn.modrinth.com/data/AANobbMI/versions/mc1.18.2-0.4.1/sodium-fabric-mc1.18.2-0.4.1%2Bbuild.15.jar",
     "86eb4db8fdb9f0bb06274c4f150b55273b5b770ffc89e0ba68011152a231b79ebe0b1adda0dd194f92cdcb386f7a60863d9fee5d15c1c35"
     "47ffa22a19083a1ee"),
    ("iris", "iris-mc1.18.2-1.6.11.jar",
     "https://cdn.modrinth.com/data/YL57xq9U/versions/ogIRhnAJ/iris-mc1.18.2-1.6.11.jar",
     "643d91c4639f4e7ff01dea5987e1e1b194d705518eb72f50ea2c000cc1a11c79abe45b9b39e805d43eb3f32987e57e90171ff825916626b8"
     "ef28c2f06647726e"),
]


class InstallError(Exception):
    pass


def check_game(game):
    city = os.path.join(game, "LEVELS", "LEGO_CITY", "LEGO_CITY")
    if not os.path.isdir(os.path.join(city, "SF_RESIDENTIAL")):
        raise InstallError("This is not the LEGO City Undercover folder (LEVELS\\LEGO_CITY\\LEGO_CITY\\SF_RESIDENTIAL "
                           f"is missing):\n{game}")


def check_instance(instance):
    if not os.path.isdir(instance):
        raise InstallError(f"Minecraft instance folder not found:\n{instance}")


def mod_jar():
    jars = sorted(glob.glob(os.path.join(ROOT, "dist", "lcucraft-*.jar")))
    if not jars:
        raise InstallError("dist/lcucraft-*.jar missing - download the release, or build it: cd mod && gradlew build")
    return jars[-1]


def download_extras(mods, log):
    names = [f.lower() for f in os.listdir(mods)]
    for key, name, url, sha512 in EXTRAS:
        if any(n.startswith(key) and n.endswith(".jar") for n in names):
            log(f"    {key} already in mods - kept")
            continue
        log(f"    downloading {name}")
        req = urllib.request.Request(url, headers={"User-Agent": "norrmi/LEGO-CITY-UNDERCOVER-x-Minecraft installer"})
        with urllib.request.urlopen(req, timeout=60) as r:
            body = r.read()
        if hashlib.sha512(body).hexdigest() != sha512:
            raise InstallError(f"{name}: download does not match its checksum, not installed")
        with open(os.path.join(mods, name), "wb") as f:
            f.write(body)


def install(game, instance, convert=True, extras=False, log=print):
    check_game(game)
    check_instance(instance)
    data = os.path.join(instance, "lcu_data")
    pack = os.path.join(instance, "shaderpacks", PACK)
    mods = os.path.join(instance, "mods")
    os.makedirs(mods, exist_ok=True)

    log(f"1/4 shader pack -> {pack}")
    lighting = None
    if os.path.isfile(os.path.join(pack, LIGHTING)):
        with open(os.path.join(pack, LIGHTING), "rb") as f:
            lighting = f.read()
    if os.path.isdir(pack):
        shutil.rmtree(pack)
    shutil.copytree(os.path.join(ROOT, "shaderpack", PACK), pack)

    if convert:
        log(f"2/4 converting the whole city (about 7 minutes, ~5 GB) -> {data}")
        import export_audio
        import export_lcu
        os.makedirs(data, exist_ok=True)
        export_lcu.main(game, data, pack)
        export_audio.main(game, data)
    elif lighting is not None:
        log("2/4 keeping the converted data")
        with open(os.path.join(pack, LIGHTING), "wb") as f:
            f.write(lighting)
    else:
        raise InstallError("Nothing converted yet in this instance: run the installer with conversion once")

    jar = mod_jar()
    for old in glob.glob(os.path.join(mods, "lcucraft-*.jar")):
        os.remove(old)
    shutil.copy2(jar, mods)
    log(f"3/4 mod -> {os.path.join(mods, os.path.basename(jar))}")
    if extras:
        download_extras(mods, log)

    saves = os.path.join(instance, "saves")
    if os.path.isdir(os.path.join(saves, WORLD)):
        log(f"4/4 world {WORLD} already exists - left as it is")
    else:
        import make_world
        os.makedirs(saves, exist_ok=True)
        make_world.main(data, saves)
        log(f"4/4 world -> {os.path.join(saves, WORLD)}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--game", required=True, help="LEGO City Undercover folder (the one with LEVELS, CHARS, AUDIO)")
    ap.add_argument("--instance", required=True, help="Minecraft instance folder (the one with mods, saves)")
    ap.add_argument("--skip-convert", action="store_true", help="keep the existing lcu_data (only update mod/pack)")
    ap.add_argument("--extras", action="store_true", help="also download Sodium and Iris for 1.18.2 if missing")
    a = ap.parse_args()
    try:
        install(a.game, a.instance, convert=not a.skip_convert, extras=a.extras)
    except InstallError as e:
        sys.exit(str(e))
    print("\nDone. Give the instance 8 GB of memory (the whole city needs it), start it, open the world",
          repr(WORLD) + ", and pick the shader pack", PACK,
          "under Options > Video Settings > Shader Packs if you use Iris.")


if __name__ == "__main__":
    main()
