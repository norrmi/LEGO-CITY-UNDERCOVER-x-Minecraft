# LEGO City Undercover x Minecraft 

**Fan-made project.** The whole of LEGO City from LEGO City Undercover in Minecraft **1.18.2 (Fabric)**: all 24
districts, islands and bridges, with the game's real 3D models, textures, baked lighting, sky, water, sounds, LEGO
objects, parkour, the police station and the ice cream parlour inside, and Chase McCain's car to drive. Nothing is
made of blocks.

> This is an unofficial fan project. It is not affiliated with, endorsed or sponsored by TT Games, Warner Bros.
> Interactive Entertainment, Nintendo or the LEGO Group. LEGO and LEGO City Undercover are trademarks of their
> respective owners.
>
> **No game files are included.** You need your own copy of LEGO City Undercover (tested with the Steam PC
> version); the installer converts everything on your PC from the game you own. Do not share the generated
> `lcu_data` folder or worlds made from it.

| Folder | What it is |
|---|---|
| `dist/` | **`LCU-Installer.exe`** (the installer, nothing else needed; the folder version is on Releases) and the built mod `lcucraft-1.1.0.jar` (Fabric API inside) |
| `installer/` | the installer's source: `gui.py` (the window), `install.py` (the steps, also usable from the command line), `build_exe.py` |
| `mod/` | Fabric 1.18.2 mod source: draws the converted meshes, sky, fog, collision, cars, interiors |
| `converter/` | Python: reads the game's GSC/GHG/NXG_TEXTURES/RES/LED/GIZ files and writes meshes, textures, collision, lighting |
| `shaderpack/LCU_CherryTreeHills/` | Iris shader pack: the game's sun, shadows, fog, bloom, water; presets LOW/MEDIUM/HIGH |

## Install

You need LEGO City Undercover for PC installed (tested with the Steam version) and a Minecraft account with the
official Minecraft Launcher, the Modrinth App, Prism Launcher or CurseForge.

1. Download **LCU-Installer.exe** from [Releases](https://github.com/norrmi/LEGO-CITY-UNDERCOVER-x-Minecraft/releases/latest) and run it (Windows may warn that it is from an
   unknown publisher: *More info > Run anyway*). If your antivirus blocks the one-file exe (a known false alarm
   for Python-built installers), take **LCU-Installer-folder.zip** instead: unpack it anywhere and run
   `LCU-Installer\LCU-Installer.exe` - same installer, built so it unpacks nothing when it starts.
2. It finds the game through Steam (or pick its folder) and lists your Minecraft instances. Choose either
   - an existing **Minecraft 1.18.2 Fabric** instance (marked `*` when it is surely one), or
   - **a new profile in the official Minecraft Launcher**: it installs Fabric Loader for 1.18.2 and adds the profile
     "LEGO City Undercover x Minecraft" (close the launcher first).
3. Press **Install**. It converts the whole city (about 7 minutes, ~5 GB of disk), puts the mod into `mods/` (Fabric API is inside the
   mod, nothing else to install), downloads Sodium 0.4.1 and Iris 1.6.11 from Modrinth for the shaders (untick it if
   you don't want them), adds the shader pack and creates the world **Cherry Tree Hills** (an existing one is left
   alone).
