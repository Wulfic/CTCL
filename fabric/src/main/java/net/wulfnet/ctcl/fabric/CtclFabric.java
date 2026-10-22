package net.wulfnet.ctcl.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.wulfnet.ctcl.Ctcl;
import net.wulfnet.ctcl.net.CycleLoaderC2S;
import net.wulfnet.ctcl.net.LoaderSizeS2C;
import net.wulfnet.ctcl.server.CtclNetworking;
import net.wulfnet.ctcl.server.CtclServer;
import net.wulfnet.ctcl.server.CtclTickets;
import net.wulfnet.ctcl.server.PayloadSender;

/** Wires Fabric's events and networking to {@code common}. */
public final class CtclFabric implements ModInitializer {
	@Override
	public void onInitialize() {
		Registry.register(BuiltInRegistries.TICKET_TYPE, CtclTickets.ID, CtclTickets.LOADER);
		ServerLevelEvents.LOAD.register((server, level) -> CtclServer.onLevelLoad(level));
		ServerLevelEvents.UNLOAD.register((server, level) -> CtclServer.onLevelUnload(level));
		ServerTickEvents.END_LEVEL_TICK.register(CtclServer::onLevelTick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> CtclServer.onServerStopped());

		// Payload types are registered on both sides; the receiver only on the server.
		PayloadTypeRegistry.serverboundPlay().register(CycleLoaderC2S.TYPE, CycleLoaderC2S.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LoaderSizeS2C.TYPE, LoaderSizeS2C.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(CycleLoaderC2S.TYPE, (payload, context) -> CtclNetworking.onCycle(context.player(), payload.containerId()));
		CtclNetworking.init(new PayloadSender() {
			@Override
			public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
				return ServerPlayNetworking.canSend(player, type);
			}

			@Override
			public void send(ServerPlayer player, CustomPacketPayload payload) {
				ServerPlayNetworking.send(player, payload);
			}
		});
		Ctcl.LOGGER.info("Crafting Table Chunk Loader initialised");
	}
}
