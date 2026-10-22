package net.wulfnet.ctcl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class LoaderSizeTest {
	@Test
	void sixClicksFromOffReadOneThreeFiveSevenNineThenOff() {
		List<Integer> labels = new ArrayList<>();
		LoaderSize size = LoaderSize.OFF;
		for (int click = 0; click < 6; click++) {
			size = size.next();
			labels.add(size.side());
		}
		assertEquals(List.of(1, 3, 5, 7, 9, 0), labels);
	}

	@Test
	void nineWrapsToOff() {
		assertEquals(LoaderSize.OFF, LoaderSize.NINE.next());
	}

	@ParameterizedTest
	@EnumSource(LoaderSize.class)
	void chunkCountIsSideSquared(LoaderSize size) {
		assertEquals(size.side() * size.side(), size.chunkCount());
	}

	@Test
	void radiusIsHalfTheSideRoundedDown() {
		assertEquals(0, LoaderSize.ONE.radius());
		assertEquals(1, LoaderSize.THREE.radius());
		assertEquals(4, LoaderSize.NINE.radius());
	}

	@Test
	void offHasNoRadius() {
		assertThrows(IllegalStateException.class, LoaderSize.OFF::radius);
	}

	@Test
	void onlyOffIsOff() {
		assertTrue(LoaderSize.OFF.isOff());
		for (LoaderSize size : LoaderSize.values()) {
			if (size != LoaderSize.OFF) {
				assertFalse(size.isOff(), size.name());
			}
		}
	}

	@ParameterizedTest
	@EnumSource(LoaderSize.class)
	void ofSideRoundTrips(LoaderSize size) {
		assertEquals(Optional.of(size), LoaderSize.ofSide(size.side()));
	}

	@ParameterizedTest
	@ValueSource(ints = {-1, 2, 4, 8, 10, 11, 99, Integer.MIN_VALUE, Integer.MAX_VALUE})
	void ofSideRejectsAnythingElse(int side) {
		assertEquals(Optional.empty(), LoaderSize.ofSide(side));
	}
}