4. Give the instance **8 GB of memory** (a new Minecraft Launcher profile already has it; in the Modrinth App,
   Prism or CurseForge it is in the instance's settings), start it, open the world, and pick the shader pack `LCU_CherryTreeHills` in
   Options > Video Settings > Shader Packs if you like.

Run the installer again after an update: untick "Convert the game" to only replace the mod and the shader pack.

Without the window (or with Python instead of the exe):

```bat
LCU-Installer.exe --game "C:\Program Files (x86)\Steam\steamapps\common\LEGO City Undercover" --instance "C:\path\to\instance" --extras
```

```bash
python -m pip install -r installer/requirements.txt
```

```bash
python installer/install.py --game "<LEGO City Undercover folder>" --instance "<Minecraft instance folder>" --extras
```

In game:

- `/lcu` takes you to the start point; `/lcu time dawn|noon|dusk` switches the game's lighting sets
- Esc > **LCUxMinecraft Settings**: LEGO objects on/off and their load radius, and the city detail radius
- the police station: step on 85 77 -152 in Cherry Tree Hills; the lift menu takes you between floors
- the ice cream parlour: step on -1715 74 545 in Little Italy
- Chase's Narym waits on parking bay 04 at the police station: right-click to get in, W/S/A/D to drive, sneak to
  get out; `/lcu car <model>` puts another car in front of you

## Building the mod

```bash
cd mod && ./gradlew build
```

The jar lands in `mod/build/libs/` (copy it to `dist/`). The installer exe: `python -m pip install pyinstaller -r
installer/requirements.txt`, then `python installer/build_exe.py` writes `dist/LCU-Installer.exe`. For development, `./gradlew runClient` uses the data in `build/lcu_data`
(convert into it with `python converter/export_lcu.py "<game>" build/lcu_data shaderpack/LCU_CherryTreeHills`).

## What is converted (first import)

- **Map:** every Cherry Tree Hills streaming tile (`LEVELS/LEGO_CITY/LEGO_CITY/SF_RESIDENTIAL`, 26 tiles plus HILLS and
  signs). That is about 2.58 M vertices and 621 textures, including the shared city texture library. Scale: 4 blocks
  per game unit, so the district is about 745 × 672 blocks.
- **Lighting and colour:** the game's baked vertex light, its baked lightmaps and each material's paint tint
  (`texture × 2 × vertex colour × lightmap × tint`). The mod draws one texture per mesh, so the lightmap is sampled
  per vertex. Triangles are subdivided where the lightmap changes, so baked shadows keep their shape. Sun, sky fill,
  fog and bloom come from `LEGO_CITY_NOON_LIGHTING.LED`: sun (1.0, 0.969, 0.82) × 1.6, fog (0.663, 0.773, 0.867) →
  lower fog (0.684, 0.852, 0.933) over 151 game units, bloom 0.8 above 0.5.
- **Time of day:** DAWN, NOON and DUSK, each with its own `LEGO_CITY_<TIME>_LIGHTING.LED` values (sun, fog, colour)
  and its own sky (`LEGO_CITY_<TIME>_DX11.GSC`: dome, clouds, sun, mountain ring). The baked lightmaps exist only
  for noon, so dawn and dusk re-colour them with a grade: (sky fill + sun) at that time ÷ noon, normalised to keep
  brightness. That grade is my approximation, not a value the game stores. Minecraft time 0 = dawn, 6000 = noon,
  12000 = dusk (night uses dusk).
- **Exposure:** the game's light goes above 1 (`texture × 2 × light`), and its auto exposure brings it back. The mod
  and pack use a fixed 0.55 (ideal luminance 0.375 ÷ the city's ~0.68 average). Colours are stored halved (vertex
  alpha 128 marks them) so nothing clips before exposure.
- **Sound:** every tile's `*_AUDIO.LED`: 25 ambience areas (houses, main street, dockside, docks edge, police dock,
  tower) cross-fading as you walk, and 288 point sounds at their real positions (birds on the game's random timers,
  bees, radios, TVs, cafés, water, air conditioners). Samples come from `SAMPLES_DEFAULT.CFG`. The game plays no
  music in this district's free roam, so five calm tracks from its soundtrack (`QUIET_01`, `HOME_SWEET_HOME`, …)
  play now and then instead of Minecraft's music, on the music volume slider.
- **Normal maps (with the shader pack):** each material's tangent-space normal map (UMTL slot 11, plain RGB) is
  exported next to its colour texture. The mod hands it to Iris through Iris's PBR texture loader, so it arrives in
  the pack's `normals` sampler. The pack builds the tangent frame from position and UV derivatives and applies the
  relief as `light(mapped) ÷ light(flat)` on top of the baked light. About 62% of the district has one. Strength and
  the green-channel flip are in the shader settings.
