package net.wulfnet.ctcl.forge.gametest;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import javax.xml.parsers.ParserConfigurationException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestBatch;
import net.minecraft.gametest.framework.GameTestBatchFactory;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.JUnitLikeTestReporter;
import net.minecraft.gametest.framework.MultipleTestTracker;
import net.minecraft.gametest.framework.StructureGridSpawner;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.wulfnet.ctcl.Ctcl;
import net.wulfnet.ctcl.gametest.CtclCases;

/**
 * Runs every CTCL gametest in a normal server, because Forge has no production gametest launcher (TODO 14.5). It does
 * what vanilla's GameTestServer does, with vanilla API only: the tests go through vanilla's runner, and the report is
 * vanilla's JUnit-like XML. {@link CtclForgeGameTests} calls {@link #start} once the server has started, ticks
 * {@code GameTestTicker} while {@link #running()} if Forge doesn't, and calls {@link #afterTick} at the end of each
 * server tick. With no {@value #REPORT_PROPERTY} system property, nothing runs and the server is a plain one.
 * <p>
 * It lives here, not in common/, because 26.3 changed the runner's API and Fabric compiles common/'s gametest sources on
 * 26.3 (gotchas.md 2026-09-30). This jar is built once on the base and runs on every pin from 26.1 to 26.3, so the three
 * calls 26.3 changed go through {@link #batches}, {@link #runnerBuilder}, and {@link #gridSpawner}, which pick the
 * overload at runtime (gotchas.md 2026-10-01).
 */
public final class ProdGameTests {
	/** The absolute path the JUnit-like report is written to. Setting it is what turns the run on. */
	public static final String REPORT_PROPERTY = "ctcl.gameTestReport";
	/** Vanilla GameTestServer's height on the default flat preset. The x and z keep the tests away from world spawn. */
	private static final BlockPos FIRST_TEST = new BlockPos(1024, -59, 1024);
	private static final int TESTS_PER_ROW = 8;
	private static final int PROGRESS_INTERVAL_TICKS = 20;

	/** Null until {@link #start} runs the tests. */
	private static MultipleTestTracker tracker;
	private static boolean finished;

	private ProdGameTests() {
	}

	/** True once the tests have started and until they have all finished. */
	public static boolean running() {
		return tracker != null && !finished;
	}

	/**
	 * Starts every CTCL test instance in the overworld, if {@value #REPORT_PROPERTY} is set. Throws before any test runs,
	 * so no report is written, if the property isn't an absolute path, the server is vanilla's own GameTestServer, the
	 * world isn't flat, or a case would be skipped.
	 */
	public static void start(MinecraftServer server) {
		String reportPath = System.getProperty(REPORT_PROPERTY);
		if (reportPath == null) {
			return;
		}
		if (tracker != null) {
			throw new IllegalStateException("CTCL production gametests already started");
		}
		if (server instanceof GameTestServer) {
			throw new IllegalStateException("-D" + REPORT_PROPERTY + " is for a normal server: a GameTestServer runs the tests itself");
		}
		File report = new File(reportPath);
		if (!report.isAbsolute()) {
			throw new IllegalStateException("-D" + REPORT_PROPERTY + " must be an absolute path, got " + reportPath);
		}
		ServerLevel level = server.overworld();
		// The cases were written for vanilla's flat test world; terrain would bury or undercut the structures.
		if (!(level.getChunkSource().getGenerator() instanceof FlatLevelSource)) {
			throw new IllegalStateException("CTCL production gametests need a flat overworld (level-type=minecraft:flat), got "
				+ level.getChunkSource().getGenerator().getClass().getName());
		}
		List<String> problems = CtclCases.problems(server.registryAccess());
		if (!problems.isEmpty()) {
			problems.forEach(problem -> Ctcl.LOGGER.error("CTCL gametests: {}", problem));
			throw new IllegalStateException("CTCL gametest registration doesn't match the cases: " + problems);
		}
		List<Holder.Reference<GameTestInstance>> tests = server.registryAccess().lookupOrThrow(Registries.TEST_INSTANCE).listElements()
			.filter(test -> test.key().identifier().getNamespace().equals(CtclCases.NAMESPACE))
			.filter(test -> !test.value().manualOnly())
			.toList();
		if (tests.size() != CtclCases.byPath().size()) {
			throw new IllegalStateException("Expected " + CtclCases.byPath().size() + " CTCL test instances to run, found " + tests.size());
		}
		try {
			GlobalTestReporter.replaceWith(new JUnitLikeTestReporter(report));
		} catch (ParserConfigurationException e) {
			throw new IllegalStateException("Can't create the gametest report for " + report, e);
		}
		List<GameTestBatch> batches = batches(tests, server, level);
		GameTestRunner runner = runnerBuilder(batches, server, level)
			.newStructureSpawner(gridSpawner())
			.build();
		tracker = new MultipleTestTracker(runner.getTestInfos());
		Ctcl.LOGGER.info("CTCL production gametests: {} tests are now running at {}, report {}", tracker.getTotalCount(), FIRST_TEST.toShortString(), report);
		runner.start();
	}

