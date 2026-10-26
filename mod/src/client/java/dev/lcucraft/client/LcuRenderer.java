package dev.lcucraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lcucraft.LcuCraft;
import dev.lcucraft.LcuData;
import dev.lcucraft.TimeOfDay;
import dev.lcucraft.client.mixin.VertexBufferAccessor;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.Shader;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Draws the converted LEGO City meshes and the LCU sky for the current time of day (DAWN, NOON, DUSK).
 *
 * The city is a list of tiles (world.json "tiles", export_lcu.py). Tiles within the map detail radius
 * (LCUxMinecraft Settings, 2000 blocks by default) are read from disk and put on the GPU nearest first, and freed
 * again once left far behind; everywhere else the game's own low-detail version of the tile (lod/) is drawn, so the
 * whole city stays in sight. LEGO props have their own, smaller radius.
 *
 * Mesh colours are stored halved (vertex alpha 128) so the game's light can go above 1. Without a shader pack the
 * meshes use Minecraft's entity cut-out shader with zero normals, which makes its fixed lighting a constant 0.4; a
 * colour modulator of 5 (2.5 to cancel the 0.4, 2 to undo the halving) times the exposure and the time-of-day grade
 * restores the game's colour. With an Iris shader pack the real normals are uploaded and the pack does the same from the alpha flag.
 */
public final class LcuRenderer {
	private static final int LIGHT = 0xF000F0;
	/** indices put on the GPU per frame at most, so loading tiles while moving does not freeze the game */
	private static final int UPLOAD_BUDGET = 600_000;
	/** tiles read from disk at the same time */
	private static final int READS = 2;
	/** sky geometry radius in its own units (the NOON dome is ~5600 across) */
	private static final float SKY_RADIUS = 5600f;
	/** the camera's far plane in a LEGO City world (GameRendererMixin): the whole city, ~4000 blocks across */
	public static final float FAR_PLANE = 8192f;
	/** where the fog becomes thick (BackgroundRendererMixin, LCU_FAR_FOG in the shader pack): beyond the city */
	public static final float FAR_FOG = 5000f;
	/** fixed stand-in for the game's auto exposure, same value as LCU_EXPOSURE in the shader pack (export_lcu.py) */
	private static final float EXPOSURE = 0.55f;

	record GpuBatch(Identifier texture, boolean translucent, boolean interior, VertexBuffer buffer, Box bounds) {
	}

	/** One time of day: LEGO_CITY_&lt;TIME&gt;_LIGHTING.LED values and its sky. */
	private static final class Light {
		float[] fog = {0.663f, 0.773f, 0.867f};
		float[] lowerFog = {0.684f, 0.852f, 0.933f};
		float fogFar = 604;
		float[] sunDir = {-0.32158f, 0.93527f, 0.14782f};
		float[] grade = {1, 1, 1};
		List<Lcum.Batch> skyCpu = List.of();
		final List<GpuBatch> sky = new ArrayList<>();
	}

	enum Kind {
		/** full detail, within the map detail radius */
		CITY,
		/** the game's low-detail version of a CITY tile, drawn while that one is not on the GPU */
		LOD,
		/** LEGO props: cut into columns, within the LEGO objects radius */
		PROPS,
		/** always loaded (the sea) */
		ALWAYS
	}

	/** One converted .lcum file and its state. */
	private static final class Tile {
		final String name;
		final Path file;
		final Kind kind;
		/** null until read when world.json has no bounds (data converted by v1.0) */
		Box bounds;
		CompletableFuture<List<Lcum.Batch>> reading;
		/** read, not all on the GPU yet (PROPS: all columns, kept while the tile is loaded) */
		List<Lcum.Batch> cpu;
		/** GPU copies; for PROPS one slot per column of cpu, null while outside the radius */
		final List<GpuBatch> gpu = new ArrayList<>();
		boolean ready;

		Tile(String name, Path file, Kind kind, Box bounds) {
			this.name = name;
			this.file = file;
			this.kind = kind;
			this.bounds = bounds;
		}
	}

