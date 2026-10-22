package net.wulfnet.ctcl.gametest;

import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.wulfnet.ctcl.client.LoaderButtons;
import net.wulfnet.ctcl.core.LoaderSize;
import net.wulfnet.ctcl.server.CtclServer;
import net.wulfnet.ctcl.server.LoaderData;

/**
 * The whole loop in a real client and integrated server: open a table, click the button
 * with real mouse input, and read the label and the server's stored size.
 *
 * <p>It uses only calls that exist on every Minecraft version in the band (26.1 – 26.3), so the
 * band builds from one source. Vanilla moved {@code Minecraft#screen} to {@code Gui} in 26.2 and
 * replaced GLFW with SDL in 26.3.
 *
 * <p>The jar built on 26.1 also runs on 26.2 and 26.3 (the production gametests), so it must not
 * use a compile-time constant whose value differs between versions. javac copies those values in.
 * {@code InputConstants.MOUSE_BUTTON_LEFT} is 0 under GLFW and 1 under SDL, so the left button is
 * read at runtime instead.
 */
public class CtclClientGameTest implements FabricClientGameTest {
	private static final int TIMEOUT_TICKS = 100;

	/** The screen the client shows, tracked through Fabric's screen events. Read and written on the client thread. */
	private static Screen shown;

	@Override
	public void runTest(ClientGameTestContext context) {
		context.runOnClient(client -> ScreenEvents.AFTER_INIT.register((c, screen, width, height) -> {
			shown = screen;
			ScreenEvents.remove(screen).register(removed -> {
				if (shown == removed) {
					shown = null;
				}
			});
		}));
		// The harness runs one server tick per client frame. On CI's software OpenGL, the menu blur behind the
		// loading screen makes each frame so slow that the login never finishes (gotchas.md).
		context.runOnClient(client -> client.options.menuBackgroundBlurriness().set(0));
		// create() returns once the world has loaded. The test needs only the crafting screen, which it waits for.
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			BlockPos table = singleplayer.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				ServerLevel level = player.level();
				BlockPos pos = player.blockPosition().east(2);
				level.setBlock(pos, Blocks.CRAFTING_TABLE.defaultBlockState(), Block.UPDATE_ALL);
				player.openMenu(level.getBlockState(pos).getMenuProvider(level, pos));
				return pos;
			});
			context.waitForScreen(CraftingScreen.class);

			waitForLabel(context, "0");
			for (String expected : List.of("1", "3", "5", "7", "9", "0")) {
				click(context, ourButton(context));
				waitForLabel(context, expected);
			}

			// The recipe book shifts the GUI; the button must follow it and stay clickable.
			int before = context.computeOnClient(client -> ourButton().getX());
			click(context, context.computeOnClient(client -> recipeBookButton()));
			context.waitFor(client -> ourButton().getX() != before, TIMEOUT_TICKS);
			click(context, ourButton(context));
			waitForLabel(context, "1");

			LoaderSize stored = singleplayer.getServer().computeOnServer(server -> CtclServer.service(server.overworld())
				.orElseThrow()
				.sizeAt(LoaderData.toTablePos(table)));
			if (stored != LoaderSize.ONE) {
				throw new AssertionError("server stored " + stored + ", expected ONE");
			}
			assertNoOverlap(context);
		}
	}

	private static Screen shown() {
		if (shown == null) {
			throw new AssertionError("no screen is open");
		}
		return shown;
	}

	private static Button ourButton(ClientGameTestContext context) {
		return context.computeOnClient(client -> ourButton());
	}

	private static Button ourButton() {
		Screen screen = shown();
		return LoaderButtons.find(screen).orElseThrow(() -> new AssertionError("no CTCL button on " + screen));
	}

	private static AbstractWidget recipeBookButton() {
		return Screens.getWidgets(shown()).stream()
			.filter(ImageButton.class::isInstance)
			.findFirst()
			.orElseThrow(() -> new AssertionError("no recipe book button"));
	}

	private static void waitForLabel(ClientGameTestContext context, String label) {
		context.waitFor(client -> {
			Button button = ourButton();
			return button.active && button.getMessage().getString().equals(label);
		}, TIMEOUT_TICKS);
	}

	/** Moves the real cursor to the widget's centre and left-clicks. Cursor coordinates are window pixels. */
	private static void click(ClientGameTestContext context, AbstractWidget widget) {
		double scale = context.computeOnClient(client -> client.getWindow().getGuiScale());
		// Attack defaults to the left button on every version; its value is read at runtime.
		int leftButton = context.computeOnClient(client -> client.options.keyAttack.getDefaultKey().getValue());
		context.getInput().setCursorPos((widget.getX() + widget.getWidth() / 2.0) * scale, (widget.getY() + widget.getHeight() / 2.0) * scale);
		context.getInput().pressMouse(leftButton);
		context.waitTick();
	}

	private static void assertNoOverlap(ClientGameTestContext context) {
		context.runOnClient(client -> {
			Screen screen = shown();
			Button ours = ourButton();
			for (AbstractWidget other : Screens.getWidgets(screen)) {
				if (other != ours && other.visible && overlaps(ours, other)) {
					throw new AssertionError("CTCL button overlaps " + other);
				}
			}
			if (ours.getX() < 0 || ours.getY() < 0 || ours.getRight() > screen.width || ours.getBottom() > screen.height) {
				throw new AssertionError("CTCL button is off-screen at " + ours.getRectangle());
			}
		});
	}

	private static boolean overlaps(AbstractWidget a, AbstractWidget b) {
		return a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom();
	}
}
