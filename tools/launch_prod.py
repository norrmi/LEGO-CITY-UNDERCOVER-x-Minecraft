"""Starts production Minecraft 1.18.2 + Fabric Loader 0.20.1 (offline) in a game folder, from the files the Modrinth
App has already downloaded - the way players run the mod (Java 17), unlike `gradlew runClient` (dev JDK, dev mappings).

    python tools/launch_prod.py <game folder> [extra JVM args...]

Example, the every-district fly-over against converted data elsewhere:

    python tools/launch_prod.py C:\\tmp\\lcu_prod -Dlcucraft.devTest="Cherry Tree Hills" -Dlcucraft.devCity=true
        -Dlcucraft.dataDir=C:/path/to/build/lcu_data

The game folder needs mods/ (the lcucraft jar; Sodium + Iris to test shaders, with config/iris.properties and the
shader pack in shaderpacks/) and saves/Cherry Tree Hills. A native crash writes hs_err_<pid>.log there.
Windows only as written (Modrinth App under %APPDATA%, its Zulu 17 runtime).
"""
import json
import os
import subprocess
import sys

META = os.path.expandvars(r"%APPDATA%\ModrinthApp\meta")
VID = "1.18.2-0.20.1"
JAVA = os.path.join(META, "java_versions", "zulu17.70.111-ca-jre17.0.21-win_x64", "bin", "java.exe")


def main():
    game_dir = sys.argv[1]
    extra = sys.argv[2:]
    v = json.load(open(os.path.join(META, "versions", VID, VID + ".json")))
    cp = []
    for lib in v["libraries"]:
        if not lib.get("include_in_classpath", True):
            continue
        art = (lib.get("downloads") or {}).get("artifact")
        if art and "path" in art:
            p = os.path.join(META, "libraries", art["path"])
        else:
            g, a, ver = lib["name"].split(":")[:3]
            p = os.path.join(META, "libraries", *g.split("."), a, ver, f"{a}-{ver}.jar")
        if os.path.isfile(p) and p not in cp:
            cp.append(p)
    cp.append(os.path.join(META, "versions", VID, VID + ".jar"))
    args = [JAVA, "-Xmx8G", f"-Djava.library.path={os.path.join(META, 'natives', VID)}",
            f"-XX:ErrorFile={os.path.join(game_dir, 'hs_err_%p.log')}", *extra,
            "-cp", os.pathsep.join(cp), v["mainClass"],
            "--username", "LcuTest", "--version", VID, "--gameDir", game_dir,
            "--assetsDir", os.path.join(META, "assets"), "--assetIndex", v["assetIndex"]["id"],
            "--uuid", "00000000000000000000000000000000", "--accessToken", "0", "--userType", "legacy",
            "--width", "1280", "--height", "720"]
    sys.exit(subprocess.call(args, cwd=game_dir))


if __name__ == "__main__":
    main()
