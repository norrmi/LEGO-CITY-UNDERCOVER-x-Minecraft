package dev.lcucraft;

import com.google.gson.JsonArray;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.util.math.Box;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.LiteralText;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.minecraft.server.command.CommandManager.literal;

public class LcuCraft implements ModInitializer {
	public static final net.minecraft.entity.EntityType<CarEntity> CAR = net.minecraft.util.registry.Registry.register(
			net.minecraft.util.registry.Registry.ENTITY_TYPE, new net.minecraft.util.Identifier("lcucraft", "car"),
			net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder.<CarEntity>create(
					net.minecraft.entity.SpawnGroup.MISC, CarEntity::new)
					.dimensions(net.minecraft.entity.EntityDimensions.fixed(2.2f, 1.6f))
					.trackRangeBlocks(160).trackedUpdateRate(1).build());
	public static final String ID = "lcucraft";
	public static final Logger LOG = LoggerFactory.getLogger(ID);

	private static Collision collision;
	private static boolean active;
	private static int chunksCleaned;
	private static MeshCollision mesh;
	/** the LEGO props' own collision (collision_props.bin), off together with the props (LCUxMinecraft Settings) */
	private static MeshCollision propsMesh;
	private static volatile boolean propsEnabled = true;
	private static Climbing climbing;
	private static java.util.List<PoliceStation> interiors = java.util.List.of();
	private static Vehicles vehicles;

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			active = LcuData.enabledIn(server);
			collision = null;
			if (!active) {
				return;
			}
			if (!LcuData.available()) {
				LOG.warn("Cherry Tree Hills world, but no converted data in {} - run the converter first", LcuData.dir());
				return;
			}
			try {
				mesh = MeshCollision.load(LcuData.dir().resolve("collision_mesh.bin"));
				LOG.info("Cherry Tree Hills collision: {} triangles", mesh.triangles());
				java.nio.file.Path props = LcuData.dir().resolve("collision_props.bin");
				if (java.nio.file.Files.exists(props)) {
					propsMesh = MeshCollision.load(props);
					LOG.info("LEGO props collision: {} triangles", propsMesh.triangles());
				}
			} catch (Exception e) {
				LOG.error("Could not load the collision mesh", e);
			}
			try {
				climbing = Climbing.load(LcuData.dir().resolve("climb.json"));
				LOG.info("Parkour climbing walls and drainpipes: {}", climbing.size());
			} catch (Exception e) {
				LOG.info("No climbing data ({})", e.toString());
			}
			try {
				interiors = PoliceStation.load();
				for (PoliceStation s : interiors) {
					LOG.info("Interior {}: {} rooms, entry at {}", s.id, s.floors.size(), s.entry);
				}
			} catch (Exception e) {
				LOG.info("No police station data ({})", e.toString());
			}
			try {
				vehicles = Vehicles.load();
				LOG.info("Cars: {} models, {} parked", vehicles.sizes.size(), vehicles.parked.size());
			} catch (Exception e) {
				LOG.info("No vehicle data ({})", e.toString());
			}
			try {
				collision = Collision.load(LcuData.dir().resolve("collision.bin"));
			} catch (Exception e) {
				LOG.info("No old barrier list ({}), nothing to clean up", e.getMessage());
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			active = false;
			collision = null;
			mesh = null;
			propsMesh = null;
			climbing = null;
			interiors = java.util.List.of();
			vehicles = null;
		});
		ServerTickEvents.END_WORLD_TICK.register(world -> {
			if (active && world.getRegistryKey() == World.OVERWORLD) {
				for (PoliceStation s : interiors) {
					s.tick(world);
				}
				if (vehicles != null && world.getTime() % 20 == 0) {
					tickCars(world);
				}
			}
		});
		ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
			if (active && world.getRegistryKey() == World.OVERWORLD) {
				for (PoliceStation s : interiors) {
					int n = s.carve(chunk);
					if (n > 0) {
						LOG.info("{}: cleared {} stone blocks for its rooms in chunk {}", s.id, n, chunk.getPos());
					}
				}
			}
			if (collision != null && world.getRegistryKey() == World.OVERWORLD) {
				int n = collision.removeBarriers(chunk);
				if (n > 0 && ++chunksCleaned % 100 == 1) {
					LOG.info("removed old barrier blocks: {} chunks so far (last {}, {} blocks)", chunksCleaned, chunk.getPos(), n);
				}
			}
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> dispatcher.register(literal("lcu")
				.then(literal("spawn").executes(LcuCraft::toSpawn))
				.then(literal("floor").then(net.minecraft.server.command.CommandManager
						.argument("floor", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
							String id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "floor");
							if (!goTo(ctx.getSource().getPlayer(), id)) {
								ctx.getSource().sendError(new LiteralText("No police station floor '" + id + "'"));
								return 0;
							}
							return 1;
						})))
				.then(literal("car").then(net.minecraft.server.command.CommandManager
						.argument("model", com.mojang.brigadier.arguments.StringArgumentType.word())
						.suggests((ctx, b) -> net.minecraft.command.CommandSource.suggestMatching(
								vehicles == null ? java.util.List.of() : vehicles.sizes.keySet(), b))
						.executes(ctx -> {
							String m = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "model");
							if (vehicles == null || !vehicles.sizes.containsKey(m)) {
								ctx.getSource().sendError(new LiteralText("No car model '" + m + "'"));
								return 0;
							}
							ServerPlayerEntity p = ctx.getSource().getPlayer();
							spawnCar(p.getWorld(), m, p.getPos().add(p.getRotationVector().multiply(4, 0, 4)), p.getYaw());
							return 1;
						})))
				.then(timeCommand(TimeOfDay.DAWN)).then(timeCommand(TimeOfDay.NOON)).then(timeCommand(TimeOfDay.DUSK))
				.then(literal("enable").requires(s -> s.hasPermissionLevel(2)).executes(ctx -> {
					try {
						LcuData.enable(ctx.getSource().getServer());
					} catch (java.io.IOException e) {
						ctx.getSource().sendError(new LiteralText("Could not write " + LcuData.MARKER + ": " + e.getMessage()));
						return 0;
					}
					ctx.getSource().sendFeedback(new LiteralText("Cherry Tree Hills enabled for this world - rejoin to load it"), false);
					return 1;
				}))
				.executes(LcuCraft::toSpawn)));
	}

	/** /lcu time &lt;dawn|noon|dusk&gt;: sets every world's time to that lighting set (the day cycle is off). */
	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> timeCommand(TimeOfDay t) {
		return literal("time").then(literal(t.id()).requires(s -> s.hasPermissionLevel(2)).executes(ctx -> {
			for (var world : ctx.getSource().getServer().getWorlds()) {
				world.setTimeOfDay(t.ticks);
			}
			ctx.getSource().sendFeedback(new LiteralText("Cherry Tree Hills: " + t.id().toUpperCase()), true);
			return 1;
		}));
	}

	/** The city's collision for this world, or null (other dimensions, other worlds, no converted data). */
	public static MeshCollision meshCollision(World world) {
		return active && world.getRegistryKey() == World.OVERWORLD ? mesh : null;
	}

	/** The LEGO props' collision for this world, or null while they are switched off. */
	public static MeshCollision propsCollision(World world) {
		return propsEnabled ? meshCollision(world) == null ? null : propsMesh : null;
	}

	/** Set by the client's LCUxMinecraft Settings (single player: same game). */
	public static void setPropsEnabled(boolean on) {
		propsEnabled = on;
	}

	/** True if the entity touches a blue-and-white climbing wall or drainpipe (LivingEntityClimbMixin). */
	public static boolean climbable(World world, Box box) {
		return climbing != null && meshCollision(world) != null && climbing.touches(box);
	}

	public static Vehicles vehicles() {
		return active ? vehicles : null;
	}

	private static CarEntity spawnCar(net.minecraft.server.world.ServerWorld world, String model, net.minecraft.util.math.Vec3d at, float yaw) {
		CarEntity car = CAR.create(world);
		if (car == null) {
			return null;
		}
		car.setModel(model);
		car.refreshPositionAndAngles(at.x, at.y + 0.3, at.z, yaw, 0);
		world.spawnEntity(car);
		return car;
	}

	/**
	 * Every second: the parked cars (Chase's Narym) appear the first time a player comes near their spot (marked in
	 * the save's lcucraft_cars.txt, so a car driven away is not put back).
	 */
	private static void tickCars(net.minecraft.server.world.ServerWorld world) {
		java.nio.file.Path marks = world.getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("lcucraft_cars.txt");
		java.util.List<String> done;
		try {
			done = java.nio.file.Files.exists(marks) ? java.nio.file.Files.readAllLines(marks) : new java.util.ArrayList<>();
		} catch (java.io.IOException e) {
			done = new java.util.ArrayList<>();
		}
		boolean changed = false;
		for (Vehicles.Parked p : vehicles.parked) {
			String key = p.model() + "@" + (int) p.pos().x + "," + (int) p.pos().z;
			if (!done.contains(key) && world.getPlayers().stream().anyMatch(pl -> pl.squaredDistanceTo(p.pos()) < 96 * 96)
					&& world.isChunkLoaded(net.minecraft.util.math.ChunkSectionPos.getSectionCoord(p.pos().x),
					net.minecraft.util.math.ChunkSectionPos.getSectionCoord(p.pos().z))) {
				CarEntity car = spawnCar(world, p.model(), p.pos(), p.yaw());
				if (car != null) {
					car.setCustomName(new LiteralText(p.name()));
					done.add(key);
					changed = true;
				}
			}
		}
		if (changed) {
			try {
				java.nio.file.Files.write(marks, done);
			} catch (java.io.IOException e) {
				LOG.warn("could not write {}", marks);
			}
		}
	}

	/** The interiors (police station, ice cream parlour) of the open world; empty when none. */
	public static java.util.List<PoliceStation> interiors() {
		return active ? interiors : java.util.List.of();
	}

	/** /lcu floor: a room id of any interior, or exit (out of the interior the player is in). */
	private static boolean goTo(ServerPlayerEntity p, String id) {
		for (PoliceStation s : interiors) {
			if (id.equals("exit") ? s.near(p.getPos()) != null : s.floors.stream().anyMatch(f -> f.id().equals(id))) {
				return s.goTo(p, id);
			}
		}
		return false;
	}

	public static boolean active() {
		return active;
	}

	private static int toSpawn(CommandContext<ServerCommandSource> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayerEntity p = ctx.getSource().getPlayer();
		try {
			JsonArray s = LcuData.json("world.json").getAsJsonArray("spawn");
			p.teleport(ctx.getSource().getServer().getOverworld(), s.get(0).getAsDouble() + 0.5, s.get(1).getAsDouble(),
					s.get(2).getAsDouble() + 0.5, p.getYaw(), p.getPitch());
		} catch (Exception e) {
			ctx.getSource().sendError(new LiteralText("No converted Cherry Tree Hills data found in " + LcuData.dir()));
			return 0;
		}
		return 1;
	}
}