	/**
	 * Logs progress, and once every test is done writes the report and stops the server. The world is saved on the
	 * way out, so a restart on it can check what the tests left behind.
	 */
	public static void afterTick(MinecraftServer server) {
		MultipleTestTracker current = tracker;
		if (current == null || finished) {
			return;
		}
		if (server.overworld().getGameTime() % PROGRESS_INTERVAL_TICKS == 0) {
			Ctcl.LOGGER.info("CTCL production gametests: {}", current.getProgressBar());
		}
		if (!current.isDone()) {
			return;
		}
		finished = true;
		GlobalTestReporter.finish();
		if (current.hasFailedRequired()) {
			Ctcl.LOGGER.error("CTCL production gametests: {} of {} required tests failed", current.getFailedRequiredCount(), current.getTotalCount());
			current.getFailedRequired().forEach(ProdGameTests::logFailure);
		} else {
			Ctcl.LOGGER.info("CTCL production gametests: all {} required tests passed", current.getTotalCount());
		}
		current.getFailedOptional().forEach(ProdGameTests::logFailure);
		Ctcl.LOGGER.info("CTCL production gametests: done, stopping the server");
		server.halt(false);
	}

	/** {@code GameTestBatchFactory.divideIntoBatches}: its last parameter is the level up to 26.2 and the server on 26.3. */
	private static List<GameTestBatch> batches(List<Holder.Reference<GameTestInstance>> tests, MinecraftServer server, ServerLevel level) {
		Method method = (Method) only(GameTestBatchFactory.class.getMethods(), "divideIntoBatches", 3);
		Object batches = invoke(method, tests, GameTestBatchFactory.DIRECT, levelOrServer(method, server, level));
		return ((List<?>) batches).stream().map(GameTestBatch.class::cast).toList();
	}

	/** {@code GameTestRunner.Builder.fromBatches}: the level up to 26.2, the server on 26.3. */
	private static GameTestRunner.Builder runnerBuilder(List<GameTestBatch> batches, MinecraftServer server, ServerLevel level) {
		Method method = (Method) only(GameTestRunner.Builder.class.getMethods(), "fromBatches", 2);
		return (GameTestRunner.Builder) invoke(method, batches, levelOrServer(method, server, level));
	}

	/** {@code new StructureGridSpawner}: one origin up to 26.2; on 26.3 an origin per dimension (the tests use only the overworld). */
	private static StructureGridSpawner gridSpawner() {
		Constructor<?> constructor = (Constructor<?>) only(StructureGridSpawner.class.getConstructors(), "<init>", 3);
		Class<?> origin = constructor.getParameterTypes()[0];
		Function<Object, BlockPos> everyDimension = dimension -> FIRST_TEST;
		Object arg = origin == BlockPos.class ? FIRST_TEST : origin == Function.class ? everyDimension : null;
		if (arg == null) {
			throw new IllegalStateException("Unknown StructureGridSpawner origin type " + origin.getName() + " in " + constructor);
		}
		return (StructureGridSpawner) invoke(constructor, arg, TESTS_PER_ROW, false);
	}

	/** The one public method (static) or constructor ({@code <init>}) with this name and parameter count; anything else fails. */
	private static Executable only(Executable[] candidates, String name, int parameters) {
		List<Executable> found = Arrays.stream(candidates)
			.filter(e -> (e instanceof Constructor<?> ? "<init>" : e.getName()).equals(name))
			.filter(e -> e instanceof Constructor<?> || Modifier.isStatic(e.getModifiers()))
			.filter(e -> e.getParameterCount() == parameters)
			.toList();
		if (found.size() != 1) {
			throw new IllegalStateException("Expected one " + name + " with " + parameters + " parameters, found " + found);
		}
		return found.get(0);
	}

	private static Object levelOrServer(Method method, MinecraftServer server, ServerLevel level) {
		Class<?> last = method.getParameterTypes()[method.getParameterCount() - 1];
		if (last.isInstance(level)) {
			return level;
		}
		if (last.isInstance(server)) {
			return server;
		}
		throw new IllegalStateException(method + " takes neither the level nor the server");
	}

	private static Object invoke(Executable target, Object... args) {
		try {
			return target instanceof Method method ? method.invoke(null, args) : ((Constructor<?>) target).newInstance(args);
		} catch (InvocationTargetException e) {
			throw new IllegalStateException(target + " threw", e.getCause());
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Can't call " + target, e);
		}
	}

	private static void logFailure(GameTestInfo test) {
		Ctcl.LOGGER.error("   - {}: {}", test.id(), test.getError() == null ? "no error recorded" : test.getError().getDescription().getString());
	}
}
