package net.wulfnet.ctcl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class ChunkAreaTest {
	@ParameterizedTest
	@CsvSource({
		"0, 0",
		"15, 0",
		"16, 1",
		"-1, -1",
		"-16, -1",
		"-17, -2",
		"30000000, 1875000",
		"-30000000, -1875000",
	})
	void chunkOfFloorsTowardNegativeInfinity(int block, int chunk) {
		assertEquals(chunk, ChunkArea.chunkOf(block));
	}

	@ParameterizedTest
	@EnumSource(LoaderSize.class)
	void countMatchesSize(LoaderSize size) {
		assertEquals(size.chunkCount(), ChunkArea.around(100, -100, size).count());
	}

	@Test
	void offIsEmpty() {
		ChunkArea area = ChunkArea.around(8, 8, LoaderSize.OFF);
		assertTrue(area.isEmpty());
		assertEquals(0, area.count());
		assertFalse(area.contains(0, 0));
	}

	@Test
	void fiveIsCentredOnTheTablesChunk() {
		// Block (40, 40) is in chunk (2, 2): a 5×5 spans chunks 0..4 on both axes.
		ChunkArea area = ChunkArea.around(40, 40, LoaderSize.FIVE);
		assertEquals(new ChunkArea(0, 0, 4, 4), area);
		assertTrue(area.contains(0, 0));
		assertTrue(area.contains(4, 4));
		assertTrue(area.contains(2, 2));
		assertFalse(area.contains(5, 2));
		assertFalse(area.contains(2, -1));
	}

	@Test
	void oneIsJustTheTablesChunk() {
		ChunkArea area = ChunkArea.around(-1, -1, LoaderSize.ONE);
		assertEquals(new ChunkArea(-1, -1, -1, -1), area);
		assertTrue(area.contains(-1, -1));
		assertFalse(area.contains(0, 0));
	}

	@Test
	void negativeCoordinatesUseTheFlooredChunk() {
		// Block x = -1 is in chunk -1, not 0 (the `/ 16` truncation bug).
		ChunkArea area = ChunkArea.around(-1, -17, LoaderSize.THREE);
		assertEquals(new ChunkArea(-2, -3, 0, -1), area);
	}

	@Test
	void tableOnAChunkBorderUsesItsOwnChunk() {
		// Block 15 is the last block of chunk 0; block 16 is the first of chunk 1.
		assertEquals(new ChunkArea(0, 0, 0, 0), ChunkArea.around(15, 15, LoaderSize.ONE));
		assertEquals(new ChunkArea(1, 1, 1, 1), ChunkArea.around(16, 16, LoaderSize.ONE));
	}
}
