package net.wulfnet.ctcl.core;

import java.util.Optional;

/**
 * The side length of the square of chunks a crafting table keeps loaded. {@link #OFF} is
 * the default for every table. Pure logic: no Minecraft imports, identical in every band.
 */
public enum LoaderSize {
	OFF(0),
	ONE(1),
	THREE(3),
	FIVE(5),
	SEVEN(7),
	NINE(9);

	private final int side;

	LoaderSize(int side) {
		this.side = side;
	}

	public int side() {
		return side;
	}

	public boolean isOff() {
		return this == OFF;
	}

	/** Chunks from the centre chunk to the edge of the square. Throws for {@link #OFF}, which has no square. */
	public int radius() {
		if (isOff()) {
			throw new IllegalStateException("OFF has no radius");
		}
		return (side - 1) / 2;
	}

	public int chunkCount() {
		return side * side;
	}

	/** The size the button moves to on click: 0 → 1 → 3 → 5 → 7 → 9 → 0. */
	public LoaderSize next() {
		LoaderSize[] all = values();
		return all[(ordinal() + 1) % all.length];
	}

	/** The size with this side length, or empty for any value that isn't one (e.g. from a corrupt save). */
	public static Optional<LoaderSize> ofSide(int side) {
		for (LoaderSize size : values()) {
			if (size.side == side) {
				return Optional.of(size);
			}
		}
		return Optional.empty();
	}
}
