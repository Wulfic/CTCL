package net.wulfnet.ctcl.core;

import java.util.HashMap;
import java.util.Map;

/** A {@link LoaderStore} backed by a map, for tests. Wrap in {@code Mockito.spy} to verify writes. */
class InMemoryLoaderStore implements LoaderStore {
	private final Map<TablePos, LoaderSize> sizes = new HashMap<>();

	@Override
	public LoaderSize get(TablePos pos) {
		return sizes.getOrDefault(pos, LoaderSize.OFF);
	}

	@Override
	public void set(TablePos pos, LoaderSize size) {
		if (size.isOff()) {
			sizes.remove(pos);
		} else {
			sizes.put(pos, size);
		}
	}

	@Override
	public Map<TablePos, LoaderSize> snapshot() {
		return Map.copyOf(sizes);
	}
}
