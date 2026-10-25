package dev.lcucraft;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collision against the city's real triangles (collision_mesh.bin, export_lcu.py) instead of barrier blocks.
 *
 * Minecraft only collides boxes, so the triangles around a query are rasterised into a fine grid (0.25 block) per
 * 16-block section, on demand and cached. Stairs and ramps become 0.25-high steps that the normal step-up climbs
 * smoothly, and nothing is a block, so nothing can be broken.
 */
public final class MeshCollision {
	public static final double CELL = 0.25;
	private static final int N = 64;                // cells per section side (16 / 0.25)
	private static final double SAMPLE = CELL / 2;  // surface sampling step: no gaps between cells
	private static final int CACHE = 768;           // sections kept (32 KB each)

	private final float[] tri;                      // 9 floats per triangle
	private final Long2ObjectOpenHashMap<int[]> bySection = new Long2ObjectOpenHashMap<>();
	private final Map<Long, long[]> cache = new LinkedHashMap<>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, long[]> e) {
			return size() > CACHE;
		}
	};

	private MeshCollision(float[] tri) {
		this.tri = tri;
		Long2ObjectOpenHashMap<IntArrayList> lists = new Long2ObjectOpenHashMap<>();
		for (int t = 0; t < tri.length / 9; t++) {
			int o = t * 9;
			int x0 = sec(min3(tri[o], tri[o + 3], tri[o + 6])), x1 = sec(max3(tri[o], tri[o + 3], tri[o + 6]));
			int y0 = sec(min3(tri[o + 1], tri[o + 4], tri[o + 7])), y1 = sec(max3(tri[o + 1], tri[o + 4], tri[o + 7]));
			int z0 = sec(min3(tri[o + 2], tri[o + 5], tri[o + 8])), z1 = sec(max3(tri[o + 2], tri[o + 5], tri[o + 8]));
			for (int x = x0; x <= x1; x++) {
				for (int y = y0; y <= y1; y++) {
					for (int z = z0; z <= z1; z++) {
						lists.computeIfAbsent(key(x, y, z), k -> new IntArrayList()).add(t);
					}
				}
			}
		}
		lists.forEach((k, v) -> bySection.put((long) k, v.toIntArray()));
	}

	public static MeshCollision load(Path file) throws IOException {
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
			ByteBuffer b = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN);
			if (b.getInt() != 0x5455434C) { // "LCUT"
				throw new IOException("not a collision mesh: " + file);
			}
			b.getInt(); // version
			int n = b.getInt();
			float[] t = new float[n * 9];
			FloatBuffer fb = b.asFloatBuffer();
			fb.get(t);
			return new MeshCollision(t);
		}
	}

	public int triangles() {
		return tri.length / 9;
	}

	/** Collision boxes inside the query box (world coordinates). */
	public List<VoxelShape> shapes(Box q) {
		int cx0 = cell(q.minX), cx1 = cell(q.maxX), cy0 = cell(q.minY), cy1 = cell(q.maxY), cz0 = cell(q.minZ), cz1 = cell(q.maxZ);
		List<VoxelShape> out = new ArrayList<>();
		for (int cy = cy0; cy <= cy1; cy++) {
			for (int cz = cz0; cz <= cz1; cz++) {
				int run = Integer.MIN_VALUE;
				for (int cx = cx0; cx <= cx1 + 1; cx++) {
					boolean solid = cx <= cx1 && solid(cx, cy, cz);
					if (solid && run == Integer.MIN_VALUE) {
						run = cx;
					} else if (!solid && run != Integer.MIN_VALUE) {
						// merge a row of cells into one box
						out.add(VoxelShapes.cuboid(run * CELL, cy * CELL, cz * CELL, cx * CELL, (cy + 1) * CELL, (cz + 1) * CELL));
						run = Integer.MIN_VALUE;
					}
				}
			}
		}
		return out;
	}

	private boolean solid(int cx, int cy, int cz) {
		long[] bits = section(Math.floorDiv(cx, N), Math.floorDiv(cy, N), Math.floorDiv(cz, N));
		if (bits == null) {
			return false;
		}
		int i = (Math.floorMod(cy, N) * N + Math.floorMod(cz, N)) * N + Math.floorMod(cx, N);
		return (bits[i >>> 6] & (1L << (i & 63))) != 0;
	}

	private long[] section(int sx, int sy, int sz) {
		long k = key(sx, sy, sz);
		int[] list = bySection.get(k);
		if (list == null) {
			return null;
		}
		synchronized (cache) {  // the integrated server and the client both ask
			long[] bits = cache.get(k);
			if (bits == null) {
				bits = rasterise(list, sx * 16, sy * 16, sz * 16);
				cache.put(k, bits);
			}
			return bits;
		}
	}

	/** Marks every cell of the section that a triangle passes through (barycentric samples, SAMPLE apart). */
	private long[] rasterise(int[] list, int ox, int oy, int oz) {
		long[] bits = new long[N * N * N / 64];
		for (int t : list) {
			int o = t * 9;
			float ax = tri[o], ay = tri[o + 1], az = tri[o + 2];
			float ux = tri[o + 3] - ax, uy = tri[o + 4] - ay, uz = tri[o + 5] - az;
			float vx = tri[o + 6] - ax, vy = tri[o + 7] - ay, vz = tri[o + 8] - az;
			double edge = Math.max(Math.max(len(ux, uy, uz), len(vx, vy, vz)), len(vx - ux, vy - uy, vz - uz));
			int steps = Math.max(1, (int) Math.ceil(edge / SAMPLE));
			for (int i = 0; i <= steps; i++) {
				for (int j = 0; i + j <= steps; j++) {
					double s = (double) i / steps, r = (double) j / steps;
					int x = (int) Math.floor((ax + s * ux + r * vx - ox) / CELL);
					int y = (int) Math.floor((ay + s * uy + r * vy - oy) / CELL);
					int z = (int) Math.floor((az + s * uz + r * vz - oz) / CELL);
					if (x < 0 || y < 0 || z < 0 || x >= N || y >= N || z >= N) {
						continue;
					}
					int idx = (y * N + z) * N + x;
					bits[idx >>> 6] |= 1L << (idx & 63);
				}
			}
		}
		return bits;
	}

	private static double len(double x, double y, double z) {
		return Math.sqrt(x * x + y * y + z * z);
	}

	private static int cell(double v) {
		return (int) Math.floor(v / CELL);
	}

	private static int sec(float v) {
		return Math.floorDiv((int) Math.floor(v), 16);
	}

	private static float min3(float a, float b, float c) {
		return Math.min(a, Math.min(b, c));
	}

	private static float max3(float a, float b, float c) {
		return Math.max(a, Math.max(b, c));
	}

	private static long key(int x, int y, int z) {
		return ((long) x & 0x3FFFFF) << 42 | ((long) y & 0xFFFFF) << 22 | ((long) z & 0x3FFFFF);
	}
}
