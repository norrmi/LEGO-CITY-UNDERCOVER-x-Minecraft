"""Finds LEGO City Undercover and Minecraft instances on this PC, and makes a Fabric 1.18.2 profile for the official
Minecraft Launcher. Used by gui.py; everything here is a best guess the user can override with Browse.

Windows: the launchers' folders under %APPDATA%, Steam from the registry. Linux (Arch and others): the same folders
under $XDG_DATA_HOME (~/.local/share) and in their Flatpak sandboxes (~/.var/app/<id>), ~/.minecraft, and Steam's
usual places (the game runs there through Proton, its files are the same)."""
import base64
import datetime
import glob
import json
import os
import re
import urllib.request

GAME_DIR = "LEGO City Undercover"
MC_VERSION = "1.18.2"
PROFILE_ID = "lcucraft"
PROFILE_NAME = "LEGO City Undercover x Minecraft"
META = "https://meta.fabricmc.net/v2/versions/loader/" + MC_VERSION
HOME = os.path.expanduser("~")
WINDOWS = os.name == "nt"
APPDATA = os.environ.get("APPDATA", HOME)
XDG_DATA = os.environ.get("XDG_DATA_HOME") or os.path.join(HOME, ".local", "share")


def _data_dirs(name, flatpak=None):
    """Where a launcher keeps its data: %APPDATA%/<name> on Windows; on Linux $XDG_DATA_HOME/<name> and, for the
    Flatpak build, ~/.var/app/<flatpak>/data/<name>."""
    if WINDOWS:
        return [os.path.join(APPDATA, name)]
    dirs = [os.path.join(XDG_DATA, name)]
    if flatpak:
        dirs.append(os.path.join(HOME, ".var", "app", flatpak, "data", name))
    return dirs


def _vanilla_dir():
    """The official launcher's .minecraft (on Linux also its Flatpak's)."""
    if WINDOWS:
        return os.path.join(APPDATA, ".minecraft")
    for d in (os.path.join(HOME, ".minecraft"), os.path.join(HOME, ".var", "app", "com.mojang.Minecraft", ".minecraft")):
        if os.path.isfile(os.path.join(d, "launcher_profiles.json")):
            return d
    return os.path.join(HOME, ".minecraft")


VANILLA = _vanilla_dir()