- **Real-time shadows (with the shader pack):** the mod draws the city into Iris's shadow pass (a mixin on Iris's
  `ShadowRenderer`, optional without Iris). The pack projects the shadow map along the game's own sun direction of
  the current time of day (not Minecraft's sun path), 4096² over ±80 blocks, filtered 3×3. Strength follows the
  game's shadow intensity (0.5 at noon); dawn and dusk store 1.0 and are scaled to 0.55 because the pack has no
  sky fill light.
- **City props (`converter/export_props.py`):** lamp posts, benches, bins, cones, chairs, tables, parasols, trees,
  boulders, signs, the playground, ice-cream stand, hoop, shelters and vending machines (848 of 936). The chain:
  tile `*_GZ2.LED` (instance matrix + `CityPropTemplate;Builder_X:Template`) → builder `*_GZ2.LED` (template
  origin) → builder GSC DISP specials (`Template_OPTM`, or the numbered build pieces). World = vertex ×
  inverse(template origin) × instance. They collide like the city.
- **Soft decals:** puddle masks, dirt and stains are drawn as translucent overlays (puddles as a light wet darkening).
- **Texture filtering:** full mip chains and 16× anisotropic filtering, plus FXAA in the shader pack.
- **Collision:** against the real triangles (`collision_mesh.bin`, 2 M triangles; foliage stays walk-through). The
  mod rasterises the triangles around each moving entity into a 0.25-block grid on demand and adds them to Minecraft's
  own entity collision (`World.getEntityCollisions`), so stairs and ramps climb smoothly and nothing can be broken.
  Barrier blocks placed by earlier versions are removed from saved worlds as their chunks load. The world origin is
  fixed (`WORLD_SHIFT`), so re-converting never moves the city.
- **Glass:** textures named glass/window whose alpha is mostly in between (police station windows 0.3–0.5) are drawn
  translucent, after the solid geometry and sorted back to front.
- **Sea:** the world is a flat world with sand at Y 58 and water at Y 59–64, so you can swim and nothing flows.
  The shader pack draws that water in the game's sea colour (`bm_sea02`) with sky reflection, sun glint and waves.
  The game's shoreline foam and seaweed edges sit on top. Without shaders it is Minecraft's own water.
- **The whole city:** every district where the game has it (`export_lcu.AREAS`): Cherry Tree Hills, Little Italy,
  Albatross docks, Chinatown, Downtown (and East, West), Bright Lights Plaza, Uptown, Central Park, the canal, the
  beach, the airport, the tunnels, the farmlands and the forest, the islands (Liberty, prison, space centre), the
  three bridges and the mountains: 391 tiles, ~35 M vertices, about 4000 x 3700 blocks. The mod reads a tile from
  disk and puts it on the GPU when it comes within the **city detail radius** (2000 blocks by default; 1000 / 2000 /
  3000 / whole city in the settings) and frees it again behind you. Everywhere farther away it draws the game's own
  low-detail version of the tile (`*_ULD` files, 3.8 M vertices for the whole city), so the city is in sight from
  anywhere; the camera's far plane and the fog reach 8192 / 5000 blocks in a LEGO City world for that. Tiles go to
  the GPU with shared vertices (an index list per batch, half the video memory of three vertices per triangle) and
  are drawn grouped by texture with one shader set-up per group. Measured with everything around Downtown loaded
  (~350 tiles, RTX 5070 Ti, Sodium + Iris, shaders off): 70-120 FPS, ~3 GB of video memory, 0.6 GB of Java heap
  after garbage collection (peaks of ~4.5 GB while flying across the city fast, hence the 8 GB).
- **Little Italy:** about 1200 blocks west of Cherry Tree Hills (Minecraft X -1830..-1490, Z 265..750).
- **Parkour:** the blue-and-white free-running pieces the tiles place as obstacle gizmos (`<tile>.GIZ`) get their
  BUILDER_PARKOUR models (`export_parkour.py`). Climbing walls and drainpipes are climbable like ladders: walk into
  them or hold jump to go up, sneak to hold on (`climb.json`, LivingEntityClimbMixin).
- **Gizmo objects:** the LEGO objects levels place as gizmos (chairs, desks, barriers, machines, tables, nets...)
  get their models from the builders the level loads (`export_gizprops.py`); in the districts they switch off with
  the other LEGO objects.
- **Cars:** CarEntity with the game's car models (`export_vehicles.py`): right-click to get in, W/S drive and
  brake, A/D steer, sneak to get out; it moves on the driver's client and collides with the city mesh. Chase McCain's
  Narym waits on bay 04 by the police station (spawned once per world). `/lcu car <model>` puts a car in front of
  you (the Narym and the residential cars of AITYPES.TXT). No traffic.
- **Water:** the game's sea texture (bm_sea02) as an even surface just above Minecraft's water, around the whole
  city and 4000 blocks beyond (the game's own sea plane has holes and long fan triangles), plus its shoreline foam.
- **Interior lighting:** interiors (vertex alpha 124, mesh flag 4) skip the sun, its shadows and the time-of-day
  grade; their baked light is dimmed and warmed and the room's lamps (<room>_LIGHTING.LED) are baked in; signs, neon
  and lamps stay bright. Additive glows (glow_gradient light cones) are blended, never solid.
- **Ice Cream Parlour:** the parlour inside (STANDALONES/ICECREAM_PARLOUR/ICECREAM01_PARLOUR) lies right under
  Little Italy at Y ~42-57; the mod clears the stone there as chunks load (the sea floor above stays). Stepping on
  -1715 74 545 goes in; its spot in the room opens a menu with the way out (`interiors.json`).
- **Police station inside:** the lobby, briefing room, line-up room and garage (LEVELS/STANDALONES/POLICE_STATION)
  stand far from the districts (X ~3000, Y ~200). Stepping on 85 77 -152 in Cherry Tree Hills enters the lobby; the
  lift spot of every floor (where the game has its lift doors) opens a floor menu with an exit to the city
  (`interiors.json`, `/lcu floor <lobby|briefing|lineup|garage|parlour|exit>`).
- **Shader presets:** the pack's Profile button switches LOW (no shadows, no normal maps; the shadow pass is off
  entirely), MEDIUM (default: shadows within 48 blocks, 1024 map, hard edges) and HIGH (soft shadows within 80
  blocks, 2048 map). Shadows are stable while walking: the shadow map follows the player in whole texels along the
  sun's axes, without distortion. The mod reads the pack's shadow distance (`shaderpacks/LCU_CherryTreeHills.txt`) and draws into
  the shadow map only the parts of the city inside it; drawing the city there is what costs frame rate.
- **Sea floor:** solid stone up to Y 58 (bedrock at Y -64). `converter/repair_world.py` converts older worlds whose
  one-block sand floor over air could be dug through.
- **Elevated railway train:** `export_trains.py` traces the line from the game's rail meshes (`mt_rail_track`, ~890
  blocks) and converts the Cydonia train the game's AI drives (cab, two carriages, rear cab from
  `CHARS/VEHICLES/TRAINS`). The mod runs it end to end at 9 blocks/s with an 8 s stop at each end, each car resting
  on two track points so it bends through curves. Its position comes from the world time, and it casts shadows with
  the shader pack. It is scenery only: no collision, you can't ride it.
