package net.wulfnet.ctcl.gametest;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;

/**
 * Every server case, for loaders that register tests as vanilla data rather than through Fabric's annotation scan.
 * Each case becomes a {@code TEST_FUNCTION} with the id {@code ctcl_gametest:<group>/<method in snake case>}, and a
 * test instance JSON with the same id in {@code data/ctcl_gametest/test_instance/} gives its tick limit.
 */
public final class CtclCases {
	/** NeoForge and Forge mod ids can't contain '-', so this differs from Fabric's {@code ctcl-gametest}. */
	public static final String NAMESPACE = "ctcl_gametest";

	private static final List<Class<?>> GROUPS = List.of(LoaderCases.class, NetworkingCases.class, DimensionCases.class);
	private static final MethodType CASE = MethodType.methodType(void.class, GameTestHelper.class);

	private CtclCases() {
	}

	/** Every public static {@code (GameTestHelper) -> void} in the case classes, so a new case can't be left out. */
	public static Map<String, Consumer<GameTestHelper>> byPath() {
		Map<String, Consumer<GameTestHelper>> cases = new TreeMap<>();
		for (Class<?> group : GROUPS) {
			String prefix = group.getSimpleName().replace("Cases", "").toLowerCase(Locale.ROOT) + "/";
			for (Method method : group.getDeclaredMethods()) {
				if (Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers())
					&& method.getReturnType() == void.class && List.of(method.getParameterTypes()).equals(List.of(GameTestHelper.class))) {
					cases.put(prefix + snakeCase(method.getName()), invoker(group, method.getName()));
				}
			}
		}
		return cases;
	}

	/**
	 * Why the registered tests don't match the cases, or empty when they do: every case needs a function and an
	 * instance, and there must be nothing else in the namespace. A mismatch would silently skip a test.
	 */
	public static List<String> problems(RegistryAccess registries) {
		Set<String> expected = byPath().keySet();
		Set<String> functions = paths(registries.lookupOrThrow(Registries.TEST_FUNCTION).keySet());
		Set<String> instances = paths(registries.lookupOrThrow(Registries.TEST_INSTANCE).keySet());
		List<String> problems = new ArrayList<>();
		describe(problems, "case(s) with no test function", minus(expected, functions));
		describe(problems, "test function(s) with no case", minus(functions, expected));
		describe(problems, "case(s) with no test instance JSON", minus(expected, instances));
		describe(problems, "test instance(s) with no case", minus(instances, expected));
		return problems;
	}

	static String snakeCase(String camel) {
		return camel.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT);
	}

	private static Consumer<GameTestHelper> invoker(Class<?> group, String name) {
		MethodHandle handle;
		try {
			handle = MethodHandles.publicLookup().findStatic(group, name, CASE);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Can't bind " + group.getSimpleName() + "." + name, e);
		}
		return helper -> {
			try {
				handle.invokeExact(helper);
			} catch (RuntimeException | Error e) {
				// Rethrown as is: the framework reports a GameTestAssertException's own message.
				throw e;
			} catch (Throwable t) {
				throw new IllegalStateException(group.getSimpleName() + "." + name + " threw", t);
			}
		};
	}

	private static Set<String> paths(Set<Identifier> ids) {
		return ids.stream().filter(id -> id.getNamespace().equals(NAMESPACE)).map(Identifier::getPath).collect(Collectors.toCollection(TreeSet::new));
	}

	private static Set<String> minus(Set<String> a, Set<String> b) {
		Set<String> result = new TreeSet<>(a);
		result.removeAll(b);
		return result;
	}

	private static void describe(List<String> problems, String what, Set<String> paths) {
		if (!paths.isEmpty()) {
			problems.add(paths.size() + " " + what + ": " + paths);
		}
	}
}