	/** columns the props are cut into for loading around the player (Lcum.split) */
	private static final float CELL = 48f;
	/** loaded pieces stay on the GPU until this far beyond their radius, so walking along the edge does not reload */
	private static final double UNLOAD_MARGIN = 64;
	private final List<Tile> tiles = new ArrayList<>();
	/** tiles that should be loaded but are not on the GPU yet */
	private int missing = 1;
	/** where and with which radii the tiles were last checked */
	private Vec3d lastScan;
	private double lastRadius, lastMapRadius;
	private static final double RESCAN = 4;
	private final Map<TimeOfDay, Light> lights = new EnumMap<>(TimeOfDay.class);
	private final Set<String> textures = new HashSet<>();
	private boolean loaded;
	private boolean uploadedWithNormals;
	private boolean skyBuilt;
	private BufferBuilder builder;
	private final Trains trains = new Trains(this);
	private final Cars cars = new Cars(this);
	/** DevTest performance switches: leave parts out to measure what they cost */
	static boolean perfNoShadow, perfNoGlass, perfNoTrains, perfNoCity;
	static int shadowPasses;

	public LcuRenderer() {
		for (TimeOfDay t : TimeOfDay.values()) {
			lights.put(t, new Light());
		}
	}

	/** Everything that should be on the GPU around the camera is there. */
	public boolean uploaded() {
		return loaded && missing == 0 && skyBuilt;
	}

	/** Reads the tile list and the skies; the meshes follow around the player while playing. */
	public void load() {
		unload();
		Path dir = LcuData.dir();
		for (TimeOfDay t : TimeOfDay.values()) {
			Light l = lights.get(t);
			try {
				JsonObject j = LcuData.json(Files.exists(dir.resolve(t.lightingFile())) ? t.lightingFile() : "lighting.json");
				l.fog = floats(j.getAsJsonArray("fog_colour"));
				l.lowerFog = floats(j.getAsJsonArray("lower_fog_colour"));
				l.fogFar = j.get("fog_far").getAsFloat();
				if (j.has("sun_direction")) {
					l.sunDir = floats(j.getAsJsonArray("sun_direction"));
				}
				if (j.has("grade")) {
					l.grade = floats(j.getAsJsonArray("grade"));
				}
				Path sky = dir.resolve(t.skyFile());
				l.skyCpu = Lcum.read(Files.exists(sky) ? sky : dir.resolve("sky.lcum"));
			} catch (Exception e) {
				LcuCraft.LOG.warn("lighting or sky for {} missing, using defaults", t, e);
			}
		}
		trains.load(dir);
		try {
			readTiles(dir);
		} catch (Exception e) {
			LcuCraft.LOG.error("Could not read the tile list from {}", dir, e);
		}
		loaded = true;
		LcuCraft.LOG.info("LEGO City: {} tiles ({} far versions)", tiles.size(),
				tiles.stream().filter(t -> t.kind == Kind.LOD).count());
	}

	/** world.json "tiles" (kind, file, bounds); data from v1.0 has none of that, then every mesh file is loaded. */
	private void readTiles(Path dir) throws Exception {
		JsonArray list = LcuData.json("world.json").getAsJsonArray("tiles");
		boolean v11 = list != null && list.size() > 0 && list.get(0).getAsJsonObject().has("file");
		if (!v11) {
			try (Stream<Path> files = Files.list(dir.resolve("meshes"))) {
				for (Path f : files.filter(p -> p.toString().endsWith(".lcum")).sorted().toList()) {
					String name = f.getFileName().toString().replace(".lcum", "");
					tiles.add(new Tile(name, f, name.startsWith("zz_props") ? Kind.PROPS : Kind.ALWAYS, null));
				}
			}
			return;
		}
		Map<String, Tile> city = new HashMap<>();
		Map<Tile, String> lods = new HashMap<>();
		for (JsonElement e : list) {
			JsonObject o = e.getAsJsonObject();
			float[] b = new float[6];
			JsonArray a = o.getAsJsonArray("bounds");
			for (int i = 0; i < 6; i++) {
				b[i] = a.get(i).getAsFloat();
			}
			Kind kind = switch (o.get("kind").getAsString()) {
				case "lod" -> Kind.LOD;
				case "props" -> Kind.PROPS;
				case "always" -> Kind.ALWAYS;
				default -> Kind.CITY;
			};
			Tile t = new Tile(o.get("name").getAsString(), dir.resolve(o.get("file").getAsString()), kind,
					new Box(b[0], b[1], b[2], b[3], b[4], b[5]));
			tiles.add(t);
			if (kind == Kind.CITY) {
				city.put(t.name, t);
			} else if (kind == Kind.LOD) {
				// the tile it stands in for: its own name, or "tile" for a landmark's far version (Ferris wheel)
				lods.put(t, o.has("tile") ? o.get("tile").getAsString() : t.name);
			}
		}
		lods.forEach((l, owner) -> {
			Tile c = city.get(owner);
			if (c != null) {
				fullOf.put(l, c);
			}
		});
	}

