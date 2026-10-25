package dev.lcucraft.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lcucraft.LcuCraft;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Cydonia train on the elevated railway (trains.json + trains/*.lcum, export_trains.py). It shuttles end to end
 * along the traced track, driven by the world time so every frame and the shadow pass agree. Each car sits on two
 * points of the track half its length ahead and behind, which bends the train through the curves.
 */
final class Trains {
	private record Car(String model, float length) {
	}

	private final LcuRenderer renderer;
	private float[] px, py, pz, arc;   // track points and the distance along it
	private final List<Car> cars = new ArrayList<>();
	private final Map<String, List<Lcum.Batch>> cpu = new HashMap<>();
	private Map<String, List<LcuRenderer.GpuBatch>> gpu;
	private float speed, gap, trainLength;
	/** middle car and its heading at the last frame, for DevTest */
	private static volatile double[] lastSeen = {0, 120, 0, 1, 0};

	Trains(LcuRenderer renderer) {
		this.renderer = renderer;
	}

	void load(Path dir) {
		unload();
		Path json = dir.resolve("trains.json");
		if (!Files.exists(json)) {
			return;
		}
		try {
			JsonObject o = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
			JsonArray track = o.getAsJsonArray("tracks").get(0).getAsJsonArray();
			int n = track.size();
			px = new float[n];
			py = new float[n];
			pz = new float[n];
			arc = new float[n];
			for (int i = 0; i < n; i++) {
				JsonArray p = track.get(i).getAsJsonArray();
				px[i] = p.get(0).getAsFloat();
				py[i] = p.get(1).getAsFloat();
				pz[i] = p.get(2).getAsFloat();
				if (i > 0) {
					arc[i] = arc[i - 1] + (float) Math.sqrt(sq(px[i] - px[i - 1]) + sq(py[i] - py[i - 1]) + sq(pz[i] - pz[i - 1]));
				}
			}
			speed = o.get("speed").getAsFloat();
			gap = o.get("gap").getAsFloat();
			for (JsonElement e : o.getAsJsonArray("cars")) {
				JsonObject c = e.getAsJsonObject();
				String model = c.get("model").getAsString();
				cars.add(new Car(model, c.get("length").getAsFloat()));
				trainLength += c.get("length").getAsFloat() + gap;
				if (!cpu.containsKey(model)) {
					cpu.put(model, Lcum.read(dir.resolve("trains").resolve(model + ".lcum")));
				}
			}
			trainLength -= gap;
		} catch (IOException | RuntimeException e) {
			LcuCraft.LOG.warn("trains not loaded: {}", e.toString());
			unload();
		}
	}

	void unload() {
		if (gpu != null) {
			gpu.values().forEach(l -> l.forEach(b -> b.buffer().close()));
		}
		gpu = null;
		cpu.clear();
		cars.clear();
		px = null;
		trainLength = 0;
	}

	/** ms maps world coordinates to the view (already translated by -camera); cut-outs only for the shadow pass. */
	void render(MatrixStack ms, Vec3d cam, Matrix4f proj, boolean shadow) {
		if (px == null || cars.isEmpty()) {
			return;
		}
		if (gpu == null) {
			gpu = new HashMap<>();
			cpu.forEach((m, l) -> gpu.put(m, l.stream().map(b -> renderer.toGpu(b, renderer.withNormals())).toList()));
		}
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.world == null) {
			return;
		}
		// ping-pong: front of the train runs 0..span and back, with a pause at each end
		float span = arc[arc.length - 1] - trainLength;
		float run = span / speed, pause = 8f;
		double t = (mc.world.getTime() + mc.getTickDelta()) / 20.0 % (2 * (run + pause));
		float u;
		if (t < run) {
			u = (float) (t * speed);
		} else if (t < run + pause) {
			u = span;
		} else if (t < 2 * run + pause) {
			u = span - (float) ((t - run - pause) * speed);
		} else {
			u = 0;
		}
		float s = u + trainLength;   // the first car (cab) leads towards the far end
		for (Car c : cars) {
			float[] a = at(s), b = at(s - c.length);
			s -= c.length + gap;
			float dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
			if (c == cars.get(cars.size() / 2) && !shadow) {
				lastSeen = new double[]{a[0], a[1], a[2], dx, dz};
			}
			if (cam.squaredDistanceTo(a[0], a[1], a[2]) > 256 * 256) {
				continue;
			}
			ms.push();
			ms.translate((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2);
			ms.multiply(Vec3f.POSITIVE_Y.getRadialQuaternion((float) Math.atan2(-dz, dx)));
			ms.multiply(Vec3f.POSITIVE_Z.getRadialQuaternion((float) Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
			Matrix4f view = ms.peek().getPositionMatrix();
			for (LcuRenderer.GpuBatch g : gpu.get(c.model)) {
				if (!g.translucent()) {
					LcuRenderer.draw(RenderLayer.getEntityCutoutNoCull(g.texture()), g, view, proj);
				} else if (!shadow) {
					LcuRenderer.draw(RenderLayer.getEntityTranslucent(g.texture()), g, view, proj);
				}
			}
			ms.pop();
		}
	}

	/** x, y, z, yaw, pitch 10 blocks to the side and 12 above of the train's middle, looking at it (DevTest). */
	static double[] viewpoint() {
		double[] p = lastSeen;
		double l = Math.max(1e-6, Math.hypot(p[3], p[4]));
		double nx = -p[4] / l, nz = p[3] / l;      // left of the heading
		double x = p[0] + nx * 10, z = p[2] + nz * 10, y = p[1] + 12;
		double yaw = Math.toDegrees(Math.atan2(-(p[0] - x), p[2] - z));
		return new double[]{x, y, z, yaw, 45};
	}

	/** Track point at distance d from the start. */
	private float[] at(float d) {
		int lo = 0, hi = arc.length - 1;
		d = Math.max(0, Math.min(arc[hi], d));
		while (hi - lo > 1) {
			int m = (lo + hi) >>> 1;
			if (arc[m] <= d) {
				lo = m;
			} else {
				hi = m;
			}
		}
		float f = arc[hi] > arc[lo] ? (d - arc[lo]) / (arc[hi] - arc[lo]) : 0;
		return new float[]{px[lo] + (px[hi] - px[lo]) * f, py[lo] + (py[hi] - py[lo]) * f, pz[lo] + (pz[hi] - pz[lo]) * f};
	}

	private static float sq(float v) {
		return v * v;
	}
}
