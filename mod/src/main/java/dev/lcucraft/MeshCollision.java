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
 *
 * The whole city has tens of millions of triangles, so the file stays memory-mapped (outside the Java heap) and is
 * grouped in REGION x REGION block columns: only the columns players walk in get a section index, and the least
 * recently used ones are dropped again. Version 1 files (one district, no columns) are one big column.
 */
public final class MeshCollision {
	public static final double CELL = 0.25;
	private static final int N = 64;                // cells per section side (16 / 0.25)
	private static final double SAMPLE = CELL / 2;  // surface sampling step: no gaps between cells
	private static final int CACHE = 768;           // sections kept (32 KB each)
	private static final int REGIONS = 96;          // column indexes kept

	private final FloatBuffer tri;                  // 9 floats per triangle, memory-mapped
	private final int region;                       // column size in blocks, 0 = the whole file is one column
	/** column key -> first triangle, triangle count */
	private final Long2ObjectOpenHashMap<int[]> columns = new Long2ObjectOpenHashMap<>();
	private final int triangles;
	/** column key -> its sections' triangle lists (section key -> triangle numbers) */
	private final Map<Long, Long2ObjectOpenHashMap<int[]>> indexes = new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Long2ObjectOpenHashMap<int[]>> e) {
			return size() > REGIONS;
		}
	};
	private final Map<Long, long[]> cache = new LinkedHashMap<>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, long[]> e) {
			return size() > CACHE;
		}
	};

	private MeshCollision(FloatBuffer tri, int region, int triangles) {
		this.tri = tri;
		this.region = region;
		this.triangles = triangles;
	}

	public static MeshCollision load(Path file) throws IOException {
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
			ByteBuffer b = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.LITTLE_ENDIAN);
			if (b.getInt() != 0x5455434C) { // "LCUT"
				throw new IOException("not a collision mesh: " + file);
			}
			int version = b.getInt();
			if (version == 1) {
				int n = b.getInt();
				MeshCollision m = new MeshCollision(b.slice(b.position(), n * 36).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer(), 0, n);
				m.columns.put(0L, new int[]{0, n});
				return m;
			}
			int region = b.getInt(), count = b.getInt();
			int[][] table = new int[count][];
			long[] keys = new long[count];
			int total = 0;
			for (int i = 0; i < count; i++) {
				int rx = b.getInt(), rz = b.getInt(), first = b.getInt(), n = b.getInt();
				keys[i] = column(rx, rz);
				table[i] = new int[]{first, n};
				total = Math.max(total, first + n);
			}
			MeshCollision m = new MeshCollision(b.slice(b.position(), total * 36).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer(), region, total);
			for (int i = 0; i < count; i++) {
				m.columns.put(keys[i], table[i]);
			}
			return m;
		}
	}

	public int triangles() {
		return triangles;
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
		synchronized (cache) {  // the integrated server and the client both ask
			long[] bits = cache.get(k);
			if (bits == null) {
				int[] list = index(sx, sz).get(k);
				if (list == null) {
					return null;
				}
				bits = rasterise(list, sx * 16, sy * 16, sz * 16);
				cache.put(k, bits);
			}
			return bits;
		}
	}

	/** Section index of the column holding section (sx, sz), built on first use. */
	private Long2ObjectOpenHashMap<int[]> index(int sx, int sz) {
		int rx = region == 0 ? 0 : Math.floorDiv(sx * 16, region), rz = region == 0 ? 0 : Math.floorDiv(sz * 16, region);
		long c = region == 0 ? 0L : column(rx, rz);
		Long2ObjectOpenHashMap<int[]> idx = indexes.get(c);
		if (idx != null) {
			return idx;
		}
		idx = new Long2ObjectOpenHashMap<>();
		int[] range = columns.get(c);
		if (range != null) {
			// a triangle spanning columns is stored in each: keep only this column's sections
			int lo = region == 0 ? Integer.MIN_VALUE : rx * region / 16, hix = region == 0 ? Integer.MAX_VALUE : lo + region / 16 - 1;
			int loz = region == 0 ? Integer.MIN_VALUE : rz * region / 16, hiz = region == 0 ? Integer.MAX_VALUE : loz + region / 16 - 1;
			Long2ObjectOpenHashMap<IntArrayList> lists = new Long2ObjectOpenHashMap<>();
			for (int t = range[0]; t < range[0] + range[1]; t++) {
				int o = t * 9;
				float ax = tri.get(o), ay = tri.get(o + 1), az = tri.get(o + 2), bx = tri.get(o + 3), by = tri.get(o + 4),
						bz = tri.get(o + 5), qx = tri.get(o + 6), qy = tri.get(o + 7), qz = tri.get(o + 8);
				int x0 = Math.max(lo, sec(min3(ax, bx, qx))), x1 = Math.min(hix, sec(max3(ax, bx, qx)));
				int y0 = sec(min3(ay, by, qy)), y1 = sec(max3(ay, by, qy));
				int z0 = Math.max(loz, sec(min3(az, bz, qz))), z1 = Math.min(hiz, sec(max3(az, bz, qz)));
				for (int x = x0; x <= x1; x++) {
					for (int y = y0; y <= y1; y++) {
						for (int z = z0; z <= z1; z++) {
							lists.computeIfAbsent(key(x, y, z), k -> new IntArrayList()).add(t);
						}
					}
				}
			}
			Long2ObjectOpenHashMap<int[]> built = idx;
			lists.forEach((k, v) -> built.put((long) k, v.toIntArray()));
		}
		indexes.put(c, idx);
		return idx;
	}

	/** Marks every cell of the section that a triangle passes through (barycentric samples, SAMPLE apart). */
	private long[] rasterise(int[] list, int ox, int oy, int oz) {
		long[] bits = new long[N * N * N / 64];
		for (int t : list) {
			int o = t * 9;
			float ax = tri.get(o), ay = tri.get(o + 1), az = tri.get(o + 2);
			float ux = tri.get(o + 3) - ax, uy = tri.get(o + 4) - ay, uz = tri.get(o + 5) - az;
			float vx = tri.get(o + 6) - ax, vy = tri.get(o + 7) - ay, vz = tri.get(o + 8) - az;
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

	private static long column(int rx, int rz) {
		return (long) rx << 32 | (rz & 0xFFFFFFFFL);
	}

	private static long key(int x, int y, int z) {
		return ((long) x & 0x3FFFFF) << 42 | ((long) y & 0xFFFFF) << 22 | ((long) z & 0x3FFFFF);
	}
}