	public void unload() {
		freeAll();
		tiles.clear();
		fullOf.clear();
		for (Light l : lights.values()) {
			l.sky.forEach(b -> b.buffer.close());
			l.sky.clear();
		}
		missing = 1;
		loaded = false;
		lastScan = null;
		skyBuilt = false;
		trains.unload();
		cars.unload();
		// free the converted textures' video memory when leaving the world
		TextureManager tm = MinecraftClient.getInstance().getTextureManager();
		for (String id : textures) {
			tm.destroyTexture(key(id));
		}
		textures.clear();
	}

	private static float[] floats(JsonArray a) {
		return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
	}

	/** The time of day the client world shows now. */
	public static TimeOfDay now() {
		var world = MinecraftClient.getInstance().world;
		return world == null ? TimeOfDay.NOON : TimeOfDay.of(world.getTimeOfDay());
	}

	private Light light() {
		return lights.get(now());
	}

	/**
	 * One builder for the whole session. A BufferBuilder holds native memory that 1.18.2 never frees, so creating one
	 * per frame (as v0.1 did) leaked about 1 MB every frame.
	 */
	private BufferBuilder builder() {
		if (builder == null) {
			builder = new BufferBuilder(1 << 20);
		}
		return builder;
	}

	private void freeAll() {
		for (Tile t : tiles) {
			free(t);
		}
	}

	/** Takes a tile off the GPU and forgets its meshes (they are read again when it comes near). */
	private static void free(Tile t) {
		for (GpuBatch g : t.gpu) {
			if (g != null) {
				g.buffer.close();
			}
		}
		t.gpu.clear();
		t.cpu = null;
		t.reading = null;
		t.ready = false;
	}

