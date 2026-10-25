package dev.lcucraft.client;

import dev.lcucraft.LcuCraft;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;

import java.util.List;

/**
 * Development check (-Dlcucraft.devTest=&lt;world&gt;): opens the world, waits until every mesh is on the GPU, takes
 * screenshots from fixed viewpoints into screenshots/ and quits. Not active in normal play.
 */
final class DevTest {
	/** x, y, z, yaw, pitch (Minecraft) */
	private static final double[][] SHOTS = {
			{0, 260, 330, 180, 32},     // whole district from the south
			{48, 150, 130, 180, 35},    // the zig-zag street
			{0, 91, 0, 0, 4},           // street level at the spawn
			{97, 84, -118, 180, 0},     // police station windows (translucent glass)
			{Double.NaN, 0, 0, 0, 0},   // beside the train on the elevated railway (Trains.lastSeen)
	};
	/** every viewpoint at each time of day (/lcu time) */
	private static final String[] TIMES = {"noon", "dawn", "dusk"};
	private static int stage, wait, shot;

	private DevTest() {
	}

	/**
	 * New places: Little Italy from above, a climbing wall (isClimbing), the police station through its door point,
	 * every floor through the lift command, and out again. 60 ticks per step.
	 */
	private static net.minecraft.util.math.Vec3d carStart;

	private static dev.lcucraft.CarEntity nearestCar(net.minecraft.client.MinecraftClient mc) {
		dev.lcucraft.CarEntity best = null;
		for (net.minecraft.entity.Entity e : mc.world.getEntities()) {
			if (e instanceof dev.lcucraft.CarEntity c && c.model().equals("musclecars_narym")
					&& (best == null || c.squaredDistanceTo(mc.player) < best.squaredDistanceTo(mc.player))) {
				best = c;
			}
		}
		return best;
	}

