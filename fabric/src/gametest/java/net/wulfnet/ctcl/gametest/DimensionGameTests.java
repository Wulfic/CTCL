package net.wulfnet.ctcl.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Registers {@link DimensionCases} with Fabric's gametest API. Method names are the test names. */
public class DimensionGameTests {
	@GameTest(maxTicks = 200)
	public void aTableLoadsItsChunkInEveryDimension(GameTestHelper helper) {
		DimensionCases.aTableLoadsItsChunkInEveryDimension(helper);
	}
}