	/** Horizontal distance from the camera to a box (0 inside it). */
	private static double distance(Box b, Vec3d cam) {
		double dx = Math.max(0, Math.max(b.minX - cam.x, cam.x - b.maxX));
		double dz = Math.max(0, Math.max(b.minZ - cam.z, cam.z - b.maxZ));
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static double distance(Lcum.Batch b, Vec3d cam) {
		double dx = Math.max(0, Math.max(b.minX() - cam.x, cam.x - b.maxX()));
		double dz = Math.max(0, Math.max(b.minZ() - cam.z, cam.z - b.maxZ()));
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** How far from the camera a tile of this kind is loaded. */
	private static double radius(Kind kind) {
		return switch (kind) {
			case CITY -> LcuSettings.mapRadius();
			case PROPS -> LcuSettings.radius();
			case LOD, ALWAYS -> Double.POSITIVE_INFINITY;
		};
	}

	/** Whether the tile should be loaded with the camera here (always, for tiles whose bounds are not known yet). */
	private static boolean wanted(Tile t, Vec3d cam, double margin) {
		return t.bounds == null || distance(t.bounds, cam) <= radius(t.kind) + margin;
	}

	/**
	 * Keeps the tiles around the camera on the GPU: starts reading the nearest missing ones from disk, uploads what
	 * was read within the per-frame budget, and frees the ones left far behind.
	 */
	private void upload() {
		if (!loaded) {
			return;
		}
		boolean normals = IrisCompat.shaderPackInUse();
		if (normals != uploadedWithNormals) {
			freeAll();
			lastScan = null;
		}
		uploadedWithNormals = normals;
		if (!skyBuilt) {
			for (Light l : lights.values()) {
				buildSky(l);
			}
			skyBuilt = true;
		}
		Vec3d cam = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
		boolean moved = lastScan == null || cam.squaredDistanceTo(lastScan) >= RESCAN * RESCAN
				|| LcuSettings.radius() != lastRadius || LcuSettings.mapRadius() != lastMapRadius;
		// nothing to do while everything near is loaded and the player has not moved a few blocks
		if (missing == 0 && !moved) {
			return;
		}
		if (moved) {
			lastScan = cam;
			lastRadius = LcuSettings.radius();
			lastMapRadius = LcuSettings.mapRadius();
		}
		List<Tile> want = new ArrayList<>();
		int reading = 0;
		for (Tile t : tiles) {
			boolean in = wanted(t, cam, 0);
			boolean active = t.reading != null || t.cpu != null || !t.gpu.isEmpty();
			if (active && !wanted(t, cam, UNLOAD_MARGIN)) {
				free(t);
			} else if (in && (!t.ready || t.kind == Kind.PROPS)) {
				want.add(t);
			}
			if (t.reading != null && !t.reading.isDone()) {
				reading++;
			}
		}
		want.sort(Comparator.comparingDouble(t -> t.bounds == null ? 0 : distance(t.bounds, cam)));
		int budget = UPLOAD_BUDGET;
		int left = 0;
		for (Tile t : want) {
			if (t.kind == Kind.PROPS) {
				left += uploadProps(t, cam, normals, budget);
				budget = Math.max(0, budget - lastUploaded);
				continue;
			}
			if (t.cpu == null && t.reading == null) {
				if (reading < READS) {
					t.reading = CompletableFuture.supplyAsync(() -> read(t));
					reading++;
				}
				left++;
				continue;
			}
			if (t.cpu == null && t.reading.isDone()) {
				t.cpu = t.reading.join();
				t.reading = null;
				if (t.bounds == null) {
					t.bounds = boundsOf(t.cpu);
				}
			}
			if (t.cpu == null) {
				left++;
				continue;
			}
			while (budget > 0 && t.gpu.size() < t.cpu.size()) {
				Lcum.Batch b = t.cpu.get(t.gpu.size());
				t.gpu.add(toGpu(b, normals));
				budget -= b.indices().length;
			}
			if (t.gpu.size() == t.cpu.size()) {
				t.ready = true;
				t.cpu = null; // on the GPU now; read again from disk if it is ever needed again
			} else {
				left++;
			}
		}
		missing = left;
	}

	private int lastUploaded;

	/** LEGO props: the file once, then its columns within the props radius. Returns 1 while something is missing. */
	private int uploadProps(Tile t, Vec3d cam, boolean normals, int budget) {
		lastUploaded = 0;
		if (t.cpu == null) {
			if (t.reading == null) {
				t.reading = CompletableFuture.supplyAsync(() -> Lcum.split(read(t), CELL));
				return 1;
			}
			if (!t.reading.isDone()) {
				return 1;
			}
			t.cpu = t.reading.join();
			t.reading = null;
			if (t.bounds == null) {
				t.bounds = boundsOf(t.cpu);
			}
			while (t.gpu.size() < t.cpu.size()) {
				t.gpu.add(null);
			}
		}
		double radius = LcuSettings.radius();
		int left = 0;
		for (int i = 0; i < t.cpu.size(); i++) {
			double d = distance(t.cpu.get(i), cam);
			GpuBatch g = t.gpu.get(i);
			if (g == null && d <= radius) {
				if (lastUploaded < budget) {
					t.gpu.set(i, toGpu(t.cpu.get(i), normals));
					lastUploaded += t.cpu.get(i).indices().length;
				} else {
					left = 1;
				}
			} else if (g != null && d > radius + UNLOAD_MARGIN) {
				g.buffer.close();
				t.gpu.set(i, null);
			}
		}
		t.ready = left == 0;
		return left;
	}

	private static List<Lcum.Batch> read(Tile t) {
		try {
			return Lcum.read(t.file);
		} catch (Exception e) {
			LcuCraft.LOG.warn("tile {} unreadable: {}", t.name, e.toString());
			return List.of();
		}
	}

	private static Box boundsOf(List<Lcum.Batch> batches) {
		if (batches.isEmpty()) {
			return new Box(0, 0, 0, 0, 0, 0);
		}
		Box b = null;
		for (Lcum.Batch x : batches) {
			Box o = new Box(x.minX(), x.minY(), x.minZ(), x.maxX(), x.maxY(), x.maxZ());
			b = b == null ? o : b.union(o);
		}
		return b;
	}

	/**
	 * One converted batch into a vertex buffer (normals only for Iris, see the class comment). Each vertex goes up
	 * once and the triangles get their own index list, half the video memory of three vertices per triangle.
	 */
	GpuBatch toGpu(Lcum.Batch b, boolean normals) {
		ByteBuffer v = b.vertices();
		int[] idx = b.indices();
		// split pieces (props columns) share their batch's vertices: send only the ones this piece uses
		int[] remap = new int[v.limit() / Lcum.STRIDE];
		Arrays.fill(remap, -1);
		int[] order = new int[remap.length];
		int n = 0;
		for (int i : idx) {
			if (remap[i] < 0) {
				remap[i] = n;
				order[n++] = i;
			}
		}
		BufferBuilder bb = builder();
		bb.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL);
		for (int k = 0; k < n; k++) {
			int o = order[k] * Lcum.STRIDE;
			float nx = normals ? v.get(o + 24) / 127f : 0, ny = normals ? v.get(o + 25) / 127f : 0,
					nz = normals ? v.get(o + 26) / 127f : 0;
			bb.vertex(v.getFloat(o), v.getFloat(o + 4), v.getFloat(o + 8),
					(v.get(o + 20) & 0xFF) / 255f, (v.get(o + 21) & 0xFF) / 255f, (v.get(o + 22) & 0xFF) / 255f,
					(v.get(o + 23) & 0xFF) / 255f,
					v.getFloat(o + 12), v.getFloat(o + 16), OverlayTexture.DEFAULT_UV, LIGHT, nx, ny, nz);
		}
		bb.end();
		VertexBuffer vb = new VertexBuffer();
		vb.upload(bb);
		VertexBufferAccessor acc = (VertexBufferAccessor) vb;
		ByteBuffer ib = MemoryUtil.memAlloc(idx.length * 4);
		try {
			for (int i : idx) {
				ib.putInt(remap[i]);
			}
			ib.flip();
			GlStateManager._glBindVertexArray(acc.lcucraft$vertexArray());
			RenderSystem.glBindBuffer(0x8893, acc::lcucraft$indexBuffer);   // GL_ELEMENT_ARRAY_BUFFER
			RenderSystem.glBufferData(0x8893, ib, 0x88E4);                 // GL_STATIC_DRAW
			VertexBuffer.unbindVertexArray();
		} finally {
			MemoryUtil.memFree(ib);
		}
		acc.lcucraft$setNoIndexBuffer(false);
		acc.lcucraft$setElementFormat(VertexFormat.IntType.INT);
		acc.lcucraft$setCount(idx.length);
		return new GpuBatch(texture(b.texture()), b.translucent(), b.interior(), vb,
				new Box(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()));
	}

	/** LEGO prop pieces on the GPU now (DevTest). */
	int loadedProps() {
		int n = 0;
		for (Tile t : tiles) {
			if (t.kind == Kind.PROPS) {
				for (GpuBatch g : t.gpu) {
					n += g != null ? 1 : 0;
				}
			}
		}
		return n;
	}

	/** Full-detail tiles on the GPU now / tiles of the city (DevTest). */
	String loadedTiles() {
		long city = tiles.stream().filter(t -> t.kind == Kind.CITY).count();
		long ready = tiles.stream().filter(t -> t.kind == Kind.CITY && t.ready).count();
		return ready + "/" + city;
	}

	boolean withNormals() {
		return uploadedWithNormals;
	}

	/** Sky layers back to front: horizon bowl (tinted with the lower fog colour), dome, clouds, sun, mountains. */
	private void buildSky(Light l) {
		Map<String, String> names = SkyNames.load();
		List<Lcum.Batch> order = new ArrayList<>(l.skyCpu);
		order.sort(Comparator.comparingInt(b -> SkyNames.order(names.getOrDefault(b.texture(), ""))));
		BufferBuilder bb = builder();
		for (Lcum.Batch b : order) {
			boolean bowl = b.texture().equals("white");
			// bowl, dome and cloud layer go onto one sphere, so with a depth-tested sky (Iris) nothing in the city is
			// behind them; draw order keeps them in front of each other
			boolean sphere = SkyNames.order(names.getOrDefault(b.texture(), "")) <= SkyNames.CLOUDS;
			bb.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_TEXTURE_COLOR);
			ByteBuffer v = b.vertices();
			for (int i : b.indices()) {
				int o = i * Lcum.STRIDE;
				float r = (v.get(o + 20) & 0xFF) / 255f, g = (v.get(o + 21) & 0xFF) / 255f, bl = (v.get(o + 22) & 0xFF) / 255f;
				if (bowl) {
					r *= l.lowerFog[0];
					g *= l.lowerFog[1];
					bl *= l.lowerFog[2];
				}
				float x = v.getFloat(o), y = v.getFloat(o + 4), z = v.getFloat(o + 8);
				if (sphere) {
					float len = (float) Math.sqrt(x * x + y * y + z * z);
					if (len > 1) {
						x *= SKY_RADIUS / len;
						y *= SKY_RADIUS / len;
						z *= SKY_RADIUS / len;
					}
				}
				bb.vertex(x, y, z).texture(v.getFloat(o + 12), v.getFloat(o + 16)).color(r, g, bl, 1f).next();
			}
			bb.end();
			VertexBuffer vb = new VertexBuffer();
			vb.upload(bb);
			l.sky.add(new GpuBatch(texture(b.texture()), false, false, vb, null));
		}
	}

