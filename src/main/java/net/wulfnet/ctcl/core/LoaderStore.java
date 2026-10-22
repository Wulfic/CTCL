package net.wulfnet.ctcl.core;

import java.util.Map;

/** The persisted table sizes for one dimension. Absent means {@link LoaderSize#OFF}. */
public interface LoaderStore {
	LoaderSize get(TablePos pos);

	/** Stores {@code size}; {@link LoaderSize#OFF} removes the entry. */
	void set(TablePos pos, LoaderSize size);

	/** A copy of every non-OFF entry, safe to iterate while calling {@link #set}. */
	Map<TablePos, LoaderSize> snapshot();
}
