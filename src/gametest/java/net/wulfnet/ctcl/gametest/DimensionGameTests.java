package net.wulfnet.ctcl.gametest;

import static net.wulfnet.ctcl.gametest.GameTestSupport.service;
import static net.wulfnet.ctcl.gametest.GameTestSupport.setSize;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.wulfnet.ctcl.core.LoaderSize;

/**
 * Loaders work in the Nether and the End, where no player is present. The tables are left
 * at size 1 on purpose: they are the fixture the uninstall check (TODO 6.3) runs a vanilla
 * server against.
 */
public class DimensionGameTests {
	private static final List<ResourceKey<Level>> DIMENSIONS = List.of(Level.OVERWORLD, Level.NETHER, Level.END);
	private static final BlockPos POS = new BlockPos(-300_008, 64, 300_008);

	@GameTest(maxTicks = 200)
	public void aTableLoadsItsChunkInEveryDimension(GameTestHelper helper) {
		for (ResourceKey<Level> dimension : DIMENSIONS) {
			ServerLevel level = helper.getLevel().getServer().getLevel(dimension);
			helper.assertTrue(level != null, "missing dimension " + dimension);
			level.getChunk(POS);
			// Replacing a leftover table from an earlier run fires the removal hook, so each run starts from 0.
			level.setBlock(POS, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			level.setBlock(POS, Blocks.CRAFTING_TABLE.defaultBlockState(), Block.UPDATE_ALL);
			setSize(service(level), POS, LoaderSize.ONE);
		}
		helper.succeedWhen(() -> {
			for (ResourceKey<Level> dimension : DIMENSIONS) {
				ServerLevel level = helper.getLevel().getServer().getLevel(dimension);
				boolean ticking = level.getChunkSource().chunkMap.getDistanceManager().inEntityTickingRange(ChunkPos.asLong(POS));
				helper.assertTrue(ticking, dimension.identifier() + " chunk should be entity-ticking");
			}
		});
	}
}
