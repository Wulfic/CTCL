package net.wulfnet.ctcl.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.wulfnet.ctcl.Ctcl;

/** "Advance the loader size of the table behind my open menu." Carries the menu id only, never a position. */
public record CycleLoaderC2S(int containerId) implements CustomPacketPayload {
	public static final Type<CycleLoaderC2S> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Ctcl.MOD_ID, "cycle"));
	public static final StreamCodec<ByteBuf, CycleLoaderC2S> CODEC = ByteBufCodecs.VAR_INT.map(CycleLoaderC2S::new, CycleLoaderC2S::containerId);

	@Override
	public Type<CycleLoaderC2S> type() {
		return TYPE;
	}
}
