package net.wulfnet.ctcl.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks which tables hold each chunk ticket, so overlapping tables stay independent.
 *
 * <p>Vanilla deduplicates tickets by (type, level) at a chunk and has no owner or count:
 * two tables in the same chunk at the same size share one ticket, and removing it for one
 * table would unload the other's chunks. So the sink is only called when a ticket gains
 * its first holder or loses its last, and a table can only release a ticket it holds.
 */
public final class LoaderTickets {
	/**
	 * Levels between a ticket's own chunk and the edge of its entity-ticking area. A ticket
	 * with radius {@code r + 2} makes the full {@code (2r+1)²} square entity-ticking.
	 */
	public static final int ENTITY_TICKING_MARGIN = 2;

	private static final Logger LOGGER = LoggerFactory.getLogger("ctcl");

	private record Key(int chunkX, int chunkZ, int ticketRadius) {
	}

	private final TicketSink sink;
	private final Map<Key, Set<TablePos>> holders = new HashMap<>();

	public LoaderTickets(TicketSink sink) {
		this.sink = sink;
	}

	public static int ticketRadius(LoaderSize size) {
		return size.radius() + ENTITY_TICKING_MARGIN;
	}

	public void apply(TablePos pos, LoaderSize size) {
		if (size.isOff()) {
			return;
		}
		Key key = keyOf(pos, size);
		Set<TablePos> tables = holders.computeIfAbsent(key, k -> new HashSet<>());
		if (!tables.add(pos)) {
			LOGGER.warn("Ignoring duplicate apply: table {} already holds its size {} ticket", pos, size.side());
			return;
		}
		if (tables.size() == 1) {
			sink.add(key.chunkX(), key.chunkZ(), key.ticketRadius());
		}
	}

	public void release(TablePos pos, LoaderSize size) {
		if (size.isOff()) {
			return;
		}
		Key key = keyOf(pos, size);
		Set<TablePos> tables = holders.get(key);
		if (tables == null || !tables.remove(pos)) {
			LOGGER.warn("Ignoring release by a table that doesn't hold the ticket: table {} size {}", pos, size.side());
			return;
		}
		if (tables.isEmpty()) {
			holders.remove(key);
			sink.remove(key.chunkX(), key.chunkZ(), key.ticketRadius());
		}
	}

	/** Distinct vanilla tickets currently held. */
	public int activeTickets() {
		return holders.size();
	}

	/**
	 * Drops all holders without touching the sink. Only for when the level itself is gone,
	 * which takes its non-persistent tickets with it.
	 */
	public void forget() {
		holders.clear();
	}

	private static Key keyOf(TablePos pos, LoaderSize size) {
		return new Key(pos.chunkX(), pos.chunkZ(), ticketRadius(size));
	}
}
