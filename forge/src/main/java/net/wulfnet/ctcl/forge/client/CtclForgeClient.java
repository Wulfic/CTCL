package net.wulfnet.ctcl.forge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.network.Channel;
import net.wulfnet.ctcl.Ctcl;
import net.wulfnet.ctcl.client.LoaderButtons;
import net.wulfnet.ctcl.net.CycleLoaderC2S;
import net.wulfnet.ctcl.net.LoaderSizeS2C;

/**
 * Wires Forge's client networking and screen events to {@link LoaderButtons}. Forge's {@code @Mod} has no dist, so
 * {@code CtclForge} calls this only on a physical client.
 */
public final class CtclForgeClient {
	private CtclForgeClient() {
	}

	public static void init(Channel<CustomPacketPayload> channel) {
		// The event's thread isn't documented; the slot is client-thread only.
		ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(event -> Minecraft.getInstance().execute(LoaderButtons::clear));
		ScreenEvent.Init.Post.BUS.addListener(event -> {
			if (event.getScreen() instanceof CraftingScreen crafting && serverAcceptsCycles(channel)) {
				event.addListener(LoaderButtons.attach(crafting, payload -> sendToServer(channel, payload)));
			}
		});
		ScreenEvent.Render.Pre.BUS.addListener(event -> {
			if (event.getScreen() instanceof CraftingScreen crafting) {
				LoaderButtons.refresh(crafting);
			}
		});
		Ctcl.LOGGER.info("Crafting Table Chunk Loader client initialised");
	}

	/** The clientbound handler. The channel runs it on the client thread, which LoaderButtons needs. */
	public static void onSize(LoaderSizeS2C payload, CustomPayloadEvent.Context context) {
		LoaderButtons.onSize(payload.containerId(), payload.size());
	}

	/** A server without the mod can't receive the payload, so the screen gets no button. */
	private static boolean serverAcceptsCycles(Channel<CustomPacketPayload> channel) {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		return connection != null && channel.isRemotePresent(connection.getConnection());
	}

	private static void sendToServer(Channel<CustomPacketPayload> channel, CycleLoaderC2S payload) {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		if (connection == null) {
			Ctcl.LOGGER.warn("Dropping a cycle request for container {}: not connected to a server", payload.containerId());
			return;
		}
		channel.send(payload, connection.getConnection());
	}
}
