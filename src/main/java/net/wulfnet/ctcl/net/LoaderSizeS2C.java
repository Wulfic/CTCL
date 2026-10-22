package net.wulfnet.ctcl.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.wulfnet.ctcl.CtclMod;

/** The current loader size (side length) of the table behind an open crafting menu. */
public record LoaderSizeS2C(int containerId, int size) implements CustomPacketPayload {
	public static final Type<LoaderSizeS2C> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CtclMod.MOD_ID, "size"));
	public static final StreamCodec<ByteBuf, LoaderSizeS2C> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, LoaderSizeS2C::containerId,
		ByteBufCodecs.VAR_INT, LoaderSizeS2C::size,
		LoaderSizeS2C::new
	);

	@Override
	public Type<LoaderSizeS2C> type() {
		return TYPE;
	}
}
