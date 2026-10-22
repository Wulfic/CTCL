package net.wulfnet.ctcl.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.wulfnet.ctcl.Ctcl;
import net.wulfnet.ctcl.client.LoaderButtons;
import net.wulfnet.ctcl.net.CycleLoaderC2S;
import net.wulfnet.ctcl.net.LoaderSizeS2C;

/** Wires Fabric's client networking and screen events to {@link LoaderButtons}. */
public final class CtclFabricClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(LoaderSizeS2C.TYPE, (payload, context) -> LoaderButtons.onSize(payload.containerId(), payload.size()));
		// The event's thread isn't documented; the slot is client-thread only.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(LoaderButtons::clear));
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof CraftingScreen crafting && ClientPlayNetworking.canSend(CycleLoaderC2S.TYPE)) {
				Screens.getWidgets(crafting).add(LoaderButtons.attach(crafting, ClientPlayNetworking::send));
				ScreenEvents.beforeExtract(crafting).register((s, graphics, mouseX, mouseY, delta) -> LoaderButtons.refresh(crafting));
			}
		});
		Ctcl.LOGGER.info("Crafting Table Chunk Loader client initialised");
	}
}
