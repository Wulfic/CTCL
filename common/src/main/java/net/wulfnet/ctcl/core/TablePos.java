package net.wulfnet.ctcl.core;

/** A crafting table's block position. Pure logic: the Minecraft side converts to and from {@code BlockPos}. */
public record TablePos(int x, int y, int z) {
	public int chunkX() {
		return ChunkArea.chunkOf(x);
	}

	public int chunkZ() {
		return ChunkArea.chunkOf(z);
	}
}
