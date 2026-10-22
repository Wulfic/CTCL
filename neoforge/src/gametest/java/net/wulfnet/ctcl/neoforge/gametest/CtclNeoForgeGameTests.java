package net.wulfnet.ctcl.neoforge.gametest;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.GameTestTicker;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHooks;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.wulfnet.ctcl.Ctcl;
import net.wulfnet.ctcl.gametest.CtclCases;

/**
 * The gametest mod: every {@link CtclCases} case as a vanilla test function. The test instances are JSON in this
 * jar's data, so the same tests run in the dev gametest server and in a real install, where NeoForge doesn't fire
 * {@code RegisterGameTestsEvent} (decisions.md 2026-09-29).
 */
@Mod(CtclCases.NAMESPACE)
public final class CtclNeoForgeGameTests {
	public CtclNeoForgeGameTests(IEventBus modBus) {
		DeferredRegister<Consumer<GameTestHelper>> functions = DeferredRegister.create(Registries.TEST_FUNCTION, CtclCases.NAMESPACE);
		CtclCases.byPath().forEach((path, test) -> functions.register(path, () -> test));
		functions.register(modBus);
		NeoForge.EVENT_BUS.addListener(CtclNeoForgeGameTests::checkRegistered);
		NeoForge.EVENT_BUS.addListener(CtclNeoForgeGameTests::tickTestsInProduction);
	}

	/**
	 * NeoForge ticks vanilla's test runner only when {@link GameTestHooks#isGametestEnabled()}, which is false in every
	 * real install, so a production GameTestServer would place the tests and never run them. This ticks it exactly when
	 * NeoForge won't, at the same point in the tick (after the levels), so it can never run twice in one tick.
	 */
	private static void tickTestsInProduction(ServerTickEvent.Post event) {
		if (event.getServer() instanceof GameTestServer server && !GameTestHooks.isGametestEnabled() && server.tickRateManager().runsNormally()) {
			GameTestTicker.SINGLETON.tick();
		}
	}

	/** Fails a gametest server before any test runs if a case would be skipped. Other servers run no tests. */
	private static void checkRegistered(ServerAboutToStartEvent event) {
		if (!(event.getServer() instanceof GameTestServer)) {
			return;
		}
		List<String> problems = CtclCases.problems(event.getServer().registryAccess());
		if (!problems.isEmpty()) {
			problems.forEach(problem -> Ctcl.LOGGER.error("CTCL gametests: {}", problem));
			throw new IllegalStateException("CTCL gametest registration doesn't match the cases: " + problems);
		}
		Ctcl.LOGGER.info("CTCL gametests: {} cases registered", CtclCases.byPath().size());
	}
}
