package net.wulfnet.ctcl.core;

import java.util.Map;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader state for one dimension: the stored sizes and the tickets that load them.
 * Every change goes through here, so the store and the tickets never disagree.
 */
public final class LoaderService {
	private static final Logger LOGGER = LoggerFactory.getLogger("ctcl");

	private final String dimension;
	private final LoaderStore store;
	private final LoaderTickets tickets;

	public LoaderService(String dimension, LoaderStore store, LoaderTickets tickets) {
		this.dimension = dimension;
		this.store = store;
		this.tickets = tickets;
	}

	/** Re-applies tickets for every stored table. Tickets aren't persisted, so this runs on every level load. */
	public int restore() {
		Map<TablePos, LoaderSize> entries = store.snapshot();
		entries.forEach(tickets::apply);
		LOGGER.info("Restored {} crafting table loader(s) in {} ({} ticket(s))", entries.size(), dimension, tickets.activeTickets());
		return entries.size();
	}

	public LoaderSize sizeAt(TablePos pos) {
		return store.get(pos);
	}

	/** Advances the table to its next size and returns it. */
	public LoaderSize cycle(TablePos pos) {
		LoaderSize old = store.get(pos);
		LoaderSize next = old.next();
		store.set(pos, next);
		// Apply before release: a table moving between sizes never drops chunks the two sizes share.
		tickets.apply(pos, next);
		tickets.release(pos, old);
		LOGGER.debug("Table {} in {}: {} -> {}", pos, dimension, old.side(), next.side());
		return next;
	}

	/** Forgets a table that no longer exists. Returns whether it had a size. */
	public boolean remove(TablePos pos, String reason) {
		LoaderSize old = store.get(pos);
		if (old.isOff()) {
			return false;
		}
		store.set(pos, LoaderSize.OFF);
		tickets.release(pos, old);
		LOGGER.info("Released crafting table loader {} in {} (was size {}): {}", pos, dimension, old.side(), reason);
		return true;
	}

	/**
	 * Removes every entry whose block is loaded but no longer a crafting table. This is the
	 * guarantee behind the removal hook, which vanilla skips for some block updates.
	 * Unloaded positions are left alone: an unloaded chunk proves nothing about its block.
	 */
	public int validate(Predicate<TablePos> isLoaded, Predicate<TablePos> isCraftingTable) {
		int removed = 0;
		for (TablePos pos : store.snapshot().keySet()) {
			if (isLoaded.test(pos) && !isCraftingTable.test(pos) && remove(pos, "no longer a crafting table")) {
				removed++;
			}
		}
		return removed;
	}

	/** The level is going away and its tickets with it; drop our counts to match. */
	public void unload() {
		tickets.forget();
	}
}
