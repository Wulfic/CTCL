package net.wulfnet.ctcl.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Registers {@link NetworkingCases} with Fabric's gametest API. Method names are the test names. */
public class NetworkingGameTests {
	@GameTest(maxTicks = 100)
	public void aCycleFromTheOpenTableMenuAdvancesTheSize(GameTestHelper helper) {
		NetworkingCases.aCycleFromTheOpenTableMenuAdvancesTheSize(helper);
	}

	@GameTest(maxTicks = 100)
	public void aWrongContainerIdChangesNothing(GameTestHelper helper) {
		NetworkingCases.aWrongContainerIdChangesNothing(helper);
	}

	@GameTest(maxTicks = 100)
	public void aPlayerWithNoMenuOpenChangesNothing(GameTestHelper helper) {
		NetworkingCases.aPlayerWithNoMenuOpenChangesNothing(helper);
	}

	@GameTest(maxTicks = 100)
	public void aPlayerOutOfReachChangesNothing(GameTestHelper helper) {
		NetworkingCases.aPlayerOutOfReachChangesNothing(helper);
	}

	@GameTest(maxTicks = 100)
	public void aTableBrokenWhileItsMenuIsOpenChangesNothing(GameTestHelper helper) {
		NetworkingCases.aTableBrokenWhileItsMenuIsOpenChangesNothing(helper);
	}

	@GameTest(maxTicks = 100)
	public void aCrafterMenuChangesNothing(GameTestHelper helper) {
		NetworkingCases.aCrafterMenuChangesNothing(helper);
	}
}