	/** Texture id "colour" or "colour+normal" (the material's normal map, handed to Iris) -> registered texture. */
	private Identifier texture(String id) {
		if (id.isEmpty()) {
			id = "white";  // untextured parts (some car models)
		}
		Identifier key = key(id);
		if (textures.add(id)) {
			TextureManager tm = MinecraftClient.getInstance().getTextureManager();
			Path dir = LcuData.dir().resolve("textures");
			int plus = id.indexOf('+');
			try {
				tm.registerTexture(key, plus < 0 ? new LcuTexture(dir.resolve(id + ".png"))
						: new LcuTexture(dir.resolve(id.substring(0, plus) + ".png"), dir.resolve(id.substring(plus + 1) + ".png")));
			} catch (Exception e) {
				LcuCraft.LOG.warn("texture {} missing", id);
			}
		}
		return key;
	}

	private static Identifier key(String id) {
		return new Identifier(LcuCraft.ID, "lcu/" + id.replace("+", "/n/"));  // '+' is not allowed in identifiers
	}

	/** The GPU batches to draw this frame: full tiles where they are ready, the game's far version elsewhere. */
	private void collect(Vec3d cam, boolean shadow, List<GpuBatch> out) {
		double props = LcuSettings.radius();
		for (Tile t : tiles) {
			switch (t.kind) {
				case CITY, ALWAYS -> {
					if (t.ready) {
						out.addAll(t.gpu);
					}
				}
				case LOD -> {
					// the far version stands in until its full tile is on the GPU; never in the shadow map (too far)
					if (!shadow && t.ready && !cityReady(t)) {
						out.addAll(t.gpu);
					}
				}
				case PROPS -> {
					for (int i = 0; i < t.gpu.size(); i++) {
						GpuBatch g = t.gpu.get(i);
						if (g != null && t.cpu != null && distance(t.cpu.get(i), cam) <= props) {
							out.add(g);
						}
					}
				}
			}
		}
	}