- **LCUxMinecraft Settings** (button in the Esc menu): LEGO objects ON/OFF (off removes the props and their
  collision, kept apart in `collision_props.bin`), the LEGO objects radius (100 / 500 / 1000 blocks / FULL MAP,
  default 500) and the city detail radius (1000 / 2000 / 3000 blocks / WHOLE CITY, default 2000), saved in
  `config/lcucraft.json`. Both load nearest first and free what is left 64 blocks beyond the radius.
- **Collision for the whole city:** ~30 M triangles in `collision_mesh.bin`, grouped in 128-block columns. The file
  stays memory-mapped (outside the Java heap) and only the columns players walk in get a section index.

## How LCU differs from LB2 (for the converter)

- NU20 file version 0x43, MESH 0xA9, UMTL 0xB2, DISP 0x17. Block headers are big endian, but vertex and index data
  are **little endian** (DX11). Each vertex buffer has a `VTXD` tag plus 6 unknown bytes before the data.
- Textures live in `.NXG_TEXTURES` beside the GSC. TXGH entries without a name point (`index >> 16`) into the
  `.GSC.RES` list of 16-byte GUIDs, which resolve into `LEGO_CITY_SHARED_TEXTURES_DX11`.
- UMTL 0xB2: the 18 `localTID` slots sit 586 bytes before the material name. Slot 5 is the colour layer.
  The colour-layer **tint** (B, G, R bytes) sits 341 bytes after the slots and applies in full (checked against the
  game: the shiplap houses come out orange, blue and brown). The houses share near-white plank and plaster textures and get their pastel paint
  from it. Sky materials have tint 0 (the sky shader colours them).
- LMDT ver 2: 44-byte entries (type, instance, 5 texture ids, UV offset, UV scale). Ids 3 and 4 are the lightmap pair:
  baked light with shadows, and sun visibility. A draw uses the entry of the last `0xB0` command before it. Lightmap UV
  = the second UV set (semantic 7) if present, otherwise `uv0.zw`. Parts whose zw are tiling texture coordinates have
  no usable lightmap.
- Alpha is often data: `*_diffh` textures keep a height map there for layer blending. Only real masks (leaves,
  fences, decals) are drawn as cut-outs; for everything else the converter drops the alpha.
- Vertex colours are DX11 `B8G8R8A8`. Read as RGBA, the baked light turns blue-grey.
- The DISP 0x17 matrix table is found by its signature: count + 48-byte matrices, ending 24 bytes before TANB.
- `.LED` lighting files are self-describing (TypeList/ClassList schema, then `MOBJ` objects with fields in schema
  order, little endian). See `converter/led.py`.

## Not done yet

- The props of one Downtown builder (`Builder_NY_Downtown_Global_1`) are not placed: its buffer layout is not read
  yet. Parts of the Monaco tunnel lie below Minecraft's water line and are flooded.
- Lightmaps are per vertex (subdivided), not per pixel. Small sharp shadows are softer than in the game.
- Helper meshes (untextured, no UVs, vertex colour exactly 1: terrain/collision shells, the shadow imposter) are
  collision only. Drawn in their raw material colour, they showed up as flat green/orange slabs.
- 88 of 936 props are not placed yet (a few trees, barriers and chairs whose object names differ from their
  template). Vehicles and trains (`CHARS/VEHICLES`, GHG models with skeletons) are not converted.
- Shadows need Iris and the shader pack; without shaders only the game's baked light is shown. Specular and the second texture layer are not read.
- No real-time shadows: Iris does not re-render the mod's meshes in its shadow pass.
- Single player only. 29 of the 65 point-sound samples aren't loose WAV files (some radios, sirens) and stay silent.
  Sound mixing (volumes, fades) is untested by ear.
