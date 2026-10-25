package dev.lcucraft.client;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The LCU shader pack's options as Iris saved them (shaderpacks/LCU_CherryTreeHills.txt, only values changed from
 * the pack's defaults), so the mod draws into the shadow map only what the pack's shadow area covers.
 */
final class ShaderOptions {
	/** SHADOW_HALF default in common.glsl (the MEDIUM preset) */
	private static final double DEFAULT_SHADOW_HALF = 48;
	private static long checked, modified = -1;
	private static double shadowHalf = DEFAULT_SHADOW_HALF;

	private ShaderOptions() {
	}

	static double shadowHalf() {
		long now = System.currentTimeMillis();
		if (now - checked > 2000) {  // the file changes only when the options screen is applied
			checked = now;
			reload();
		}
		return shadowHalf;
	}

	private static void reload() {
		Path f = FabricLoader.getInstance().getGameDir().resolve("shaderpacks").resolve("LCU_CherryTreeHills.txt");
		try {
			long m = Files.exists(f) ? Files.getLastModifiedTime(f).toMillis() : 0;
			if (m == modified) {
				return;
			}
			modified = m;
			shadowHalf = DEFAULT_SHADOW_HALF;
			if (m != 0) {
				for (String line : Files.readAllLines(f)) {
					if (line.startsWith("SHADOW_HALF=")) {
						shadowHalf = Double.parseDouble(line.substring("SHADOW_HALF=".length()).trim());
					}
				}
			}
		} catch (Exception e) {
			shadowHalf = DEFAULT_SHADOW_HALF;
		}
	}
}
