package dev.lcucraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * An interior you walk into from a district (interiors.json, export_police.py): the police station, whose floors
 * stand far from the districts, or the ice cream parlour under Little Italy. Stepping on the site's entry point
 * enters its first room, and every room's lift spot opens a menu on the client (ElevatorScreen) that answers with
 * /lcu floor &lt;id|exit&gt;. Underground rooms list "carve" boxes: the stone there is cleared as chunks load.
 */
public final class PoliceStation {
	public record Floor(String id, String name, Vec3d lift, float yaw) {
	}

	/** how close (blocks, horizontally) a player must stand to the door point or a lift spot */
	public static final double REACH = 1.3;
	/** below its floors by this much, a player in the station is put back on the nearest floor (a gap, a hole) */
	private static final double FALL_LIMIT = 12;

	public final String id;
	public final String name;
	public final Vec3d entry;
	public final Vec3d exit;
	public final float exitYaw;
	public final List<Floor> floors = new ArrayList<>();
	/** x0 y0 z0 x1 y1 z1 (inclusive) of stone to clear around underground rooms */
	public final List<int[]> carve = new ArrayList<>();
	private final Set<UUID> onEntry = new HashSet<>();

	private PoliceStation(JsonObject o) {
		id = o.has("id") ? o.get("id").getAsString() : "police";
		name = o.has("name") ? o.get("name").getAsString() : "Police Station";
		entry = vec(o.getAsJsonArray("entry"));
		JsonArray x = o.getAsJsonArray("exit");
		exit = vec(x);
		exitYaw = x.size() > 3 ? x.get(3).getAsFloat() : 0;
		for (JsonElement e : o.getAsJsonArray("floors")) {
			JsonObject f = e.getAsJsonObject();
			JsonArray l = f.getAsJsonArray("lift");
			floors.add(new Floor(f.get("id").getAsString(), f.get("name").getAsString(), vec(l), l.get(3).getAsFloat()));
		}
		if (o.has("carve")) {
			for (JsonElement e : o.getAsJsonArray("carve")) {
				JsonArray c = e.getAsJsonArray();
				int[] b = new int[6];
				for (int i = 0; i < 6; i++) {
					b[i] = c.get(i).getAsInt();
				}
				carve.add(b);
			}
		}
	}

	/** Every interior: interiors.json, or the police station alone (police.json of v0.6). */
	public static List<PoliceStation> load() throws IOException {
		List<PoliceStation> out = new ArrayList<>();
		if (java.nio.file.Files.exists(LcuData.dir().resolve("interiors.json"))) {
			for (JsonElement e : LcuData.json("interiors.json").getAsJsonArray("sites")) {
				out.add(new PoliceStation(e.getAsJsonObject()));
			}
		} else {
			out.add(new PoliceStation(LcuData.json("police.json")));
		}
		return out;
	}

	/** Clears the stone of this site's underground rooms inside a freshly loaded chunk. Returns blocks cleared. */
	public int carve(net.minecraft.world.chunk.WorldChunk chunk) {
		int cx = chunk.getPos().getStartX(), cz = chunk.getPos().getStartZ(), n = 0;
		net.minecraft.util.math.BlockPos.Mutable pos = new net.minecraft.util.math.BlockPos.Mutable();
		for (int[] b : carve) {
			int x0 = Math.max(b[0], cx), x1 = Math.min(b[3], cx + 15), z0 = Math.max(b[2], cz), z1 = Math.min(b[5], cz + 15);
			for (int x = x0; x <= x1; x++) {
				for (int z = z0; z <= z1; z++) {
					for (int y = b[1]; y <= b[4]; y++) {
						pos.set(x, y, z);
						if (!chunk.getBlockState(pos).isAir()) {
							chunk.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState(), false);
							n++;
						}
					}
				}
			}
		}
		return n;
	}

	/** The room of this site within 400 blocks (horizontally) of pos, or null. */
	public Floor near(Vec3d pos) {
		return nearest(pos);
	}

	private static Vec3d vec(JsonArray a) {
		return new Vec3d(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
	}

	public static boolean near(Vec3d player, Vec3d spot) {
		return Math.hypot(player.x - spot.x, player.z - spot.z) < REACH && Math.abs(player.y - spot.y) < 2;
	}

	/** Server tick: players stepping onto the door point go in (once per step, not every tick while standing). */
	public void tick(ServerWorld world) {
		for (ServerPlayerEntity p : world.getPlayers()) {
			boolean on = near(p.getPos(), entry);
			if (on && onEntry.add(p.getUuid())) {
				goTo(p, floors.get(0).id());  // the first room, at its lift (the client does not open the menu on arrival)
			} else if (!on) {
				onEntry.remove(p.getUuid());
			}
			Floor near = nearest(p.getPos());
			if (near != null && p.getY() < near.lift().y - FALL_LIMIT && !p.isSpectator()) {
				p.fallDistance = 0;
				goTo(p, near.id());
			}
		}
	}

	/** The floor whose lift is within 400 blocks (horizontally) of pos, or null outside the station. */
	private Floor nearest(Vec3d pos) {
		Floor best = null;
		double d = 400;
		for (Floor f : floors) {
			double h = Math.hypot(pos.x - f.lift().x, pos.z - f.lift().z);
			if (h < d) {
				d = h;
				best = f;
			}
		}
		return best;
	}

	/** /lcu floor &lt;id|exit&gt; */
	public boolean goTo(ServerPlayerEntity p, String id) {
		ServerWorld world = p.getServer().getOverworld();
		if (id.equals("exit")) {
			p.fallDistance = 0;
			p.teleport(world, exit.x, exit.y, exit.z, exitYaw, 0);
			return true;
		}
		for (Floor f : floors) {
			if (f.id().equals(id)) {
				p.fallDistance = 0;
				// above the floor's collision cell (MeshCollision.CELL): starting inside it, Minecraft would let the
				// player fall through
				double y = Math.ceil(f.lift().y / MeshCollision.CELL) * MeshCollision.CELL + 0.3;
				p.teleport(world, f.lift().x, y, f.lift().z, f.yaw(), 0);
				return true;
			}
		}
		return false;
	}
}
