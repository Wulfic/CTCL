package net.wulfnet.ctcl.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Registers {@link LoaderCases} with Fabric's gametest API. Method names are the test names. */
public class LoaderGameTests {
	@GameTest(maxTicks = 200)
	public void sizeFiveTicksExactlyTheFiveByFiveSquare(GameTestHelper helper) {
		LoaderCases.sizeFiveTicksExactlyTheFiveByFiveSquare(helper);
	}

	@GameTest(maxTicks = 200)
	public void cyclingBackToOffReleasesEveryChunk(GameTestHelper helper) {
		LoaderCases.cyclingBackToOffReleasesEveryChunk(helper);
	}

	@GameTest(maxTicks = 200)
	public void breakingTheTableReleasesItImmediately(GameTestHelper helper) {
		LoaderCases.breakingTheTableReleasesItImmediately(helper);
	}

	@GameTest(maxTicks = 200)
	public void aPistonPushReleasesTheOldPositionAndTheMovedTableStartsOff(GameTestHelper helper) {
		LoaderCases.aPistonPushReleasesTheOldPositionAndTheMovedTableStartsOff(helper);
	}

	@GameTest(maxTicks = 200)
	public void aReplacementThatSkipsNeighbourUpdatesIsCaughtByValidation(GameTestHelper helper) {
		LoaderCases.aReplacementThatSkipsNeighbourUpdatesIsCaughtByValidation(helper);
	}

	@GameTest(maxTicks = 200)
	public void turningOffOneOverlappingTableKeepsTheOthersChunks(GameTestHelper helper) {
		LoaderCases.turningOffOneOverlappingTableKeepsTheOthersChunks(helper);
	}
}