	private static void tour(net.minecraft.client.MinecraftClient mc) {
		int step = wait / 60, t = wait % 60;
		mc.options.pauseOnLostFocus = false;  // another window taking focus must not pause the server mid-tour
		if (mc.currentScreen instanceof net.minecraft.client.gui.screen.GameMenuScreen) {
			mc.setScreen(null);
		}
		dev.lcucraft.PoliceStation police = dev.lcucraft.LcuCraft.interiors().get(0);
		if (t == 0) {
			switch (step) {
				case 0 -> {
					mc.options.hudHidden = true;
					mc.player.sendChatMessage("/gamemode creative");
					mc.player.sendChatMessage("/tp @s -1505 90 470 160 30");  // Little Italy's canal
				}
				case 1 -> mc.player.sendChatMessage("/tp @s -1660 110 600 160 15");
				case 2 -> {
					try {
						var b = dev.lcucraft.LcuData.dir().resolve("climb.json");
						var a = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(b)).getAsJsonArray().get(0).getAsJsonArray();
						double x = (a.get(0).getAsDouble() + a.get(3).getAsDouble()) / 2, y = a.get(1).getAsDouble() + 0.5,
								z = (a.get(2).getAsDouble() + a.get(5).getAsDouble()) / 2;
						mc.player.sendChatMessage("/gamemode survival");
						mc.player.sendChatMessage(String.format(java.util.Locale.ROOT, "/tp @s %.2f %.2f %.2f", x, y, z));
					} catch (Exception e) {
						LcuCraft.LOG.warn("devTest: no climb.json", e);
					}
				}
				case 3 -> {
					mc.player.sendChatMessage("/gamemode survival");
					mc.player.sendChatMessage("/tp @s 85.5 78 -149.5 180 0");  // walk onto the door point next
				}
				case 4 -> mc.player.sendChatMessage("/tp @s 85.5 78 -151.5");
				case 5, 6, 7 -> mc.player.sendChatMessage("/lcu floor " + police.floors.get(step - 4).id());
				case 8 -> mc.player.sendChatMessage("/lcu floor exit");
				case 9 -> mc.player.sendChatMessage("/tp @s -1714.5 75 547.5 180 0");  // next to the parlour entry
				case 10 -> mc.player.sendChatMessage("/tp @s -1714.5 75 545.5");      // onto it: into the parlour
				case 11 -> mc.player.sendChatMessage("/lcu floor exit");
				case 12 -> mc.player.sendChatMessage("/tp @s 57 79.5 -146 -140 20");  // bay 04 by the police station
				case 13 -> {  // walk up to the Narym
					dev.lcucraft.CarEntity car = nearestCar(mc);
					LcuCraft.LOG.info("devTest: car on bay 04: {}", car == null ? null : car.model() + " at " + car.getPos() + " yaw " + car.getYaw());
					mc.player.sendChatMessage("/lcu car musclecars_narym");  // a Narym of our own, 4 blocks ahead
				}
				case 14 -> mc.options.forwardKey.setPressed(true);  // drive forward
				case 15 -> {
					mc.options.forwardKey.setPressed(false);
					dev.lcucraft.CarEntity car = mc.player.getVehicle() instanceof dev.lcucraft.CarEntity c ? c : null;
					LcuCraft.LOG.info("devTest: driving: in car {}, moved {} blocks, now at {}", car != null,
							car == null || carStart == null ? -1 : car.getPos().distanceTo(carStart), car == null ? null : car.getPos());
				}
				case 16 -> {
					mc.player.sendChatMessage("/tp @s 0 100 0 0 50");
					long cars = 0;
					for (net.minecraft.entity.Entity e : mc.world.getEntities()) {
						cars += e instanceof dev.lcucraft.CarEntity ? 1 : 0;
					}
					LcuCraft.LOG.info("devTest: cars around: {}", cars);
				}
				default -> {
					stage = 5;
					wait = 0;
					return;
				}
			}
		}
		if (step == 13 && t == 30) {  // get in, as a right click does
			dev.lcucraft.CarEntity car = nearestCar(mc);
			if (car != null) {
				mc.interactionManager.interactEntity(mc.player, car, net.minecraft.util.Hand.MAIN_HAND);
				carStart = car.getPos();
			}
		}
		if (step == 7 && t == 25) {
			mc.player.sendChatMessage("/tp @s ~2.5 ~ ~");   // step off the lift...
		}
		if (step == 7 && t == 35) {
			mc.player.sendChatMessage("/tp @s ~-2.5 ~ ~");  // ...and back on: the floor menu should open
		}
		if (t == 20 && step == 2) {
			LcuCraft.LOG.info("devTest: on the climbing wall: isClimbing {} at {}", mc.player.isClimbing(), mc.player.getPos());
		}
		if (t == 50) {
			LcuCraft.LOG.info("devTest: tour step {} at {} screen {}", step, mc.player.getPos(),
					mc.currentScreen == null ? null : mc.currentScreen.getClass().getSimpleName());
			ScreenshotRecorder.saveScreenshot(mc.runDirectory, "lcu_tour_" + step + ".png", mc.getFramebuffer(),
					x -> LcuCraft.LOG.info("devTest: {}", x.getString()));
		}
		if (t == 55 && mc.currentScreen instanceof ElevatorScreen) {
			mc.setScreen(null);
		}
		wait++;
	}

	/** lcucraft.devPerf: FPS at one street view with parts of the city left out, one configuration after another */
	private static final String[] PERF = {"everything", "LEGO objects off", "objects off + no city shadows",
			"objects off + no glass/decals", "objects off + no train", "no city at all (shader + Minecraft only)"};
	private static int perfStep;
	private static final List<Integer> fps = new java.util.ArrayList<>();

	private static void perf(net.minecraft.client.MinecraftClient mc) {
		if (wait == 0 && perfStep == 0) {
			mc.options.maxFps = 260;
			mc.options.enableVsync = false;
			mc.getWindow().setVsync(false);
			mc.options.hudHidden = true;
			mc.player.sendChatMessage("/gamemode spectator");
			mc.player.sendChatMessage("/lcu time noon");
			mc.player.sendChatMessage("/tp @s 0 93 0 0 10");
		}
		if (wait == 0) {
			LcuRenderer.perfNoShadow = perfStep == 2;
			LcuRenderer.perfNoGlass = perfStep == 3;
			LcuRenderer.perfNoTrains = perfStep == 4;
			LcuRenderer.perfNoCity = perfStep == 5;
			LcuSettings.setLegoObjects(perfStep == 0);
			fps.clear();
		}
		if (wait >= 80 && wait % 10 == 0) {  // after 4 s settling, 10 samples over 5 s
			String d = mc.fpsDebugString;
			fps.add(Integer.parseInt(d.substring(0, d.indexOf(' '))));
		}
		if (++wait == 180) {
			LcuCraft.LOG.info("devTest: perf {} -> {} fps (samples {}), shadow passes {}", PERF[perfStep],
					fps.stream().mapToInt(Integer::intValue).average().orElse(0), fps, LcuRenderer.shadowPasses);
			LcuRenderer.shadowPasses = 0;
			wait = 0;
			if (++perfStep == PERF.length) {
				LcuSettings.setLegoObjects(true);
				mc.scheduleStop();
				stage = 99;
			}
		}
	}

	static void register() {
		String world = System.getProperty("lcucraft.devTest");
		if (world == null) {
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			switch (stage) {
				case 0 -> {
					if (mc.currentScreen instanceof TitleScreen) {
						mc.startIntegratedServer(world);
						stage = 1;
					}
				}
				case 1 -> {
					mc.options.pauseOnLostFocus = false;  // another window taking focus must not pause the test
					if (mc.player != null && LcuCraftClient.RENDERER.uploaded()) {
						LcuCraft.LOG.info("devTest: meshes uploaded");
						stage = Boolean.getBoolean("lcucraft.devPerf") ? 10 : 2;
						wait = 0;
					}
				}
				case 10 -> perf(mc);
				case 2 -> {
					if (wait == 0) {
						double[] s = SHOTS[shot % SHOTS.length];
						if (Double.isNaN(s[0])) {
							s = Trains.viewpoint();
						}
						if (shot % SHOTS.length == 0) {
							mc.player.sendChatMessage("/lcu time " + TIMES[shot / SHOTS.length]);
						}
						if (shot == 0) {
							mc.player.sendChatMessage("/gamemode creative");
						}
						mc.player.getAbilities().flying = true;  // hold the viewpoint (the fall test turns this off)
						mc.player.sendAbilitiesUpdate();
						mc.player.sendChatMessage(String.format(java.util.Locale.ROOT, "/tp @s %.1f %.1f %.1f %.1f %.1f",
								s[0], s[1], s[2], s[3], s[4]));
						mc.options.hudHidden = true;
					}
					if (wait == 70 && Double.isNaN(SHOTS[shot % SHOTS.length][0])) {
						double[] v = Trains.viewpoint();  // the train has moved on: catch up with it
						mc.player.sendChatMessage(String.format(java.util.Locale.ROOT, "/tp @s %.1f %.1f %.1f %.1f %.1f",
								v[0], v[1], v[2], v[3], v[4]));
					}
					if (++wait == 80) {
						LcuCraft.LOG.info("devTest: ambience {} at shot {}, player at {} onGround {}", LcuSounds.currentTrack(), shot,
								mc.player.getPos(), mc.player.isOnGround());
						ScreenshotRecorder.saveScreenshot(mc.runDirectory,
								"lcu_" + TIMES[shot / SHOTS.length] + "_" + shot % SHOTS.length + ".png", mc.getFramebuffer(),
								t -> LcuCraft.LOG.info("devTest: {}", t.getString()));
						wait = 0;
						if (++shot == SHOTS.length * TIMES.length) {
							stage = 3;
						}
					}
				}
				case 3 -> {
					// collision check: no flying, drop onto the street at the spawn and see where the player lands
					if (wait == 0) {
						mc.player.sendChatMessage("/gamemode survival");
						mc.player.sendChatMessage("/tp @s 0.5 95 0.5");
					}
					if (wait == 100 || wait == 200) {
						LcuCraft.LOG.info("devTest: fall test after {} ticks: player at {} onGround {}", wait, mc.player.getPos(),
								mc.player.isOnGround());
					}
					// pause menu with the LCUxMinecraft Settings button, then the settings screen
					if (wait == 240) {
						mc.options.hudHidden = false;
						mc.setScreen(new net.minecraft.client.gui.screen.GameMenuScreen(true));
					}
					if (wait == 240 || wait == 250 || wait == 271) {
						LcuCraft.LOG.info("devTest: tick {} screen {} buttons {}", wait, mc.currentScreen == null ? null : mc.currentScreen.getClass().getSimpleName(),
								mc.currentScreen == null ? List.of() : net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(mc.currentScreen)
										.stream().map(b -> b.getMessage().getString()).toList());
					}
					if (wait == 259 || wait == 289) {
						LcuCraft.LOG.info("devTest: screen {} buttons {}", mc.currentScreen == null ? null : mc.currentScreen.getClass().getSimpleName(),
								mc.currentScreen == null ? List.of() : net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(mc.currentScreen)
										.stream().map(b -> b.getMessage().getString()).toList());
					}
					if (wait == 260 || wait == 290) {
						ScreenshotRecorder.saveScreenshot(mc.runDirectory, wait == 260 ? "lcu_menu.png" : "lcu_settings.png",
								mc.getFramebuffer(), t -> LcuCraft.LOG.info("devTest: {}", t.getString()));
					}
					if (wait == 270 && mc.currentScreen != null) {
						for (var b : net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(mc.currentScreen)) {
							if (b.getMessage().getString().startsWith("LCUxMinecraft") && b instanceof net.minecraft.client.gui.widget.ButtonWidget bw) {
								bw.onPress();
							}
						}
					}
					if (wait == 300) {
						mc.setScreen(null);
						mc.options.hudHidden = true;
						mc.player.sendChatMessage("/gamemode spectator");
						mc.player.sendChatMessage("/tp @s 48 110 130 180 40");
					}
					if (wait == 320) {
						LcuCraft.LOG.info("devTest: LEGO objects on, {} prop pieces loaded", LcuCraftClient.RENDERER.loadedProps());
						ScreenshotRecorder.saveScreenshot(mc.runDirectory, "lcu_props.png", mc.getFramebuffer(),
								t -> LcuCraft.LOG.info("devTest: {}", t.getString()));
						LcuSettings.setLegoObjects(false);
					}
					if (wait == 330) {  // same view, LEGO objects off
						LcuCraft.LOG.info("devTest: LEGO objects off, {} prop pieces loaded, props collision {}",
								LcuCraftClient.RENDERER.loadedProps(), LcuCraft.propsCollision(mc.getServer().getOverworld()));
						ScreenshotRecorder.saveScreenshot(mc.runDirectory, "lcu_noprops.png", mc.getFramebuffer(),
								t -> LcuCraft.LOG.info("devTest: {}", t.getString()));
					}
					if (wait == 340) {
						LcuSettings.setLegoObjects(true);
					}
					if (wait == 370) {
						LcuCraft.LOG.info("devTest: LEGO objects on again, {} prop pieces loaded", LcuCraftClient.RENDERER.loadedProps());
					}
					if (++wait == 400) {
						stage = 4;
						wait = 0;
					}
				}
				case 4 -> tour(mc);
				case 5 -> {
					if (wait == 250) {
						System.gc();
					}
					if (wait == 290) {
						Runtime rt = Runtime.getRuntime();
						LcuCraft.LOG.info("devTest: java heap used {} MB of {} MB committed (max {} MB)",
								(rt.totalMemory() - rt.freeMemory()) >> 20, rt.totalMemory() >> 20, rt.maxMemory() >> 20);
					}
					if (++wait == 300) { // ~15 s idle in the world: memory check
						mc.scheduleStop();
					}
				}
				default -> {
				}
			}
		});
	}
}
