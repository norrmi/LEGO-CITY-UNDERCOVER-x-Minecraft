package dev.lcucraft.client;

import com.google.gson.JsonObject;
import dev.lcucraft.LcuData;

import java.util.HashMap;
import java.util.Map;

/** Game texture names of the sky layers (textures.json), used to draw them back to front. */
final class SkyNames {
	static final int BOWL = 0, DOME = 1, CLOUDS = 2, SUN = 3, FRONT = 4;

	private SkyNames() {
	}

	static Map<String, String> load() {
		Map<String, String> out = new HashMap<>();
		try {
			JsonObject o = LcuData.json("textures.json");
			o.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
		} catch (Exception ignored) {
		}
		return out;
	}

	/** NOON: noon_clouds (dome), city_clouds_noon; DAWN/DUSK: new_dawn_sky / new_dusk_sky, city_clouds_dawn/_dusk */
	static int order(String name) {
		if (name.equals("white")) return BOWL;
		if (name.endsWith("_sky") || name.equals("noon_clouds")) return DOME;
		if (name.contains("clouds")) return CLOUDS;
		if (name.contains("sun")) return SUN;
		return FRONT;                            // mountains and anything else in front
	}
}
