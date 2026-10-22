package net.wulfnet.ctcl.core;

/**
 * An inclusive, axis-aligned square of chunk coordinates. Pure logic: no Minecraft imports.
 */
public record ChunkArea(int minX, int minZ, int maxX, int maxZ) {
	public static final ChunkArea EMPTY = new ChunkArea(0, 0, -1, -1);

	/** The square of {@code size} centred on the chunk containing block ({@code blockX}, {@code blockZ}). */
	public static ChunkArea around(int blockX, int blockZ, LoaderSize size) {
		if (size.isOff()) {
			return EMPTY;
		}
		int centerX = chunkOf(blockX);
		int centerZ = chunkOf(blockZ);
		int r = size.radius();
		return new ChunkArea(centerX - r, centerZ - r, centerX + r, centerZ + r);
	}

	/** The chunk coordinate containing a block coordinate. Floors, so block -1 is in chunk -1. */
	public static int chunkOf(int blockCoord) {
		return blockCoord >> 4;
	}

	public boolean isEmpty() {
		return maxX < minX || maxZ < minZ;
	}

	public int count() {
		return isEmpty() ? 0 : (maxX - minX + 1) * (maxZ - minZ + 1);
	}

	public boolean contains(int chunkX, int chunkZ) {
		return chunkX >= minX && chunkX <= maxX && chunkZ >= minZ && chunkZ <= maxZ;
	}
}
