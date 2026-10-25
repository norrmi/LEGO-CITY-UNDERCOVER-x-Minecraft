package dev.lcucraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * vehicles.json (export_vehicles.py): car models and their sizes, and the cars parked in the world from the start
 * (Chase McCain's Narym on bay 04).
 */
public final class Vehicles {
	public record Parked(String model, Vec3d pos, float yaw, String name) {
	}

	public final Map<String, float[]> sizes = new HashMap<>();
	public final List<Parked> parked = new ArrayList<>();

	private Vehicles(JsonObject o) {
		for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("models").entrySet()) {
			JsonArray s = e.getValue().getAsJsonObject().getAsJsonArray("size");
			sizes.put(e.getKey(), new float[]{s.get(0).getAsFloat(), s.get(1).getAsFloat(), s.get(2).getAsFloat()});
		}
		for (JsonElement e : o.getAsJsonArray("parked")) {
			JsonObject p = e.getAsJsonObject();
			JsonArray pos = p.getAsJsonArray("pos");
			parked.add(new Parked(p.get("model").getAsString(),
					new Vec3d(pos.get(0).getAsDouble(), pos.get(1).getAsDouble(), pos.get(2).getAsDouble()),
					p.get("yaw").getAsFloat(), p.has("name") ? p.get("name").getAsString() : p.get("model").getAsString()));
		}
	}

	public static Vehicles load() throws IOException {
		return new Vehicles(LcuData.json("vehicles.json"));
	}
}
