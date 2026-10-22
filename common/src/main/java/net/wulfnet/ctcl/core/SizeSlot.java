package net.wulfnet.ctcl.core;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's latest known size, for one menu. Holds a single (container id, size) pair:
 * the server's size packet and the screen's init can arrive in either order, and only the
 * newest menu matters. Pure logic, client thread only.
 */
public final class SizeSlot {
	private static final Logger LOGGER = LoggerFactory.getLogger("ctcl");

	private int containerId = -1;
	private LoaderSize size;

	/** Records a size from the server. An invalid side is ignored and logged, never displayed. */
	public void accept(int containerId, int side) {
		Optional<LoaderSize> parsed = LoaderSize.ofSide(side);
		if (parsed.isEmpty()) {
			LOGGER.warn("Ignoring invalid loader size {} from server for container {}", side, containerId);
			return;
		}
		this.containerId = containerId;
		this.size = parsed.get();
	}

	/** The size for this menu, or empty if the server hasn't said yet. */
	public Optional<LoaderSize> sizeFor(int containerId) {
		return size != null && this.containerId == containerId ? Optional.of(size) : Optional.empty();
	}

	public void clear() {
		containerId = -1;
		size = null;
	}
}
