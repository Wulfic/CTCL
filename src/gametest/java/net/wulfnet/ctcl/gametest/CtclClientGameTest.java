package net.wulfnet.ctcl.gametest;

import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ImageButton;
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
import org.lwjgl.glfw.GLFW;

/**
 * The whole loop in a real client and integrated server: open a table, click the button
 * with real mouse input, and read the label and the server's stored size.
 */
public class CtclClientGameTest implements FabricClientGameTest {
	private static final int TIMEOUT_TICKS = 100;

	@Override
	public void runTest(ClientGameTestContext context) {
		// The harness runs one server tick per client frame. On CI's software OpenGL, the menu blur behind the
		// loading screen makes each frame so slow that the login never finishes (gotchas.md).
		context.runOnClient(client -> client.options.menuBackgroundBlurriness().set(0));
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getClientWorld().waitForChunksRender();
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
			int before = context.computeOnClient(client -> ourButton(client).getX());
			click(context, context.computeOnClient(CtclClientGameTest::recipeBookButton));
			context.waitFor(client -> ourButton(client).getX() != before, TIMEOUT_TICKS);
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

	private static Button ourButton(ClientGameTestContext context) {
		return context.computeOnClient(CtclClientGameTest::ourButton);
	}

	private static Button ourButton(Minecraft client) {
		return LoaderButtons.find(client.screen).orElseThrow(() -> new AssertionError("no CTCL button on " + client.screen));
	}

	private static AbstractWidget recipeBookButton(Minecraft client) {
		return Screens.getButtons(client.screen).stream()
			.filter(ImageButton.class::isInstance)
			.findFirst()
			.orElseThrow(() -> new AssertionError("no recipe book button"));
	}

	private static void waitForLabel(ClientGameTestContext context, String label) {
		context.waitFor(client -> {
			Button button = ourButton(client);
			return button.active && button.getMessage().getString().equals(label);
		}, TIMEOUT_TICKS);
	}

	/** Moves the real cursor to the widget's centre and left-clicks. Cursor coordinates are window pixels. */
	private static void click(ClientGameTestContext context, AbstractWidget widget) {
		double scale = context.computeOnClient(client -> client.getWindow().getGuiScale());
		context.getInput().setCursorPos((widget.getX() + widget.getWidth() / 2.0) * scale, (widget.getY() + widget.getHeight() / 2.0) * scale);
		context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		context.waitTick();
	}

	private static void assertNoOverlap(ClientGameTestContext context) {
		context.runOnClient(client -> {
			Button ours = ourButton(client);
			for (AbstractWidget other : Screens.getButtons(client.screen)) {
				if (other != ours && other.visible && overlaps(ours, other)) {
					throw new AssertionError("CTCL button overlaps " + other);
				}
			}
			if (ours.getX() < 0 || ours.getY() < 0 || ours.getRight() > client.screen.width || ours.getBottom() > client.screen.height) {
				throw new AssertionError("CTCL button is off-screen at " + ours.getRectangle());
			}
		});
	}

	private static boolean overlaps(AbstractWidget a, AbstractWidget b) {
		return a.getX() < b.getRight() && b.getX() < a.getRight() && a.getY() < b.getBottom() && b.getY() < a.getBottom();
	}
}
