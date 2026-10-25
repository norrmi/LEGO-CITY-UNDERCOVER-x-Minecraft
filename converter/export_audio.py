"""Converts the soundscape of Cherry Tree Hills and Little Italy from the user's own LEGO City Undercover install.

    python export_audio.py "<LEGO City Undercover folder>" <converter output folder>

Reads every district tile's *_AUDIO.LED (export_lcu.AREAS):
    MultiEffectsArea  box areas with the ambience bed to loop while you are inside
    Sound             point sounds (birds, bees, radios, TVs, cafés, water...) with position, volume, falloff
                      and a random trigger interval
Samples are resolved through AUDIO/SAMPLES_DEFAULT.CFG. Free roam has no music in this district, so a few calm
tracks from AUDIO/TRACKS/MUSIC_FINAL are added as background music (the mod plays them like Minecraft music).

Writes <out>/resourcepack (sounds, loaded by the mod as a built-in pack) and <out>/audio.json.
"""
import glob
import json
import os
import re
import shutil
import sys

import numpy as np
import soundfile as sf

import export_lcu as e
import led

# calm tracks from the game's soundtrack for background music (not played in this district by the game itself)
MUSIC = ["QUIET_01", "HOME_SWEET_HOME", "ROOFTOPS_AMB", "CITYSTORIES_BREAKS_01", "MOZART_INSTRUMENTAL"]
NS = "lcucraft"


def read_sample_config(path):
    """SAMPLES_DEFAULT.CFG: sample name -> {file, volume, pitch, loop}."""
    out = {}
    for line in open(path, "rb").read().decode("latin1").splitlines():
        m = re.match(r'\s*Sample\s+name\s+"([^"]+)"\s+Filename\s+"([^"]+)"(.*)', line)
        if not m:
            continue
        rest = m.group(3)
        vol = re.search(r"Volume\s+([\d.]+)", rest)
        pitch = re.search(r"(?<!Rand)Pitch\s+([\d.]+)", rest)
        out[m.group(1).lower()] = {"file": m.group(2).replace("\\", "/"),
                                   "volume": float(vol.group(1)) if vol else 1.0,
                                   "pitch": float(pitch.group(1)) if pitch else 1.0,
                                   "loop": bool(re.search(r"\bLoop\b", rest))}
    return out


def decode_objects(path):
    d = open(path, "rb").read()
    try:
        sch = led.read_schema(d)
    except ValueError:
        return []
    out = []
    for name, at in led.objects(d):
        for cls in ("Sound", "MultiEffectsArea"):
            if cls not in sch:
                continue
            try:
                o, end = led.decode(d, sch, cls, at)
            except Exception:
                continue
            nxt = min(x for x in (d.find(b"MOBJ", at), d.find(b"OLST", at), len(d)) if x >= 0)
            if end <= nxt and nxt - end < 64:
                out.append((cls, name, o))
                break
    return out


def to_mc(p, shift):
    return [round(-p[0] * e.SCALE + shift[0], 3), round(p[1] * e.SCALE + shift[1], 3),
            round(p[2] * e.SCALE + shift[2], 3)]


def sound_id(kind, name):
    return f"{kind}/" + re.sub(r"[^a-z0-9_]", "_", name.lower())


def main(game, out):
    shift = e.WORLD_SHIFT
    audio = os.path.join(game, "AUDIO")
    cfg = read_sample_config(os.path.join(audio, "SAMPLES_DEFAULT.CFG"))
    pack = os.path.join(out, "resourcepack")
    snd_dir = os.path.join(pack, "assets", NS, "sounds")
    if os.path.isdir(pack):
        shutil.rmtree(pack)
    os.makedirs(snd_dir)
    with open(os.path.join(pack, "pack.mcmeta"), "w") as f:
        json.dump({"pack": {"pack_format": 8, "description": "LEGO City Undercover sounds (from your own game)"}}, f)

    sounds_json, areas, emitters = {}, [], []
    area_files = sorted(f for a in e.AREAS for f in glob.glob(os.path.join(game, e.CITY, a, "*", "*_AUDIO.LED")))
    for path in area_files:
        for cls, name, o in decode_objects(path):
            m = np.array(o["Mtx"]).reshape(4, 4)
            if cls == "MultiEffectsArea":
                track = o["Ambience track"]
                if not track:
                    continue
                axes = m[:3, :3]
                half = np.linalg.norm(axes, axis=1)
                unit = axes / half[:, None]
                unit[:, 0] = -unit[:, 0]                      # mirror X like the meshes
                areas.append({"name": name, "centre": to_mc(m[3, :3], shift),
                              "axes": [[round(x, 5) for x in a] for a in unit],
                              "half": [round(h * e.SCALE, 3) for h in half],
                              "track": sound_id("amb", track), "volume": o["Ambience track volume"]})
                src = os.path.join(audio, "TRACKS", "AMBIENCE", track.upper() + ".OGG")
                sid = sound_id("amb", track)
                if sid not in sounds_json and os.path.exists(src):
                    os.makedirs(os.path.dirname(os.path.join(snd_dir, sid)), exist_ok=True)
                    shutil.copyfile(src, os.path.join(snd_dir, sid + ".ogg"))  # already Ogg Vorbis
                    sounds_json[sid] = {"sounds": [{"name": f"{NS}:{sid}", "stream": True}]}
            else:
                c = cfg.get(o["Sample"].lower())
                if c is None:
                    continue
                wav = os.path.join(audio, "SAMPLES", c["file"] + ".WAV")
                if not os.path.exists(wav):
                    continue
                sid = sound_id("sfx", o["Sample"])
                far = max(o["Far falloff"], 1.0) * e.SCALE
                if sid not in sounds_json:
                    data, rate = sf.read(wav, always_2d=True)
                    os.makedirs(os.path.dirname(os.path.join(snd_dir, sid)), exist_ok=True)
                    # positional sounds must be mono in Minecraft
                    sf.write(os.path.join(snd_dir, sid + ".ogg"), data.mean(1), rate, format="OGG", subtype="VORBIS")
                    sounds_json[sid] = {"sounds": [{"name": f"{NS}:{sid}", "attenuation_distance": int(round(far))}]}
                emitters.append({"name": name, "pos": to_mc(m[3, :3], shift), "sound": sid,
                                 "volume": round(o["Volume"] * c["volume"], 3), "pitch": c["pitch"],
                                 "far": round(far, 2), "loop": c["loop"] and not o["Trigger randomly"],
                                 "random": bool(o["Trigger randomly"]),
                                 "min_time": o["Random min time"], "max_time": o["Random max time"]})

    music = []
    for t in MUSIC:
        src = os.path.join(audio, "TRACKS", "MUSIC_FINAL", t + ".OGG")
        if os.path.exists(src):
            sid = sound_id("music", t)
            os.makedirs(os.path.dirname(os.path.join(snd_dir, sid)), exist_ok=True)
            shutil.copyfile(src, os.path.join(snd_dir, sid + ".ogg"))
            music.append({"name": f"{NS}:{sid}", "stream": True})
    if music:
        sounds_json["music/free_roam"] = {"sounds": music}

    with open(os.path.join(pack, "assets", NS, "sounds.json"), "w") as f:
        json.dump(sounds_json, f, indent=1)
    with open(os.path.join(out, "audio.json"), "w") as f:
        json.dump({"areas": areas, "emitters": emitters, "music": "music/free_roam" if music else None}, f, indent=1)
    print(f"audio: {len(areas)} ambience areas, {len(emitters)} point sounds, "
          f"{sum(k.startswith('sfx/') for k in sounds_json)} samples, {len(music)} music tracks")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
