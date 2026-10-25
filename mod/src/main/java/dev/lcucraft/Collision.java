package dev.lcucraft;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where versions before 0.4 placed invisible barrier blocks (collision.bin, export_lcu.py). Collision now comes from
 * the real triangles (MeshCollision); this list only removes those old barriers from existing worlds.
 */
public final class Collision {
	private static final BlockState BARRIER = Blocks.BARRIER.getDefaultState();
	private static final BlockState AIR = Blocks.AIR.getDefaultState();
	/** chunk key -> packed entries (x << 4 | z, y) */
	private final Long2ObjectOpenHashMap<int[]> chunks = new Long2ObjectOpenHashMap<>();

	public static Collision load(Path file) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
		if (b.getInt() != 0x4355434C) { // "LCUC"
			throw new IOException("not a collision file: " + file);
		}
		b.getInt(); // version
		Collision c = new Collision();
		int n = b.getInt();
		for (int i = 0; i < n; i++) {
			int cx = b.getInt(), cz = b.getInt(), count = b.getInt();
			int[] e = new int[count];
			for (int k = 0; k < count; k++) {
				int xz = b.get() & 0xFF;
				e[k] = xz << 16 | (b.getShort() & 0xFFFF);
			}
			c.chunks.put(ChunkPos.toLong(cx, cz), e);
		}
		return c;
	}

	public int chunkCount() {
		return chunks.size();
	}

	/** Removes the chunk's old barriers; returns how many blocks changed. */
	public int removeBarriers(WorldChunk chunk) {
		int[] e = chunks.get(chunk.getPos().toLong());
		if (e == null) {
			return 0;
		}
		int bx = chunk.getPos().getStartX(), bz = chunk.getPos().getStartZ();
		int bottom = chunk.getBottomY(), top = chunk.getTopY();
		BlockPos.Mutable p = new BlockPos.Mutable();
		int changed = 0;
		for (int v : e) {
			int y = (short) (v & 0xFFFF);
			if (y < bottom || y >= top) {
				continue;
			}
			int xz = v >>> 16;
			p.set(bx + (xz >> 4), y, bz + (xz & 15));
			if (chunk.getBlockState(p) == BARRIER) {
				chunk.setBlockState(p, AIR, false);
				changed++;
			}
		}
		if (changed > 0) {
			chunk.setNeedsSaving(true);
		}
		return changed;
	}
}
