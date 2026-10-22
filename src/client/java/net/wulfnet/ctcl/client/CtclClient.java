package net.wulfnet.ctcl.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.wulfnet.ctcl.CtclMod;
import net.wulfnet.ctcl.net.LoaderSizeS2C;

public final class CtclClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(LoaderSizeS2C.TYPE, (payload, context) -> LoaderButtons.onSize(payload.containerId(), payload.size()));
		// Container ids restart per connection, so a stale size could match a new world's first menu.
		// The event's thread isn't documented; the slot is client-thread only.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(LoaderButtons::clear));
		LoaderButtons.init();
		CtclMod.LOGGER.info("Crafting Table Chunk Loader client initialised");
	}
}