def _steam_libraries():
    roots = []
    try:
        import winreg
        for hive, key in ((winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam"),
                          (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\WOW6432Node\Valve\Steam")):
            try:
                with winreg.OpenKey(hive, key) as k:
                    for value in ("SteamPath", "InstallPath"):
                        try:
                            roots.append(os.path.normpath(winreg.QueryValueEx(k, value)[0]))
                        except OSError:
                            pass
            except OSError:
                pass
    except ImportError:
        pass
    if WINDOWS:
        roots.append(r"C:\Program Files (x86)\Steam")
    else:
        roots += [os.path.join(XDG_DATA, "Steam"), os.path.join(HOME, ".steam", "steam"),
                  os.path.join(HOME, ".steam", "root"),
                  os.path.join(HOME, ".var", "app", "com.valvesoftware.Steam", ".local", "share", "Steam")]
    libs = []
    for root in roots:
        libs.append(root)
        vdf = os.path.join(root, "steamapps", "libraryfolders.vdf")
        if os.path.isfile(vdf):
            with open(vdf, encoding="utf-8", errors="replace") as f:
                libs += [p.replace("\\\\", "\\") for p in re.findall(r'"path"\s+"([^"]+)"', f.read())]
    seen, out = set(), []
    for lib in libs:
        key = os.path.normcase(os.path.realpath(lib))  # ~/.steam/steam is a link to ~/.local/share/Steam
        if key not in seen:
            seen.add(key)
            out.append(lib)
    return out


def find_game():
    """The LEGO City Undercover folder of a Steam install, or None."""
    for lib in _steam_libraries():
        game = os.path.join(lib, "steamapps", "common", GAME_DIR)
        if os.path.isdir(os.path.join(game, "LEVELS")):
            return game
    return None


def _mods_say_118(game_dir):
    """Modrinth keeps no plain version file: a mod built for 1.18.2 in mods/ is the best hint there is."""
    return any("1.18.2" in os.path.basename(p) for p in glob.glob(os.path.join(game_dir, "mods", "*.jar")))


def _prism_instances(prism):
    """Prism Launcher instances under one data folder (its prismlauncher.cfg may move them elsewhere)."""
    found = []
    inst = os.path.join(prism, "instances")
    cfg = os.path.join(prism, "prismlauncher.cfg")
    if os.path.isfile(cfg):
        with open(cfg, encoding="utf-8", errors="replace") as f:
            m = re.search(r"^InstanceDir=(.+)$", f.read(), re.M)
        if m:
            inst = m.group(1).strip()
            if not os.path.isabs(inst):
                inst = os.path.join(prism, inst)
    for d in sorted(glob.glob(os.path.join(inst, "*"))):
        game = next((os.path.join(d, s) for s in (".minecraft", "minecraft") if os.path.isdir(os.path.join(d, s))),
                    None)
        if game is None:
            continue
        ok = False
        try:
            with open(os.path.join(d, "mmc-pack.json"), encoding="utf-8") as f:
                comps = {c.get("uid"): c.get("version") for c in json.load(f).get("components", [])}
            ok = comps.get("net.minecraft") == MC_VERSION and "net.fabricmc.fabric-loader" in comps
        except (OSError, ValueError):
            pass
        found.append(("Prism: " + os.path.basename(d), game, ok))
    return found


def find_instances():
    """[(label, game folder, ok)] for Modrinth App, Prism Launcher and CurseForge; ok means Fabric 1.18.2 for sure
    (or very likely), those come first."""
    found = []
    for data in _data_dirs("ModrinthApp", "com.modrinth.ModrinthApp") + _data_dirs("com.modrinth.theseus"):
        for d in sorted(glob.glob(os.path.join(data, "profiles", "*"))):
            if os.path.isdir(d):
                found.append(("Modrinth: " + os.path.basename(d), d, _mods_say_118(d)))

    for prism in _data_dirs("PrismLauncher", "org.prismlauncher.PrismLauncher"):
        found += _prism_instances(prism)

    for d in sorted(glob.glob(os.path.join(HOME, "curseforge", "minecraft", "Instances", "*"))):
        ok = False
        try:
            with open(os.path.join(d, "minecraftinstance.json"), encoding="utf-8") as f:
                info = json.load(f)
            loader = (info.get("baseModLoader") or {}).get("name", "")
            ok = info.get("gameVersion") == MC_VERSION and loader.startswith("fabric")
        except (OSError, ValueError):
            pass
        if os.path.isdir(d):
            found.append(("CurseForge: " + os.path.basename(d), d, ok))
    found.sort(key=lambda x: not x[2])
    return found


def vanilla_available():
    return os.path.isfile(os.path.join(VANILLA, "launcher_profiles.json"))


def _get_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": "norrmi/LEGO-CITY-UNDERCOVER-x-Minecraft installer"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def create_vanilla_profile(game_dir, icon_png=None, mc_dir=VANILLA, log=print):
    """Installs the newest stable Fabric Loader for 1.18.2 into the official launcher (the way the Fabric installer
    does) and adds a profile that runs it in game_dir. The launcher downloads Minecraft itself on first start."""
    profiles_file = os.path.join(mc_dir, "launcher_profiles.json")
    if not os.path.isfile(profiles_file):
        raise RuntimeError("The Minecraft Launcher is not installed (or was never started) - launcher_profiles.json "
                           f"missing in {mc_dir}")
    loaders = _get_json(META)
    loader = next((l["loader"]["version"] for l in loaders if l["loader"].get("stable")), loaders[0]["loader"]["version"])
    profile = _get_json(f"{META}/{loader}/profile/json")
    vid = profile["id"]
    vdir = os.path.join(mc_dir, "versions", vid)
    os.makedirs(vdir, exist_ok=True)
    with open(os.path.join(vdir, vid + ".json"), "w", encoding="utf-8") as f:
        json.dump(profile, f, indent=2)
    open(os.path.join(vdir, vid + ".jar"), "wb").close()
    log(f"    Fabric Loader {loader} -> {vdir}")

    with open(profiles_file, encoding="utf-8") as f:
        data = json.load(f)
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")
    entry = data.setdefault("profiles", {}).get(PROFILE_ID, {})
    entry.update({"name": PROFILE_NAME, "type": "custom", "lastVersionId": vid, "gameDir": game_dir,
                  "javaArgs": "-Xmx8G -XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:MaxGCPauseMillis=50",
                  "lastUsed": now})
    entry.setdefault("created", now)
    if icon_png and os.path.isfile(icon_png):
        with open(icon_png, "rb") as f:
            entry["icon"] = "data:image/png;base64," + base64.b64encode(f.read()).decode()
    data["profiles"][PROFILE_ID] = entry
    tmp = profiles_file + ".lcu"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
    os.replace(tmp, profiles_file)
    os.makedirs(game_dir, exist_ok=True)
    log(f"    Minecraft Launcher profile '{PROFILE_NAME}' -> {game_dir}")