	/** lod tile -> its full tile is ready. */
	private boolean cityReady(Tile lod) {
		Tile c = fullOf.get(lod);
		return c != null && c.ready;
	}

	private final Map<Tile, Tile> fullOf = new HashMap<>();

	/** WorldRenderEvents.AFTER_ENTITIES */
	public void renderWorld(WorldRenderContext ctx) {
		upload();
		if (!loaded || perfNoCity) {
			return;
		}
		Vec3d cam = ctx.camera().getPos();
		MatrixStack ms = ctx.matrixStack();
		ms.push();
		ms.translate(-cam.x, -cam.y, -cam.z);
		Matrix4f view = ms.peek().getPositionMatrix();
		Matrix4f proj = ctx.projectionMatrix();
		modulate(false);
		List<GpuBatch> draw = new ArrayList<>();
		collect(cam, false, draw);
		List<GpuBatch> glass = new ArrayList<>();
		Map<Identifier, List<GpuBatch>> solid = new HashMap<>(), interior = new HashMap<>();
		for (GpuBatch b : draw) {
			if (ctx.frustum() != null && !ctx.frustum().isVisible(b.bounds)) {
				continue;
			}
			if (b.translucent) {
				glass.add(b);
				continue;
			}
			(b.interior ? interior : solid).computeIfAbsent(b.texture, k -> new ArrayList<>()).add(b);
		}
		// one shader set-up per texture instead of per batch: the city is thousands of batches
		solid.forEach((tex, list) -> drawAll(RenderLayer.getEntityCutoutNoCull(tex), list, view, proj));
		modulate(true);
		interior.forEach((tex, list) -> drawAll(RenderLayer.getEntityCutoutNoCull(tex), list, view, proj));
		modulate(false);
		if (!perfNoTrains) {
			trains.render(ms, cam, proj, false);
		}
		cars.render(ms, cam, proj, false);
		if (perfNoGlass) {
			glass.clear();
		}
		// glass after everything solid, far to near, blended (the game's windows are 50-70 % see-through)
		glass.sort(Comparator.comparingDouble((GpuBatch b) -> -b.bounds.getCenter().squaredDistanceTo(cam)));
		for (GpuBatch b : glass) {
			modulate(b.interior);
			draw(RenderLayer.getEntityTranslucent(b.texture), b, view, proj);
		}
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		ms.pop();
	}

