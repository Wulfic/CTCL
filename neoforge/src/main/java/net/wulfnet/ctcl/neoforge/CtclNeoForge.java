package net.wulfnet.ctcl.neoforge;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.wulfnet.ctcl.Ctcl;
import net.wulfnet.ctcl.net.CycleLoaderC2S;
import net.wulfnet.ctcl.net.LoaderSizeS2C;
import net.wulfnet.ctcl.server.CtclNetworking;
import net.wulfnet.ctcl.server.CtclServer;
import net.wulfnet.ctcl.server.CtclTickets;
import net.wulfnet.ctcl.server.PayloadSender;

/** Wires NeoForge's events and networking to {@code common}. */
@Mod(Ctcl.MOD_ID)
public final class CtclNeoForge {
	/** Bumped only if a payload's wire format changes. */
	private static final String PROTOCOL = "1";

	public CtclNeoForge(IEventBus modBus) {
		// NeoForge freezes vanilla registries before mods initialise, so the ticket type goes through a DeferredRegister.
		DeferredRegister<TicketType> tickets = DeferredRegister.create(Registries.TICKET_TYPE, Ctcl.MOD_ID);
		tickets.register(CtclTickets.ID.getPath(), () -> CtclTickets.LOADER);
		tickets.register(modBus);
		modBus.addListener(CtclNeoForge::registerPayloads);

		NeoForge.EVENT_BUS.addListener((LevelEvent.Load event) -> {
			if (event.getLevel() instanceof ServerLevel level) {
				CtclServer.onLevelLoad(level);
			}
		});
		NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
			if (event.getLevel() instanceof ServerLevel level) {
				CtclServer.onLevelUnload(level);
			}
		});
		NeoForge.EVENT_BUS.addListener((LevelTickEvent.Post event) -> {
			if (event.getLevel() instanceof ServerLevel level) {
				CtclServer.onLevelTick(level);
			}
		});
		NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> CtclServer.onServerStopped());

		CtclNetworking.init(new PayloadSender() {
			@Override
			public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
				return player.connection.hasChannel(type);
			}

			@Override
			public void send(ServerPlayer player, CustomPacketPayload payload) {
				PacketDistributor.sendToPlayer(player, payload);
			}
		});
		Ctcl.LOGGER.info("Crafting Table Chunk Loader initialised");
	}

	/**
	 * Both payloads are optional, so a client without the mod (a vanilla LAN guest) can still join: it just gets
	 * no button. The client-side S2C handler is registered in {@code CtclNeoForgeClient}.
	 */
	private static void registerPayloads(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar(PROTOCOL).optional();
		registrar.playToServer(CycleLoaderC2S.TYPE, CycleLoaderC2S.CODEC, (payload, context) -> {
			if (context.player() instanceof ServerPlayer player) {
				CtclNetworking.onCycle(player, payload.containerId());
			} else {
				Ctcl.LOGGER.warn("Ignoring a cycle request from a non-server player {}", context.player());
			}
		});
		registrar.playToClient(LoaderSizeS2C.TYPE, LoaderSizeS2C.CODEC);
	}
}
