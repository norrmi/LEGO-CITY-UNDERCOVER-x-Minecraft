"""Writes the "Cherry Tree Hills" Minecraft 1.18.2 world: a flat world that is empty except for the sea (stone
floor, water up to Y 64), creative, noon locked like the game's NOON lighting, spawn on the district. The mod adds
the city, its collision and its sky.

    python make_world.py <converter output folder> <saves folder>
"""
import gzip
import io
import json
import os
import random
import struct
import sys

from PIL import Image

DATA_VERSION = 2975  # 1.18.2
WORLD_NAME = "Cherry Tree Hills"


# minimal NBT writer: python values are wrapped to pick the tag type
class Byte(int): pass
class Short(int): pass
class Int(int): pass
class Long(int): pass
class Float(float): pass
class Double(float): pass


def _payload(v):
    if isinstance(v, Byte): return 1, struct.pack(">b", v)
    if isinstance(v, Short): return 2, struct.pack(">h", v)
    if isinstance(v, Long): return 4, struct.pack(">q", v)
    if isinstance(v, Float): return 5, struct.pack(">f", v)
    if isinstance(v, Double): return 6, struct.pack(">d", v)
    if isinstance(v, (Int, int)) and not isinstance(v, bool): return 3, struct.pack(">i", v)
    if isinstance(v, str):
        b = v.encode("utf-8")
        return 8, struct.pack(">H", len(b)) + b
    if isinstance(v, list):
        if not v:
            return 9, struct.pack(">bi", 0, 0)
        tags = [_payload(x) for x in v]
        return 9, struct.pack(">bi", tags[0][0], len(v)) + b"".join(p for _, p in tags)
    if isinstance(v, dict):
        out = io.BytesIO()
        for k, x in v.items():
            t, p = _payload(x)
            kb = k.encode("utf-8")
            out.write(struct.pack(">bH", t, len(kb)) + kb + p)
        out.write(b"\0")
        return 10, out.getvalue()
    raise TypeError(type(v))


def write_nbt(path, root):
    _, p = _payload(root)
    with gzip.open(path, "wb") as f:
        f.write(struct.pack(">bH", 10, 0) + p)


def level_dat(spawn, seed):
    void = {"type": "minecraft:flat", "settings": {
        "biome": "minecraft:the_void", "lakes": Byte(0), "features": Byte(0),
        # the sea: solid stone up to Y 58 (a thin floor over air could be dug through), water Y 59-64 (its surface sits just under the game's sea plane at ~65.4);
        # generated and lit by Minecraft itself, never flows, no edges
        "layers": [{"block": "minecraft:bedrock", "height": Int(1)}, {"block": "minecraft:stone", "height": Int(122)},
                   {"block": "minecraft:water", "height": Int(6)}], "structures": {"structures": {}}}}
    dims = {
        "minecraft:overworld": {"type": "minecraft:overworld", "generator": void},
        "minecraft:the_nether": {"type": "minecraft:the_nether", "generator": {
            "type": "minecraft:noise", "seed": Long(seed), "settings": "minecraft:nether",
            "biome_source": {"type": "minecraft:multi_noise", "preset": "minecraft:nether"}}},
        "minecraft:the_end": {"type": "minecraft:the_end", "generator": {
            "type": "minecraft:noise", "seed": Long(seed), "settings": "minecraft:end",
            "biome_source": {"type": "minecraft:the_end", "seed": Long(seed)}}},
    }
    rules = {"doDaylightCycle": "false", "doWeatherCycle": "false", "doMobSpawning": "false",
             "doTraderSpawning": "false", "doPatrolSpawning": "false", "doInsomnia": "false",
             "doFireTick": "false", "mobGriefing": "false"}
    data = {
        "DataVersion": Int(DATA_VERSION),
        "version": Int(19133),
        "Version": {"Id": Int(DATA_VERSION), "Name": "1.18.2", "Series": "main", "Snapshot": Byte(0)},
        "LevelName": WORLD_NAME,
        "GameType": Int(1), "hardcore": Byte(0), "Difficulty": Byte(0), "DifficultyLocked": Byte(0),
        "allowCommands": Byte(1), "initialized": Byte(1),
        "SpawnX": Int(spawn[0]), "SpawnY": Int(spawn[1]), "SpawnZ": Int(spawn[2]), "SpawnAngle": Float(0),
        "Time": Long(0), "DayTime": Long(6000),  # noon, matches LEGO_CITY_NOON
        "raining": Byte(0), "rainTime": Int(0), "thundering": Byte(0), "thunderTime": Int(0),
        "clearWeatherTime": Int(1000000000),
        "LastPlayed": Long(0), "WasModded": Byte(1),
        "GameRules": rules,
        "WorldGenSettings": {"seed": Long(seed), "generate_features": Byte(0), "bonus_chest": Byte(0),
                             "dimensions": dims},
        "DataPacks": {"Enabled": ["vanilla", "Fabric Mods"], "Disabled": []},
        "ServerBrands": ["fabric"],
        "BorderCenterX": Double(0), "BorderCenterZ": Double(0), "BorderSize": Double(59999968),
        "BorderSafeZone": Double(5), "BorderWarningBlocks": Double(5), "BorderWarningTime": Double(15),
        "BorderSizeLerpTarget": Double(59999968), "BorderSizeLerpTime": Long(0),
        "BorderDamagePerBlock": Double(0.2),
        "WanderingTraderSpawnChance": Int(25), "WanderingTraderSpawnDelay": Int(24000),
        "DragonFight": {"NeedsStateScanning": Byte(1), "DragonKilled": Byte(0), "PreviouslyKilled": Byte(0)},
        "CustomBossEvents": {}, "ScheduledEvents": [],
    }
    return {"Data": data}


def main(data_dir, saves):
    world = json.load(open(os.path.join(data_dir, "world.json")))
    out = os.path.join(saves, WORLD_NAME)
    os.makedirs(out, exist_ok=True)
    seed = random.Random(4934).getrandbits(63)
    write_nbt(os.path.join(out, "level.dat"), level_dat(world["spawn"], seed))
    with open(os.path.join(out, "lcucraft.json"), "w") as f:
        json.dump({"area": "cherry_tree_hills"}, f)
    preview = os.path.join(os.path.dirname(os.path.abspath(data_dir)), "..", "preview_color.png")
    if os.path.exists(preview):
        Image.open(preview).convert("RGB").resize((64, 64), Image.LANCZOS).save(os.path.join(out, "icon.png"))
    print("world written:", out, "spawn", world["spawn"])


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
