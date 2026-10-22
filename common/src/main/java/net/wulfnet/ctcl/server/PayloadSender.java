package net.wulfnet.ctcl.server;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** How a loader sends a clientbound payload. Each loader's entrypoint hands one to {@link CtclNetworking#init}. */
public interface PayloadSender {
	/** False for a client that can't receive the payload, such as a LAN guest without the mod. */
	boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type);

	void send(ServerPlayer player, CustomPacketPayload payload);
}
