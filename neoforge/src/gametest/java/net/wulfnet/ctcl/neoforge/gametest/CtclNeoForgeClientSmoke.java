package net.wulfnet.ctcl.neoforge.gametest;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.wulfnet.ctcl.gametest.CtclCases;
import net.wulfnet.ctcl.gametest.client.ClientSmoke;

/** Runs {@link ClientSmoke} when the harness asks for it (-Dctcl.clientSmoke). NeoForge has no client gametest API. */
@Mod(value = CtclCases.NAMESPACE, dist = Dist.CLIENT)
public final class CtclNeoForgeClientSmoke {
	public CtclNeoForgeClientSmoke(IEventBus modBus) {
		ClientSmoke.fromSystemProperty().ifPresent(smoke -> {
			NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post event) -> smoke.onScreenOpened(event.getScreen()));
			NeoForge.EVENT_BUS.addListener((ScreenEvent.Closing event) -> smoke.onScreenClosed(event.getScreen()));
			NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> smoke.tick(Minecraft.getInstance()));
		});
	}
}
