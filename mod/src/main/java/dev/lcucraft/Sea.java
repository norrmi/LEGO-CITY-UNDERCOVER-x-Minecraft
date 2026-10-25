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
 * Swimmable sea under the game's sea plane (water.bin, export_lcu.py): still water from each column's bottom up to
 * just below the plane, sand under it. Placed straight into the chunk without fluid ticks, so the edges of the area
 * never start flowing into the void.
 */
public final class Sea {
	private static final BlockState WATER = Blocks.WATER.getDefaultState();
	private static final BlockState SAND = Blocks.SAND.getDefaultState();
	private final Long2ObjectOpenHashMap<int[]> chunks = new Long2ObjectOpenHashMap<>();
	private int top, bottom;

	public static Sea load(Path file) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
		if (b.getInt() != 0x5755434C) { // "LCUW"
			throw new IOException("not a water file: " + file);
		}
		b.getInt(); // version
		Sea s = new Sea();
		int n = b.getInt();
		s.top = b.getInt();
		s.bottom = b.getInt();
		for (int i = 0; i < n; i++) {
			int cx = b.getInt(), cz = b.getInt(), count = b.getInt();
			int[] e = new int[count];
			for (int k = 0; k < count; k++) {
				int xz = b.get() & 0xFF;
				e[k] = xz << 16 | (b.getShort() & 0xFFFF);
			}
			s.chunks.put(ChunkPos.toLong(cx, cz), e);
		}
		return s;
	}

	/** Fills the chunk's sea columns (only air is replaced); returns how many blocks changed. */
	public int fill(WorldChunk chunk) {
		int[] e = chunks.get(chunk.getPos().toLong());
		if (e == null) {
			return 0;
		}
		int bx = chunk.getPos().getStartX(), bz = chunk.getPos().getStartZ();
		BlockPos.Mutable p = new BlockPos.Mutable();
		int changed = 0;
		for (int v : e) {
			int from = (short) (v & 0xFFFF), xz = v >>> 16;
			int x = bx + (xz >> 4), z = bz + (xz & 15);
			if (from <= bottom) {
				changed += set(chunk, p.set(x, bottom - 1, z), SAND);
			}
			for (int y = Math.max(from, bottom); y <= top; y++) {
				changed += set(chunk, p.set(x, y, z), WATER);
			}
		}
		if (changed > 0) {
			chunk.setNeedsSaving(true);
		}
		return changed;
	}

	private static int set(WorldChunk chunk, BlockPos p, BlockState s) {
		if (!chunk.getBlockState(p).isAir()) {
			return 0;
		}
		chunk.setBlockState(p, s, false);
		return 1;
	}
}
