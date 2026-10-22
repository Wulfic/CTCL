package net.wulfnet.ctcl.neoforge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.wulfnet.ctcl.Ctcl;
import net.wulfnet.ctcl.client.LoaderButtons;
import net.wulfnet.ctcl.net.CycleLoaderC2S;
import net.wulfnet.ctcl.net.LoaderSizeS2C;

/** Wires NeoForge's client networking and screen events to {@link LoaderButtons}. */
@Mod(value = Ctcl.MOD_ID, dist = Dist.CLIENT)
public final class CtclNeoForgeClient {
	public CtclNeoForgeClient(IEventBus modBus) {
		// Payload handlers run on the client thread by default, which LoaderButtons needs.
		modBus.addListener((RegisterClientPayloadHandlersEvent event) ->
			event.register(LoaderSizeS2C.TYPE, (payload, context) -> LoaderButtons.onSize(payload.containerId(), payload.size())));
		// The event's thread isn't documented; the slot is client-thread only.
		NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> Minecraft.getInstance().execute(LoaderButtons::clear));
		NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post event) -> {
			if (event.getScreen() instanceof CraftingScreen crafting && serverAcceptsCycles()) {
				event.addListener(LoaderButtons.attach(crafting, payload -> ClientPacketDistributor.sendToServer(payload)));
			}
		});
		NeoForge.EVENT_BUS.addListener((ScreenEvent.Render.Pre event) -> {
			if (event.getScreen() instanceof CraftingScreen crafting) {
				LoaderButtons.refresh(crafting);
			}
		});
		Ctcl.LOGGER.info("Crafting Table Chunk Loader client initialised");
	}

	/** A server without the mod can't receive the payload, so the screen gets no button. */
	private static boolean serverAcceptsCycles() {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		return connection != null && connection.hasChannel(CycleLoaderC2S.TYPE);
	}
}
