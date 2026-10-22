package net.wulfnet.ctcl.forge.gametest;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.GameTestTicker;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.gametest.ForgeGameTestHooks;
import net.minecraftforge.registries.DeferredRegister;
import net.wulfnet.ctcl.Ctcl;
import net.wulfnet.ctcl.gametest.CtclCases;

/**
 * The gametest mod: every {@link CtclCases} case as a vanilla test function. The test instances are JSON in this jar's
 * data, so the same tests run in the dev gametest server and in a real install, where Forge's {@code @GameTest} scan is
 * off (decisions.md 2026-09-29). Forge has no production gametest launcher, so in a real install a normal server runs
 * them through {@link ProdGameTests} when {@code -Dctcl.gameTestReport} is set (TODO 14.5).
 */
@Mod(CtclCases.NAMESPACE)
public final class CtclForgeGameTests {
	public CtclForgeGameTests(FMLJavaModLoadingContext context) {
		DeferredRegister<Consumer<GameTestHelper>> functions = DeferredRegister.create(Registries.TEST_FUNCTION, CtclCases.NAMESPACE);
		CtclCases.byPath().forEach((path, test) -> functions.register(path, () -> test));
		functions.register(context.getModBusGroup());
		ServerAboutToStartEvent.BUS.addListener(CtclForgeGameTests::checkRegistered);
		ServerStartedEvent.BUS.addListener(CtclForgeGameTests::startProductionTests);
		TickEvent.ServerTickEvent.Post.BUS.addListener(CtclForgeGameTests::tickProductionTests);
		// A dedicated server never takes this branch, so it never loads a client class.
		if (FMLEnvironment.dist == Dist.CLIENT) {
			CtclForgeClientSmoke.init();
		}
	}

	/**
	 * A run that can't start stops the server at once, with no report, so the harness fails now rather than at its
	 * timeout. Nothing here relies on how Forge treats a listener that throws.
	 */
	private static void startProductionTests(ServerStartedEvent event) {
		try {
			ProdGameTests.start(event.getServer());
		} catch (RuntimeException e) {
			Ctcl.LOGGER.error("CTCL production gametests couldn't start; stopping the server with no report", e);
			event.getServer().halt(false);
		}
	}

	/**
	 * Forge ticks vanilla's test runner only when {@link ForgeGameTestHooks#isGametestEnabled()}, which is false in every
	 * real install (gotchas.md 2026-09-29), so this ticks it exactly when Forge won't, at the same point in the tick
	 * (after the levels). It can never tick twice, even in dev, where the gate is on.
	 */
	private static void tickProductionTests(TickEvent.ServerTickEvent.Post event) {
		if (!ProdGameTests.running()) {
			return;
		}
		if (!ForgeGameTestHooks.isGametestEnabled() && event.server().tickRateManager().runsNormally()) {
			GameTestTicker.SINGLETON.tick();
		}
		ProdGameTests.afterTick(event.server());
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
