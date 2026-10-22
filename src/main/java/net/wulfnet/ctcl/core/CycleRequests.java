package net.wulfnet.ctcl.core;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether a client's "cycle" request is allowed. The client only ever sends its
 * open container id; the table position comes from the server's own view of that menu,
 * so a client can never name an arbitrary block.
 */
public final class CycleRequests {
	private static final Logger LOGGER = LoggerFactory.getLogger("ctcl");

	private CycleRequests() {
	}

	/** The table to cycle, or empty (logged at DEBUG) if the request doesn't match an open, valid crafting table menu. */
	public static Optional<TablePos> resolve(String player, int requestedContainerId, OpenTableMenu menu) {
		if (requestedContainerId != menu.containerId()) {
			return reject(player, "container id " + requestedContainerId + " is not the open menu (" + menu.containerId() + ")");
		}
		if (!menu.isCraftingMenu()) {
			return reject(player, "open menu is not a crafting table menu");
		}
		Optional<TablePos> pos = menu.tablePos();
		if (pos.isEmpty()) {
			return reject(player, "crafting menu has no table position");
		}
		if (!menu.stillValid()) {
			return reject(player, "menu is no longer valid (table gone or out of reach)");
		}
		if (!menu.isCraftingTableAt(pos.get())) {
			return reject(player, "block at " + pos.get() + " is not a crafting table");
		}
		return pos;
	}

	private static Optional<TablePos> reject(String player, String reason) {
		LOGGER.debug("Ignoring cycle request from {}: {}", player, reason);
		return Optional.empty();
	}
}
