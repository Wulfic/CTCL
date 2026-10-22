package net.wulfnet.ctcl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class SizeSlotTest {
	@Test
	void emptyUntilTheServerSpeaks() {
		assertEquals(Optional.empty(), new SizeSlot().sizeFor(3));
	}

	@Test
	void returnsTheSizeForTheMatchingMenuOnly() {
		SizeSlot slot = new SizeSlot();
		slot.accept(3, 5);
		assertEquals(Optional.of(LoaderSize.FIVE), slot.sizeFor(3));
		assertEquals(Optional.empty(), slot.sizeFor(4));
	}

	@Test
	void aNewerMenuReplacesTheOlder() {
		SizeSlot slot = new SizeSlot();
		slot.accept(3, 5);
		slot.accept(4, 0);
		assertEquals(Optional.empty(), slot.sizeFor(3));
		assertEquals(Optional.of(LoaderSize.OFF), slot.sizeFor(4));
	}

	@Test
	void anInvalidSizeIsIgnoredAndKeepsTheLastGoodValue() {
		SizeSlot slot = new SizeSlot();
		slot.accept(3, 5);
		slot.accept(3, 4);
		slot.accept(9, -1);
		assertEquals(Optional.of(LoaderSize.FIVE), slot.sizeFor(3));
		assertEquals(Optional.empty(), slot.sizeFor(9));
	}

	@Test
	void clearForgetsEverything() {
		SizeSlot slot = new SizeSlot();
		slot.accept(3, 5);
		slot.clear();
		assertEquals(Optional.empty(), slot.sizeFor(3));
	}
}
