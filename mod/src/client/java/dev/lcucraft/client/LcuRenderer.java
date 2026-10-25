package dev.lcucraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.lcucraft.LcuCraft;
import dev.lcucraft.LcuData;
import dev.lcucraft.TimeOfDay;
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

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Draws the converted Cherry Tree Hills meshes and the LCU sky for the current time of day (DAWN, NOON, DUSK).
 *
 * Mesh colours are stored halved (vertex alpha 128) so the game's light can go above 1. Without a shader pack the
 * meshes use Minecraft's entity cut-out shader with zero normals, which makes its fixed lighting a constant 0.4; a
 * colour modulator of 5 (2.5 to cancel the 0.4, 2 to undo the halving) times the exposure and the time-of-day grade
 * restores the game's colour. With an Iris shader pack the real normals are uploaded and the pack does the same from the alpha flag.
 */
public final class LcuRenderer {
	private static final int LIGHT = 0xF000F0;
	private static final int UPLOADS_PER_FRAME = 24;
	/** sky geometry radius in its own units (the NOON dome is ~5600 across) */
	private static final float SKY_RADIUS = 5600f;
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

	/** converted LEGO props (export_props.py): the only part limited by the load radius */
	private static final String PROPS_FILE = "zz_props.lcum";
	/** cpu[propStart..] are the props, cut into columns; everything before is the city, always loaded */
	private volatile int propStart = Integer.MAX_VALUE;
	/** columns the props are cut into for loading around the player (Lcum.split) */
	private static final float CELL = 48f;
	/** loaded pieces stay on the GPU until this far beyond the radius, so walking along the edge does not reload */
	private static final double UNLOAD_MARGIN = 64;
	private final List<Lcum.Batch> cpu = new ArrayList<>();
	/** GPU copy of cpu[i], or null while it is outside the load radius */
	private GpuBatch[] slots = new GpuBatch[0];
	private int missing = 1;
	/** where and with which radius the pieces were last checked against the load radius */
	private Vec3d lastScan;
	private double lastRadius;
	private static final double RESCAN = 4;
	private final Map<TimeOfDay, Light> lights = new EnumMap<>(TimeOfDay.class);
	private final Set<String> textures = new HashSet<>();
	private CompletableFuture<List<Lcum.Batch>> loading;
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

	/** All batches are on the GPU. */
	public boolean uploaded() {
		return loading != null && !cpu.isEmpty() && missing == 0 && skyBuilt;
	}