	/** extra blocks around the shader pack's shadow area: its 8-block snapping and casters just outside it */
	private static final double SHADOW_MARGIN = 12;
	private static java.lang.reflect.Field shadowModelView;

	/**
	 * Iris shadow pass (IrisShadowRendererMixin): the solid and cut-out meshes near the camera, with Iris's shadow
	 * model-view. The shader pack projects them along the game's sun direction itself (shadow.vsh).
	 */
	public void renderShadowPass() {
		shadowPasses++;
		if (!loaded || perfNoShadow || perfNoCity) {
			return;
		}
		Matrix4f base;
		try {
			if (shadowModelView == null) {
				shadowModelView = Class.forName("net.coderbot.iris.pipeline.ShadowRenderer").getField("MODELVIEW");
			}
			base = (Matrix4f) shadowModelView.get(null);
		} catch (ReflectiveOperationException | ClassCastException e) {
			return;
		}
		if (base == null) {
			return;
		}
		Vec3d cam = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
		Matrix4f view = base.copy();
		view.multiply(Matrix4f.translate((float) -cam.x, (float) -cam.y, (float) -cam.z));
		Matrix4f proj = RenderSystem.getProjectionMatrix();
		MatrixStack shadowStack = new MatrixStack();
		shadowStack.peek().getPositionMatrix().load(base);
		shadowStack.translate(-cam.x, -cam.y, -cam.z);
		trains.render(shadowStack, cam, proj, true);
		cars.render(shadowStack, cam, proj, true);
		// only what lands in the shadow map: the pack covers SHADOW_HALF blocks either way across the sun direction
		// (common.glsl lcuShadowClip), so test each piece's box on those two axes
		double half = ShaderOptions.shadowHalf() + SHADOW_MARGIN;
		float[] L = light().sunDir;
		Vec3d l = new Vec3d(L[0], L[1], L[2]).normalize();
		Vec3d up = Math.abs(l.y) > 0.99 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
		Vec3d r = up.crossProduct(l).normalize(), u = l.crossProduct(r);
		List<GpuBatch> draw = new ArrayList<>();
		collect(cam, true, draw);
		Map<Identifier, List<GpuBatch>> byTexture = new HashMap<>();
		for (GpuBatch b : draw) {
			if (!b.translucent && inShadow(b.bounds, cam, r, u, half)) {
				byTexture.computeIfAbsent(b.texture, k -> new ArrayList<>()).add(b);
			}
		}
		byTexture.forEach((tex, list) -> drawAll(RenderLayer.getEntityCutoutNoCull(tex), list, view, proj));
	}

	private static boolean inShadow(Box b, Vec3d cam, Vec3d r, Vec3d u, double half) {
		Vec3d c = b.getCenter().subtract(cam);
		double ex = b.getXLength() / 2, ey = b.getYLength() / 2, ez = b.getZLength() / 2;
		return Math.abs(c.dotProduct(r)) <= half + ex * Math.abs(r.x) + ey * Math.abs(r.y) + ez * Math.abs(r.z)
				&& Math.abs(c.dotProduct(u)) <= half + ex * Math.abs(u.x) + ey * Math.abs(u.y) + ez * Math.abs(u.z);
	}

