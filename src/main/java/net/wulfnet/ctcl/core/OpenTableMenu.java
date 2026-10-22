package net.wulfnet.ctcl.core;

import java.util.Optional;

/** What the server can see of a player's open menu, reduced to what a cycle request needs. */
public interface OpenTableMenu {
	int containerId();

	boolean isCraftingMenu();

	/** The table the menu was opened on; empty for a menu with no block (e.g. {@code ContainerLevelAccess.NULL}). */
	Optional<TablePos> tablePos();

	/** Vanilla's own check: the block is still a crafting table and the player is in reach. */
	boolean stillValid();

	boolean isCraftingTableAt(TablePos pos);
}