	/** Starts reading the converted files in the background. */
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
			} catch (Exception e) {
				LcuCraft.LOG.warn("lighting for {} missing, using defaults", t, e);
			}
		}
		trains.load(dir);
		loading = CompletableFuture.supplyAsync(() -> {
			List<Lcum.Batch> all = new ArrayList<>();
			try (Stream<Path> files = Files.list(dir.resolve("meshes"))) {
				List<Lcum.Batch> props = new ArrayList<>();
				for (Path f : files.filter(p -> p.toString().endsWith(".lcum")).sorted().toList()) {
					(f.getFileName().toString().equals(PROPS_FILE) ? props : all).addAll(Lcum.read(f));
				}
				propStart = all.size();
				all.addAll(Lcum.split(props, CELL));
				for (TimeOfDay t : TimeOfDay.values()) {
					Path sky = dir.resolve(t.skyFile());
					lights.get(t).skyCpu = Lcum.read(Files.exists(sky) ? sky : dir.resolve("sky.lcum"));
				}
			} catch (Exception e) {
				throw new RuntimeException("Could not read converted meshes from " + dir, e);
			}
			return all;
		});
	}

	public void unload() {
		freeAll();
		slots = new GpuBatch[0];
		for (Light l : lights.values()) {
			l.sky.forEach(b -> b.buffer.close());
			l.sky.clear();
		}
		cpu.clear();
		missing = 1;
		propStart = Integer.MAX_VALUE;
		loading = null;
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
		for (int i = 0; i < slots.length; i++) {
			if (slots[i] != null) {
				slots[i].buffer.close();
				slots[i] = null;
			}
		}
	}

	/** Horizontal distance from the camera to a piece of the map (0 inside it). */
	private static double distance(Lcum.Batch b, Vec3d cam) {
		double dx = Math.max(0, Math.max(b.minX() - cam.x, cam.x - b.maxX()));
		double dz = Math.max(0, Math.max(b.minZ() - cam.z, cam.z - b.maxZ()));
		return Math.sqrt(dx * dx + dz * dz);
	}

	/**
	 * Keeps the map within the load radius (LCUxMinecraft Settings) on the GPU: nearest pieces first, a few per
	 * frame so moving does not freeze the game, and frees the ones left far behind.
	 */
	private void upload() {
		if (loading == null) {
			return;
		}
		boolean normals = IrisCompat.shaderPackInUse();
		if (normals != uploadedWithNormals) {
			freeAll();
			lastScan = null;
		}
		uploadedWithNormals = normals;
		if (cpu.isEmpty() && loading.isDone()) {
			try {
				cpu.addAll(loading.join());
			} catch (Exception e) {
				LcuCraft.LOG.error("Cherry Tree Hills meshes failed to load", e);
				loading = CompletableFuture.completedFuture(List.of());
				return;
			}
			slots = new GpuBatch[cpu.size()];
			for (Light l : lights.values()) {
				buildSky(l);
			}
			skyBuilt = true;
			LcuCraft.LOG.info("Cherry Tree Hills: {} mesh batches", cpu.size());
		}
		if (cpu.isEmpty()) {
			return;
		}
		Vec3d cam = MinecraftClient.getInstance().gameRenderer.getCamera().getPos();
		// nothing to do while everything near is loaded and the player has not moved a few blocks
		if (missing == 0 && lastScan != null && cam.squaredDistanceTo(lastScan) < RESCAN * RESCAN
				&& LcuSettings.radius() == lastRadius) {
			return;
		}
		lastScan = cam;
		lastRadius = LcuSettings.radius();
		List<Integer> want = new ArrayList<>();
		double[] dist = new double[cpu.size()];
		for (int i = 0; i < cpu.size(); i++) {
			double radius = radius(i);
			dist[i] = distance(cpu.get(i), cam);
			if (slots[i] == null && dist[i] <= radius) {
				want.add(i);
			} else if (slots[i] != null && dist[i] > radius + UNLOAD_MARGIN) {
				slots[i].buffer.close();
				slots[i] = null;
			}
		}
		missing = want.size();
		want.sort(Comparator.comparingDouble(i -> dist[i]));
		for (int n = 0; n < UPLOADS_PER_FRAME && n < want.size(); n++) {
			int i = want.get(n);
			slots[i] = toGpu(cpu.get(i), normals);
			missing--;
		}
	}

	/** The city is always loaded; the LEGO props only within the radius set in LCUxMinecraft Settings. */
	private double radius(int i) {
		return i < propStart ? Double.POSITIVE_INFINITY : LcuSettings.radius();
	}

	private boolean visible(int i, Vec3d cam) {
		return slots[i] != null && (i < propStart || distance(cpu.get(i), cam) <= LcuSettings.radius());
	}

	/** One converted batch into a vertex buffer (normals only for Iris, see the class comment). */
	GpuBatch toGpu(Lcum.Batch b, boolean normals) {
		BufferBuilder bb = builder();
		bb.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL);
		ByteBuffer v = b.vertices();
		for (int i : b.indices()) {
			int o = i * Lcum.STRIDE;
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
		return new GpuBatch(texture(b.texture()), b.translucent(), b.interior(), vb,
				new Box(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()));
	}

	/** LEGO prop pieces on the GPU now (DevTest). */
	int loadedProps() {
		int n = 0;
		for (int i = Math.min(propStart, slots.length); i < slots.length; i++) {
			n += slots[i] != null ? 1 : 0;
		}
		return n;
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

	/** WorldRenderEvents.AFTER_ENTITIES */
	public void renderWorld(WorldRenderContext ctx) {
		upload();
		if (slots.length == 0 || perfNoCity) {
			return;
		}
		Vec3d cam = ctx.camera().getPos();
		MatrixStack ms = ctx.matrixStack();
		ms.push();
		ms.translate(-cam.x, -cam.y, -cam.z);
		Matrix4f view = ms.peek().getPositionMatrix();
		Matrix4f proj = ctx.projectionMatrix();
		modulate(false);
		List<GpuBatch> glass = new ArrayList<>();
		for (int i = 0; i < slots.length; i++) {
			if (!visible(i, cam)) {
				continue;
			}
			GpuBatch b = slots[i];
			if (ctx.frustum() != null && !ctx.frustum().isVisible(b.bounds)) {
				continue;
			}
			if (b.translucent) {
				glass.add(b);
				continue;
			}
			if (b.interior) {
				modulate(true);
			}
			draw(RenderLayer.getEntityCutoutNoCull(b.texture), b, view, proj);
			if (b.interior) {
				modulate(false);
			}
		}
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
		if (slots.length == 0 || perfNoShadow || perfNoCity) {
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
		for (int i = 0; i < slots.length; i++) {
			GpuBatch b = slots[i];
			if (b != null && !b.translucent && inShadow(b.bounds, cam, r, u, half) && visible(i, cam)) {
				draw(RenderLayer.getEntityCutoutNoCull(b.texture), b, view, proj);
			}
		}
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
		// scale the sky just inside the far plane (render distance x 4)
		float far = MinecraftClient.getInstance().gameRenderer.getViewDistance() * 3.5f;
		float s = far / SKY_RADIUS;
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
