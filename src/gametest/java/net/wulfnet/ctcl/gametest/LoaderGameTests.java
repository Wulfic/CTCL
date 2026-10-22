package net.wulfnet.ctcl.gametest;

import static net.wulfnet.ctcl.gametest.GameTestSupport.placeTable;
import static net.wulfnet.ctcl.gametest.GameTestSupport.service;
import static net.wulfnet.ctcl.gametest.GameTestSupport.setSize;
import static net.wulfnet.ctcl.gametest.GameTestSupport.table;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.wulfnet.ctcl.core.ChunkArea;
import net.wulfnet.ctcl.core.LoaderService;
import net.wulfnet.ctcl.core.LoaderSize;

/**
 * Proves in a real server what the unit tests can only assume: that our ticket makes the
 * N×N square entity-ticking, and that every removal path releases it.
 *
 * <p>Each test works in its own region far from spawn and from the others (tests run in
 * parallel), so any entity-ticking chunk there can only be ticking because of us.
 */
public class LoaderGameTests {
	@GameTest(maxTicks = 200)
	public void sizeFiveTicksExactlyTheFiveByFiveSquare(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos pos = placeTable(level, 0);
		LoaderService service = service(level);
		setSize(service, pos, LoaderSize.FIVE);

		helper.succeedWhen(() -> assertSquare(helper, level, pos, 2, true));
	}

	@GameTest(maxTicks = 200)
	public void cyclingBackToOffReleasesEveryChunk(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos pos = placeTable(level, 1);
		LoaderService service = service(level);
		setSize(service, pos, LoaderSize.FIVE);

		helper.runAfterDelay(5, () -> {
			assertSquare(helper, level, pos, 2, true);
			setSize(service, pos, LoaderSize.OFF);
			helper.succeedWhen(() -> assertNothingTicking(helper, level, pos, 3));
		});
	}

	@GameTest(maxTicks = 200)
	public void breakingTheTableReleasesItImmediately(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos pos = placeTable(level, 2);
		LoaderService service = service(level);
		setSize(service, pos, LoaderSize.THREE);

		helper.runAfterDelay(5, () -> {
			level.destroyBlock(pos, false);
			helper.assertTrue(service.sizeAt(table(pos)).isOff(), "the removal hook should clear the entry in the same tick");
			helper.succeedWhen(() -> assertNothingTicking(helper, level, pos, 2));
		});
	}

	@GameTest(maxTicks = 200)
	public void aPistonPushReleasesTheOldPositionAndTheMovedTableStartsOff(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos pos = placeTable(level, 3);
		BlockPos piston = pos.west();
		level.setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST), Block.UPDATE_ALL);
		LoaderService service = service(level);
		setSize(service, pos, LoaderSize.ONE);
		// Stands in for a player nearby. The push releases this table's own loader, and the
		// moving block only lands if something else keeps the chunk ticking.
		ChunkPos chunk = new ChunkPos(pos);
		level.setChunkForced(chunk.x, chunk.z, true);

		helper.runAfterDelay(5, () -> {
			level.setBlock(piston.below(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
			helper.succeedWhen(() -> {
				helper.assertTrue(level.getBlockState(pos.east()).is(Blocks.CRAFTING_TABLE), "the table should have been pushed east; piston=" + level.getBlockState(piston)
					+ " old=" + level.getBlockState(pos) + " east=" + level.getBlockState(pos.east()) + " east2=" + level.getBlockState(pos.east(2)));
				helper.assertTrue(service.sizeAt(table(pos)).isOff(), "the old position should be released; size=" + service.sizeAt(table(pos))
					+ " old=" + level.getBlockState(pos) + " east=" + level.getBlockState(pos.east()) + " piston=" + level.getBlockState(piston));
				helper.assertTrue(service.sizeAt(table(pos.east())).isOff(), "a moved table starts at 0");
				level.setChunkForced(chunk.x, chunk.z, false);
			});
		});
	}

	@GameTest(maxTicks = 200)
	public void aReplacementThatSkipsNeighbourUpdatesIsCaughtByValidation(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos pos = placeTable(level, 4);
		LoaderService service = service(level);
		setSize(service, pos, LoaderSize.ONE);

		helper.runAfterDelay(5, () -> {
			// UPDATE_CLIENTS only: vanilla skips affectNeighborsAfterRemoval, so the hook never fires.
			level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
			helper.assertFalse(service.sizeAt(table(pos)).isOff(), "the hook should not have seen this change");
			helper.succeedWhen(() -> {
				helper.assertTrue(service.sizeAt(table(pos)).isOff(), "validation should remove the entry");
				assertNothingTicking(helper, level, pos, 1);
			});
		});
	}

	@GameTest(maxTicks = 200)
	public void turningOffOneOverlappingTableKeepsTheOthersChunks(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos first = placeTable(level, 5);
		BlockPos second = first.north(3);
		level.setBlock(second, Blocks.CRAFTING_TABLE.defaultBlockState(), Block.UPDATE_ALL);
		helper.assertTrue(new ChunkPos(first).equals(new ChunkPos(second)), "both tables must share a chunk");
		LoaderService service = service(level);
		setSize(service, first, LoaderSize.FIVE);
		setSize(service, second, LoaderSize.FIVE);

		helper.runAfterDelay(5, () -> {
			setSize(service, first, LoaderSize.OFF);
			helper.runAfterDelay(5, () -> {
				assertSquare(helper, level, second, 2, true);
				setSize(service, second, LoaderSize.OFF);
				helper.succeedWhen(() -> assertNothingTicking(helper, level, first, 3));
			});
		});
	}

	/** Every chunk within {@code radius} is entity-ticking, and the ring just outside it is not. */
	private static void assertSquare(GameTestHelper helper, ServerLevel level, BlockPos pos, int radius, boolean ticking) {
		int cx = ChunkArea.chunkOf(pos.getX());
		int cz = ChunkArea.chunkOf(pos.getZ());
		for (int dx = -radius - 1; dx <= radius + 1; dx++) {
			for (int dz = -radius - 1; dz <= radius + 1; dz++) {
				boolean inside = Math.max(Math.abs(dx), Math.abs(dz)) <= radius;
				boolean actual = entityTicking(level, cx + dx, cz + dz);
				helper.assertTrue(actual == (inside && ticking), "chunk offset (" + dx + ", " + dz + ") entity-ticking=" + actual);
			}
		}
	}

	private static void assertNothingTicking(GameTestHelper helper, ServerLevel level, BlockPos pos, int radius) {
		assertSquare(helper, level, pos, radius, false);
	}

	private static boolean entityTicking(ServerLevel level, int chunkX, int chunkZ) {
		return level.getChunkSource().chunkMap.getDistanceManager().inEntityTickingRange(ChunkPos.asLong(chunkX, chunkZ));
	}
}
