package net.wulfnet.ctcl.forge.gametest;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.wulfnet.ctcl.gametest.client.ClientSmoke;

/**
 * Runs {@link ClientSmoke} when the harness asks for it (-Dctcl.clientSmoke). Forge has no client gametest API, and its
 * {@code @Mod} has no dist, so {@link CtclForgeGameTests} calls this only on a physical client.
 */
final class CtclForgeClientSmoke {
	private CtclForgeClientSmoke() {
	}

	static void init() {
		ClientSmoke.fromSystemProperty().ifPresent(smoke -> {
			ScreenEvent.Init.Post.BUS.addListener(event -> smoke.onScreenOpened(event.getScreen()));
			// Forge fires Closing whenever setScreen replaces a screen, not only on close.
			ScreenEvent.Closing.BUS.addListener(event -> smoke.onScreenClosed(event.getScreen()));
			TickEvent.ClientTickEvent.Post.BUS.addListener(event -> smoke.tick(Minecraft.getInstance()));
		});
	}
}