	/**
	 * Without a shader pack: the colour modulator that turns the halved, 0.4-lit vertex colours back into the
	 * game's (see the class comment); interiors keep their own baked light, without the time-of-day grade.
	 */
	private void modulate(boolean interior) {
		if (!uploadedWithNormals) {
			float[] g = interior ? new float[]{1, 1, 1} : light().grade;
			float k = 5f * EXPOSURE;
			RenderSystem.setShaderColor(k * g[0], k * g[1], k * g[2], 2f);
		}
	}

	/**
	 * Draws batches that share a render layer with one shader set-up: the uniforms VertexBuffer.setShader would
	 * upload for every batch are set once, then each buffer is bound and drawn.
	 */
	static void drawAll(RenderLayer layer, List<GpuBatch> batches, Matrix4f view, Matrix4f proj) {
		layer.startDrawing();
		Shader shader = RenderSystem.getShader();
		if (shader == null) {
			layer.endDrawing();
			return;
		}
		for (int i = 0; i < 12; i++) {
			shader.addSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
		}
		if (shader.modelViewMat != null) {
			shader.modelViewMat.set(view);
		}
		if (shader.projectionMat != null) {
			shader.projectionMat.set(proj);
		}
		if (shader.viewRotationMat != null) {
			shader.viewRotationMat.method_39978(RenderSystem.getInverseViewRotationMatrix());
		}
		if (shader.colorModulator != null) {
			shader.colorModulator.set(RenderSystem.getShaderColor());
		}
		if (shader.fogStart != null) {
			shader.fogStart.set(RenderSystem.getShaderFogStart());
		}
		if (shader.fogEnd != null) {
			shader.fogEnd.set(RenderSystem.getShaderFogEnd());
		}
		if (shader.fogColor != null) {
			shader.fogColor.set(RenderSystem.getShaderFogColor());
		}
		if (shader.fogShape != null) {
			shader.fogShape.set(RenderSystem.getShaderFogShape().getId());
		}
		if (shader.textureMat != null) {
			shader.textureMat.set(RenderSystem.getTextureMatrix());
		}
		if (shader.gameTime != null) {
			shader.gameTime.set(RenderSystem.getShaderGameTime());
		}
		if (shader.screenSize != null) {
			var w = MinecraftClient.getInstance().getWindow();
			shader.screenSize.set((float) w.getFramebufferWidth(), (float) w.getFramebufferHeight());
		}
		RenderSystem.setupShaderLights(shader);
		net.minecraft.client.render.BufferRenderer.unbindAll();  // its cache of bound buffers would go stale
		shader.bind();
		for (GpuBatch b : batches) {
			GlStateManager._glBindVertexArray(((VertexBufferAccessor) b.buffer).lcucraft$vertexArray());
			b.buffer.bind();
			b.buffer.getVertexFormat().startDrawing();
			b.buffer.drawElements();
		}
		shader.unbind();
		VertexBuffer.unbind();
		VertexBuffer.unbindVertexArray();
		layer.endDrawing();
	}

	static void draw(RenderLayer layer, GpuBatch b, Matrix4f view, Matrix4f proj) {
		layer.startDrawing();
		Shader shader = RenderSystem.getShader();
		b.buffer.setShader(view, proj, shader);
		layer.endDrawing();
	}

	/** Replaces the vanilla sky (WorldRenderer.renderSky). Returns false if there is nothing to draw yet. */
	public boolean renderSky(MatrixStack matrices, Matrix4f proj, Camera camera) {
		List<GpuBatch> sky = light().sky;
		if (sky.isEmpty()) {
			return false;
		}
		// just inside the far plane, behind the farthest tiles of the city
		float s = FAR_PLANE * 0.9f / SKY_RADIUS;
		matrices.push();
		matrices.scale(s, s, s);
		Matrix4f view = matrices.peek().getPositionMatrix();
		RenderSystem.depthMask(false);
		RenderSystem.disableDepthTest();
		RenderSystem.disableCull();
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
		for (GpuBatch b : sky) {
			RenderSystem.setShaderTexture(0, b.texture);
			b.buffer.setShader(view, proj, RenderSystem.getShader());
		}
		RenderSystem.disableBlend();
		RenderSystem.enableCull();
		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(true);
		matrices.pop();
		return true;
	}

	public float[] fogColour() {
		return light().fog;
	}

	public float fogFar() {
		return light().fogFar;
	}
}
