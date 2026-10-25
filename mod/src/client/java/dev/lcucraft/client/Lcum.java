package dev.lcucraft.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reader for the converter's .lcum mesh files (see export_lcu.py write_lcum). */
public final class Lcum {
	public static final int STRIDE = 28; // 3f pos, 2f uv, 4u8 colour, 4i8 normal

	/** interior: lit only by its own baked light (flag 4, export_lcu.write_lcum) */
	public record Batch(String texture, boolean cutout, boolean translucent, boolean interior, ByteBuffer vertices, int[] indices,
			float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
	}

	private Lcum() {
	}

	public static List<Batch> read(Path file) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
		if (b.getInt() != 0x4D55434C) { // "LCUM"
			throw new IOException("not a mesh file: " + file);
		}
		b.getInt(); // version
		int n = b.getInt();
		List<Batch> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			byte[] name = new byte[b.getShort() & 0xFFFF];
			b.get(name);
			int flags = b.getInt(), nv = b.getInt(), ni = b.getInt();
			ByteBuffer v = b.slice(b.position(), nv * STRIDE).order(ByteOrder.LITTLE_ENDIAN);
			b.position(b.position() + nv * STRIDE);
			int[] idx = new int[ni];
			b.asIntBuffer().get(idx);
			b.position(b.position() + ni * 4);
			float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
			float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
			for (int k = 0; k < nv; k++) {
				for (int a = 0; a < 3; a++) {
					float f = v.getFloat(k * STRIDE + a * 4);
					lo[a] = Math.min(lo[a], f);
					hi[a] = Math.max(hi[a], f);
				}
			}
			out.add(new Batch(new String(name, StandardCharsets.UTF_8), (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, v, idx,
					lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]));
		}
		return out;
	}

	/**
	 * Cuts batches into CELL x CELL block columns (by triangle centre) so the renderer can load and draw the map
	 * around the player only. The pieces share their batch's vertex buffer.
	 */
	public static List<Batch> split(List<Batch> in, float cell) {
		List<Batch> out = new ArrayList<>();
		for (Batch b : in) {
			ByteBuffer v = b.vertices();
			int[] idx = b.indices();
			java.util.Map<Long, it.unimi.dsi.fastutil.ints.IntArrayList> parts = new java.util.LinkedHashMap<>();
			for (int t = 0; t + 2 < idx.length; t += 3) {
				float cx = 0, cz = 0;
				for (int k = 0; k < 3; k++) {
					cx += v.getFloat(idx[t + k] * STRIDE);
					cz += v.getFloat(idx[t + k] * STRIDE + 8);
				}
				long key = (long) Math.floor(cx / 3 / cell) << 32 | ((long) Math.floor(cz / 3 / cell) & 0xFFFFFFFFL);
				it.unimi.dsi.fastutil.ints.IntArrayList part = parts.computeIfAbsent(key, k -> new it.unimi.dsi.fastutil.ints.IntArrayList());
				part.addElements(part.size(), idx, t, 3);
			}
			for (it.unimi.dsi.fastutil.ints.IntArrayList part : parts.values()) {
				int[] pi = part.toIntArray();
				float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
				float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
				for (int i : pi) {
					for (int a = 0; a < 3; a++) {
						float f = v.getFloat(i * STRIDE + a * 4);
						lo[a] = Math.min(lo[a], f);
						hi[a] = Math.max(hi[a], f);
					}
				}
				out.add(new Batch(b.texture(), b.cutout(), b.translucent(), b.interior(), v, pi, lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]));
			}
		}
		return out;
	}
}
