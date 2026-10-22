package net.wulfnet.ctcl.gametest.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.wulfnet.ctcl.Ctcl;
import net.wulfnet.ctcl.client.LoaderButtons;
import net.wulfnet.ctcl.core.LoaderSize;
import net.wulfnet.ctcl.server.CtclServer;
import net.wulfnet.ctcl.server.LoaderData;

/**
 * A client smoke test for loaders with no client gametest API (NeoForge, Forge). It's active only with
 * {@code -Dctcl.clientSmoke=<result file>}, and the loader calls {@link #tick} at the end of every client tick.
 *
 * <p>It builds on the base version and runs on every pin, so it uses only API that is the same on all of them. The
 * current screen is one that isn't: the loader reports screens through {@link #onScreenOpened} and {@link #onScreenClosed}
 * (as the Fabric client test tracks them), because the field holding it moved after 26.1.2.
 *
 * <p>The steps: create a superflat world, open a crafting table beside the player, and wait for the CTCL button to show
 * the server's size. Then click it through the screen's own mouse handling, and check both the new label and the size the
 * server stored. The result file gets {@code PASS} or {@code FAIL: <reason>}, then the client quits. The harness fails on
 * anything but PASS, so a crash or a hang, which leave no PASS, fail too.
 */
public final class ClientSmoke {
	public static final String PROPERTY = "ctcl.clientSmoke";
	/** Per step. World creation on CI's software OpenGL is the slow one. */
	private static final int STEP_TIMEOUT_TICKS = 20 * 180;

	private enum Step { WAIT_FOR_MENU, WAIT_FOR_WORLD, WAIT_FOR_BUTTON, WAIT_FOR_SIZE, WAIT_FOR_CLICK, DONE }

	private final Path result;
	private Step step = Step.WAIT_FOR_MENU;
	private int ticksInStep;
	/** Set on the server thread and read on the client thread. */
	private volatile BlockPos table;
	private volatile LoaderSize storedSize;
	/** The screen the client shows, from the loader's screen events. Client thread only. */
	private Screen shown;

	private ClientSmoke(Path result) {
		this.result = result;
	}

	/** The smoke test, when the harness asked for it. */
	public static Optional<ClientSmoke> fromSystemProperty() {
		String path = System.getProperty(PROPERTY);
		if (path == null || path.isBlank()) {
			return Optional.empty();
		}
		Ctcl.LOGGER.info("CTCL client smoke test: result goes to {}", path);
		return Optional.of(new ClientSmoke(Path.of(path)));
	}

	/** Client thread: a screen finished its init. */
	public void onScreenOpened(Screen screen) {
		shown = screen;
	}

	/** Client thread: a screen was closed or replaced. */
	public void onScreenClosed(Screen screen) {
		if (shown == screen) {
			shown = null;
		}
	}

	/** Client thread, once per tick. */
	public void tick(Minecraft minecraft) {
		if (step == Step.DONE) {
			return;
		}
		try {
			if (++ticksInStep > STEP_TIMEOUT_TICKS) {
				finish(minecraft, "FAIL: timed out in " + step + " after " + STEP_TIMEOUT_TICKS + " ticks" + detail(minecraft));
				return;
			}
			switch (step) {
				case WAIT_FOR_MENU -> {
					// The first screen after loading: the title screen, or accessibility onboarding on a fresh game dir.
					// isGameLoadFinished, not the loading overlay: the overlay API changed after 26.1.2, and this jar is
					// built on the base and run on every pin.
					if (minecraft.isGameLoadFinished() && shown != null) {
						// The menu blur starved the Fabric harness on CI's software OpenGL (gotchas.md 2026-09-28).
						minecraft.options.menuBackgroundBlurriness().set(0);
						createWorld(minecraft, shown);
						next(Step.WAIT_FOR_WORLD);
					}
				}
				case WAIT_FOR_WORLD -> {
					IntegratedServer server = minecraft.getSingleplayerServer();
					if (minecraft.level != null && minecraft.player != null && server != null && shown == null) {
						UUID player = minecraft.player.getUUID();
						server.execute(() -> openTable(server, player));
						next(Step.WAIT_FOR_BUTTON);
					}
				}
				case WAIT_FOR_BUTTON -> {
					if (shown instanceof CraftingScreen crafting && LoaderButtons.find(crafting).isPresent()) {
						next(Step.WAIT_FOR_SIZE);
					}
				}
				case WAIT_FOR_SIZE -> {
					Button button = button();
					if (button.active && label(button).equals("0")) {
						click(minecraft, button);
						next(Step.WAIT_FOR_CLICK);
					}
				}
				case WAIT_FOR_CLICK -> {
					readStoredSize(minecraft);
					if (label(button()).equals("1") && storedSize == LoaderSize.ONE) {
						finish(minecraft, "PASS: the button read 0, a click made it 1, and the server stored 1");
					}
				}
				case DONE -> {
				}
			}
		} catch (RuntimeException | AssertionError e) {
			Ctcl.LOGGER.error("CTCL client smoke test failed in {}", step, e);
			finish(minecraft, "FAIL: " + step + ": " + e);
		}
	}

