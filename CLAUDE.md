# Notes for Claude: LEGO City Undercover x Minecraft

What README.md does not say: how this project is worked on, decisions taken, and what is still open. Read README.md
for what the project does and how players install it.

## Owner and rules

- Owner: norrmi (GitHub `norrmi/LEGO-CITY-UNDERCOVER-x-Minecraft`). Writes mostly in Russian: answer in Russian.
- **Commits and PRs carry no Claude co-author / "Generated with" lines** (the owner asked for that). Author is
  `norrmi <1norrmi@gmail.com>` (set in the repo's local git config).
- Fan-made project. **Never commit or ship anything from the game**: no converted data (`lcu_data/`, `*.lcum`, PNGs
  from textures, `lcu_lighting.glsl`), no screenshots of converted content in the repo. Users convert from their own
  copy. `installer/build_exe.py` stages the shader pack without `lcu_lighting.glsl` for this reason.
- Publishing a GitHub release is outward-facing: ask the owner first, every time.
- After a work session, clean up what was created outside the repo (temp test instances, venvs, Gradle daemons,
  build tools installed for one build). The owner asked for that.
- Owner's choices: no traffic, no NPCs, no more drivable cars. Minecraft gets 8 GB of memory.

## Open work (next likely requests)

- **STANDALONES interiors**, deferred on purpose ("not now, but keep the idea close"): FIRE_STATION, BANK, MUSEUM,
  PRISON, BLACKWELLS_MANSION, CONSTRUCTION_SITE, SCRAPYARD, MINE, TEMPLE, SECRETBASE, SPACE_CENTRE, MOON_BASE,
  ROOFTOPS (`LEVELS/STANDALONES/`). Police station and ice cream parlour are done the same way: `converter/
  export_police.py` SITES + `interiors.json`, a teleport spot on the map, a lift/exit menu (`ElevatorScreen`).
- Props of `Builder_NY_Downtown_Global_1` are skipped ("buffer references cannot be resolved" in
  `lcu_gsc._resolve_refs`).
- Parts of the Monaco tunnel lie below Minecraft's water line (flat world water Y 59-64) and are flooded.
- Linux installer support (`installer/install.sh`, Linux paths in `launchers.py`) is untested. Risk: file name case
  (the converter uses the upper-case names of the Steam depot).
- Antivirus: the one-file PyInstaller exe got 4/66 heuristic hits on VirusTotal (Microsoft `Wacatac.C!ml`); the
  folder build with a self-compiled bootloader exists for that. Code signing was not done.

## Layout

- `converter/` (Python, numpy/Pillow/soundfile): `export_lcu.py` is the entry (`main(game, out, shaderpack)`); it
  calls `export_props`, `export_gizprops`, `export_parkour`, `export_police`, `export_trains`, `export_vehicles`;
  `export_audio.py` and `make_world.py` run after it (see `installer/install.py`). `lcu_gsc.py` reads NU20 GSC/GHG,
  `led.py` reads LED. `probe.py`, `topdown.py`, `preview_lcum.py`, `repair_world.py` are dev tools.
- `mod/` Fabric 1.18.2, Yarn build.4, Loom, Java 17, split client/main source sets. Fabric API is nested in the jar
  (every module listed in `build.gradle`; Loom's `include` is not transitive) and `fabric.mod.json` depends on
  `fabric-api-base`, so players need only Fabric Loader.
- `installer/`: `install.py` (steps, CLI), `gui.py` (tkinter window; also CLI when given args), `launchers.py`
  (finds the game and instances; can create an official-launcher profile with Fabric), `build_exe.py`,
  `install.bat` / `install.sh`.
- `shaderpack/LCU_CherryTreeHills/`: Iris pack; presets LOW/MEDIUM/HIGH; `lcu_lighting.glsl` is generated.
- `dist/`: `LCU-Installer.exe` and `lcucraft-<version>.jar` (committed); `LCU-Installer-folder.zip` is a release
  file only (gitignored).
- `tools/launch_prod.py`: run production Minecraft for testing (below).

## Facts the code depends on

- Coordinates: Minecraft = (-x * 4, y * 4, z * 4) + `WORLD_SHIFT` (-639, 64, 740). The shift is fixed forever (old
  worlds hold barrier blocks at those coordinates).
- `.lcum`: per batch texture id (`colour[+normal][#cutout|#translucent]`), flags (1 cutout, 2 translucent,
  4 interior), vertices 28 bytes (3f pos, 2f uv, 4u8 colour, 4i8 normal), u32 indices. Vertex alpha 128 = colour
  stored halved (HDR), 124 = interior (no sun/shadows/grade).
- `world.json` "tiles": `{name, kind, file, bounds, district, tile?}`; kinds `city` (full detail, loaded within the
  City detail radius), `lod` (the game's `*_ULD` far version, drawn while its tile is not on the GPU; `tile` links
  landmark LODs such as `_2125_fwheel`), `props` (LEGO objects, own radius, cut into 48-block columns), `always`
  (the sea). v1.0 data had no kinds: the renderer then loads every mesh file.
- `collision_mesh.bin` v2: triangles grouped in 128-block columns, memory-mapped; v1 (one block) still loads.
- `EXCLUDE` in `export_lcu.py` drops tile copies: `_DRC_`, `_3DS_`, `_TECH_`, `_CUT` (cutscene copies, same
  bounds), `NEW DIVIDE`, `-CHANGES`, `ORIGINALRAMP`, `NEW FLOOR`, `ULD_BRIDGE`. District-wide `<DISTRICT>_ULD` files
  are skipped (they overlap the per-tile ones and some carry data in their colours: a bright red island).
- The sea is an even grid of the game's sea texture (`bm_sea02`, repeats every ~72 blocks), not the game's plane
  (holes, long fan triangles).
- Rendering: `VertexBufferAccessor` gives each `VertexBuffer` its own index list (clear `hasNoIndexBuffer`, fill
  `indexBufferId`, count = indices). `LcuRenderer.drawAll` sets the shader up once per texture and draws many
  buffers (thousands of batches). `GameRendererMixin` raises the far plane to 8192 in a LEGO City world; fog ends at
  5000 (`FAR_FOG`, `LCU_FAR_FOG` in the pack).
- `LcuTexture` must reset GL unpack state (row length, skip pixels/rows, alignment) before `glTexImage2D`: Minecraft
  leaves its own, and the driver then read past the buffer (native crash, often with no hs_err file).
- Uploading textures: `NativeImage.makePixelArray()` is ARGB, upload as `GL_BGRA`; S3TC on upload.

## Building and testing

- Converting the whole city: `python converter/export_lcu.py "<game>" build/lcu_data <copy of shaderpack>`,
  ~5-7 minutes, ~5 GB. Run long jobs in the background.
- The repo usually sits in a long path: Windows then fails to load LWJGL natives (error 206) in `gradlew
  runClient`. Copy `mod/` to a short folder (e.g. `%TEMP%\lcut\mod`) and make `%TEMP%\lcut\build` a junction to the
  repo's `build/` (the run configs read `../build/lcu_data`). Create junctions with PowerShell `New-Item -ItemType
  Junction`; remove them with `cmd /c rmdir` before deleting the folder.
- `gradlew runDevTest` / `runDevPerf` / `runDevCity` (DevTest.java): scripted screenshots, FPS, and a fly-over of
  every district that logs tiles loaded, Java heap, FPS and video memory. The Gradle dev run used JDK 25 here and
  crashed natively now and then; trust tests in production instead:
- `python tools/launch_prod.py <folder> -Dlcucraft.devTest="Cherry Tree Hills" -Dlcucraft.devCity=true
  -Dlcucraft.dataDir=<build/lcu_data>` runs the real 1.18.2 client on Java 17 with 8 GB. Last measured (RTX 5070 Ti,
  radius 2000): 70-120 FPS without shaders, 35-118 with the pack on HIGH, ~0.6 GB heap after GC.
- Installer exe: a venv with `pyinstaller==6.22.3` + requirements, then `python installer/build_exe.py`. Folder
  build: PyInstaller installed from its sdist after `python waf all --gcc --target-arch=64bit` in `bootloader/`
  (GCC from `winget install BrechtSanders.WinLibs.POSIX.UCRT`, uninstall afterwards), then `build_exe.py --folder`.
  Test with `LCU-Installer.exe --game ... --instance <empty folder> --extras`.
- No `gh` CLI on the owner's PC: releases were made through the GitHub REST API with the token from `git credential
  fill` (never printed). Bash heredocs break on some quoting: write longer Python patch scripts to a file.
- The owner's Modrinth profile: `%APPDATA%\ModrinthApp\profiles\Lego city undercover`. Its memory limit lives in the
  app's `app.db` (binary blob): ask the owner to change it in the app rather than editing the database.
