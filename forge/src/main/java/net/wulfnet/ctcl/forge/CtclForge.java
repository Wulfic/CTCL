package net.wulfnet.ctcl.forge;

import java.util.function.BiConsumer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.registries.DeferredRegister;
import net.wulfnet.ctcl.Ctcl;
import net.wulfnet.ctcl.forge.client.CtclForgeClient;
import net.wulfnet.ctcl.net.CycleLoaderC2S;
import net.wulfnet.ctcl.net.LoaderSizeS2C;
import net.wulfnet.ctcl.server.CtclNetworking;
import net.wulfnet.ctcl.server.CtclServer;
import net.wulfnet.ctcl.server.CtclTickets;
import net.wulfnet.ctcl.server.PayloadSender;

/** Wires Forge's events and networking to {@code common}. */
@Mod(Ctcl.MOD_ID)
public final class CtclForge {
	/** Bumped only if a payload's wire format changes. */
	private static final int PROTOCOL = 1;

	public CtclForge(FMLJavaModLoadingContext context) {
		// Forge freezes vanilla registries before mods initialise, so the ticket type goes through a DeferredRegister.
		DeferredRegister<TicketType> tickets = DeferredRegister.create(Registries.TICKET_TYPE, Ctcl.MOD_ID);
		tickets.register(CtclTickets.ID.getPath(), () -> CtclTickets.LOADER);
		tickets.register(context.getModBusGroup());

		LevelEvent.Load.BUS.addListener(event -> {
			if (event.getLevel() instanceof ServerLevel level) {
				CtclServer.onLevelLoad(level);
			}
		});
		LevelEvent.Unload.BUS.addListener(event -> {
			if (event.getLevel() instanceof ServerLevel level) {
				CtclServer.onLevelUnload(level);
			}
		});
		TickEvent.LevelTickEvent.Post.BUS.addListener(event -> {
			if (event.level() instanceof ServerLevel level) {
				CtclServer.onLevelTick(level);
			}
		});
		ServerStoppedEvent.BUS.addListener(event -> CtclServer.onServerStopped());

		// Forge fixes a channel's handlers when it's built, so the clientbound one is chosen here. A dedicated server
		// never takes the client branch, so it never loads a client class.
		boolean client = FMLEnvironment.dist == Dist.CLIENT;
		Channel<CustomPacketPayload> channel = buildChannel(client ? CtclForgeClient::onSize : CtclForge::sizeOnServer);
		CtclNetworking.init(new PayloadSender() {
			@Override
			public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
				return channel.isRemotePresent(player.connection.getConnection());
			}

			@Override
			public void send(ServerPlayer player, CustomPacketPayload payload) {
				channel.send(payload, player.connection.getConnection());
			}
		});
		if (client) {
			CtclForgeClient.init(channel);
		}
		Ctcl.LOGGER.info("Crafting Table Chunk Loader initialised");
	}

	/**
	 * One channel carries both payloads, and it's optional, so a client without the mod (a vanilla LAN guest) can still
	 * join: it just gets no button. Both handlers run on the game thread.
	 */
	private static Channel<CustomPacketPayload> buildChannel(BiConsumer<LoaderSizeS2C, CustomPayloadEvent.Context> onSize) {
		return ChannelBuilder.named(Identifier.fromNamespaceAndPath(Ctcl.MOD_ID, "main"))
			.networkProtocolVersion(PROTOCOL)
			.optional()
			.payloadChannel()
			.play()
			.serverbound()
			.addMain(CycleLoaderC2S.TYPE, CycleLoaderC2S.CODEC.cast(), (payload, context) -> {
				ServerPlayer player = context.getSender();
				if (player != null) {
					CtclNetworking.onCycle(player, payload.containerId());
				} else {
					Ctcl.LOGGER.warn("Ignoring a cycle request with no server player on {}", context.getConnection());
				}
			})
			.clientbound()
			.addMain(LoaderSizeS2C.TYPE, LoaderSizeS2C.CODEC.cast(), onSize)
			.build();
	}

	/**
	 * The channel needs a clientbound handler on a dedicated server too. Forge disconnects a peer that sends a payload the
	 * wrong way before any handler runs, so this only logs if that ever changes.
	 */
	private static void sizeOnServer(LoaderSizeS2C payload, CustomPayloadEvent.Context context) {
		Ctcl.LOGGER.warn("Ignoring a loader size payload received by a dedicated server from {}", context.getConnection());
	}
}