	private void next(Step nextStep) {
		Ctcl.LOGGER.info("CTCL client smoke test: {} done after {} ticks, now {}", step, ticksInStep, nextStep);
		step = nextStep;
		ticksInStep = 0;
	}

	private static void createWorld(Minecraft minecraft, Screen parent) {
		// A fresh name each run: the game dir outlives a run, and an existing world would carry old state.
		String name = "ctcl-client-smoke-" + System.currentTimeMillis();
		LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT, false, WorldDataConfiguration.DEFAULT);
		// The flat preset from the registry: the static helper for it is gone after 26.1.2.
		minecraft.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(0L, false, false),
			registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), parent);
	}

	/** Server thread: a table beside the player, and its menu open, as a player's right-click would. */
	private void openTable(IntegratedServer server, UUID id) {
		ServerPlayer player = server.getPlayerList().getPlayer(id);
		if (player == null) {
			throw new IllegalStateException("the integrated server has no player " + id);
		}
		ServerLevel level = player.level();
		BlockPos pos = player.blockPosition().offset(2, 0, 0);
		level.setBlock(pos, Blocks.CRAFTING_TABLE.defaultBlockState(), Block.UPDATE_ALL);
		table = pos;
		player.openMenu(level.getBlockState(pos).getMenuProvider(level, pos));
	}

	/** Asks the server thread for the table's stored size; the answer arrives on a later tick. */
	private void readStoredSize(Minecraft minecraft) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		BlockPos pos = table;
		if (server == null || pos == null) {
			return;
		}
		server.execute(() -> server.getAllLevels().forEach(level -> {
			if (level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
				CtclServer.service(level).ifPresent(service -> storedSize = service.sizeAt(LoaderData.toTablePos(pos)));
			}
		}));
	}

	private Button button() {
		if (!(shown instanceof CraftingScreen crafting)) {
			throw new AssertionError("the crafting screen closed; the screen is " + shown);
		}
		return LoaderButtons.find(crafting).orElseThrow(() -> new AssertionError("the crafting screen has no CTCL button"));
	}

	private static String label(Button button) {
		return button.getMessage().getString();
	}

	/** A left click at the button's centre, through the screen's mouse handling, as the real cursor would deliver it. */
	private void click(Minecraft minecraft, Button button) {
		// Attack defaults to the left button on every version; read it at runtime, since the constant changed (gotchas.md).
		int left = minecraft.options.keyAttack.getDefaultKey().getValue();
		MouseButtonEvent event = new MouseButtonEvent(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, new MouseButtonInfo(left, 0));
		shown.mouseClicked(event, false);
		shown.mouseReleased(event);
	}

	private String detail(Minecraft minecraft) {
		String screen = shown == null ? "none" : shown.getClass().getName();
		String label = shown instanceof CraftingScreen crafting
			? LoaderButtons.find(crafting).map(b -> "'" + label(b) + "', active " + b.active).orElse("absent")
			: "n/a";
		return " (screen " + screen + ", button " + label + ", stored size " + storedSize + ")";
	}

	private void finish(Minecraft minecraft, String outcome) {
		step = Step.DONE;
		Ctcl.LOGGER.info("CTCL client smoke test: {}", outcome);
		try {
			Files.writeString(result, outcome + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			// Without the file the harness fails anyway; say why.
			Ctcl.LOGGER.error("Could not write the smoke test result to {}", result, e);
		}
		minecraft.stop();
	}
}
