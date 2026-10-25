package dev.lcucraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.util.math.Box;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The blue-and-white climbing walls and drainpipes of the districts (climb.json, export_parkour.py): inside one of
 * these boxes a player climbs like on a ladder (LivingEntityClimbMixin).
 */
public final class Climbing {
	private final List<Box> boxes = new ArrayList<>();

	private Climbing() {
	}

	public static Climbing load(Path file) throws IOException {
		Climbing c = new Climbing();
		JsonArray a = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
		for (JsonElement e : a) {
			JsonArray b = e.getAsJsonArray();
			c.boxes.add(new Box(b.get(0).getAsDouble(), b.get(1).getAsDouble(), b.get(2).getAsDouble(),
					b.get(3).getAsDouble(), b.get(4).getAsDouble(), b.get(5).getAsDouble()));
		}
		return c;
	}

	public int size() {
		return boxes.size();
	}

	public boolean touches(Box entity) {
		for (Box b : boxes) {
			if (b.intersects(entity)) {
				return true;
			}
		}
		return false;
	}
}
