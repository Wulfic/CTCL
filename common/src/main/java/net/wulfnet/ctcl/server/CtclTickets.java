package net.wulfnet.ctcl.server;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.wulfnet.ctcl.Ctcl;
import net.wulfnet.ctcl.core.TicketSink;

/**
 * The mod's chunk ticket. Like {@code /forceload} (loads, simulates, keeps the dimension
 * ticking) but <b>without FLAG_PERSIST</b>: it is never written to {@code chunks.dat}, so
 * removing the mod leaves nothing behind. The ticket-type registry isn't synced to
 * clients, so vanilla LAN guests are unaffected. Each loader registers {@link #LOADER} under
 * {@link #ID} in its own way: NeoForge freezes vanilla registries before mods initialise.
 */
public final class CtclTickets {
	public static final Identifier ID = Identifier.fromNamespaceAndPath(Ctcl.MOD_ID, "loader");
	public static final TicketType LOADER = new TicketType(
		TicketType.NO_TIMEOUT,
		TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE
	);

	private CtclTickets() {
	}

	/** Sends ticket changes to a level's vanilla chunk cache. */
	record VanillaSink(ServerChunkCache chunks) implements TicketSink {
		@Override
		public void add(int chunkX, int chunkZ, int ticketRadius) {
			chunks.addTicketWithRadius(LOADER, new ChunkPos(chunkX, chunkZ), ticketRadius);
		}

		@Override
		public void remove(int chunkX, int chunkZ, int ticketRadius) {
			chunks.removeTicketWithRadius(LOADER, new ChunkPos(chunkX, chunkZ), ticketRadius);
		}
	}
}
