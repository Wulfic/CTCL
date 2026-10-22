package net.wulfnet.ctcl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoaderServiceTest {
	private static final TablePos TABLE = new TablePos(40, 64, 40);
	private static final TablePos NEIGHBOUR = new TablePos(33, 64, 47);
	private static final TablePos FAR = new TablePos(-200, 70, 500);

	@Mock
	private TicketSink sink;
	private LoaderStore store;
	private LoaderService service;

	@BeforeEach
	void setUp() {
		store = spy(new InMemoryLoaderStore());
		service = new LoaderService("minecraft:overworld", store, new LoaderTickets(sink));
	}

	@Test
	void everyTableStartsOff() {
		assertEquals(LoaderSize.OFF, service.sizeAt(TABLE));
	}

	@Test
	void firstCycleStoresOneAndTicketsTheTablesChunk() {
		assertEquals(LoaderSize.ONE, service.cycle(TABLE));
		assertEquals(LoaderSize.ONE, store.get(TABLE));
		verify(sink).add(2, 2, 2);
		verifyNoMoreInteractions(sink);
	}

	@Test
	void growingAppliesTheNewTicketBeforeReleasingTheOld() {
		service.cycle(TABLE);
		service.cycle(TABLE);
		InOrder order = inOrder(sink);
		order.verify(sink).add(2, 2, 2);
		order.verify(sink).add(2, 2, 3);
		order.verify(sink).remove(2, 2, 2);
		verifyNoMoreInteractions(sink);
	}

	@Test
	void cyclingPastNineTurnsOffAndReleasesEverything() {
		for (int click = 0; click < 6; click++) {
			service.cycle(TABLE);
		}
		assertEquals(LoaderSize.OFF, service.sizeAt(TABLE));
		assertTrue(store.snapshot().isEmpty());
		for (int radius = 2; radius <= 6; radius++) {
			verify(sink).add(2, 2, radius);
			verify(sink).remove(2, 2, radius);
		}
		verifyNoMoreInteractions(sink);
	}

	@Test
	void turningOneOverlappingTableOffKeepsTheOthersChunks() {
		setSize(TABLE, LoaderSize.FIVE);
		setSize(NEIGHBOUR, LoaderSize.FIVE);

		assertTrue(service.remove(TABLE, "test"));
		verify(sink, never()).remove(2, 2, 4);

		assertTrue(service.remove(NEIGHBOUR, "test"));
		verify(sink).remove(2, 2, 4);
	}

	@Test
	void removingAnUnknownTableChangesNothing() {
		assertFalse(service.remove(TABLE, "test"));
		verify(store, never()).set(any(), any());
		verifyNoInteractions(sink);
	}

	@Test
	void validateRemovesLoadedEntriesThatAreNoLongerCraftingTables() {
		setSize(TABLE, LoaderSize.THREE);
		setSize(FAR, LoaderSize.NINE);

		int removed = service.validate(pos -> true, pos -> pos.equals(FAR));

		assertEquals(1, removed);
		assertEquals(LoaderSize.OFF, service.sizeAt(TABLE));
		assertEquals(LoaderSize.NINE, service.sizeAt(FAR));
		verify(sink).remove(2, 2, 3);
	}

	@Test
	void validateNeverJudgesAnUnloadedPosition() {
		setSize(TABLE, LoaderSize.THREE);
		clearInvocations(store, sink);

		assertEquals(0, service.validate(pos -> false, pos -> false));

		assertEquals(LoaderSize.THREE, service.sizeAt(TABLE));
		verify(store, never()).set(any(), any());
		verifyNoInteractions(sink);
	}

	@Test
	void validateOnAnEmptyStoreDoesNothing() {
		assertEquals(0, service.validate(pos -> true, pos -> false));
		verify(store, never()).set(any(), any());
		verifyNoInteractions(sink);
	}

	@Test
	void restoreReappliesEveryStoredTable() {
		store.set(TABLE, LoaderSize.FIVE);
		store.set(FAR, LoaderSize.ONE);

		assertEquals(2, service.restore());

		verify(sink).add(2, 2, 4);
		verify(sink).add(-13, 31, 2);
		verifyNoMoreInteractions(sink);
	}

	@Test
	void unloadThenRestoreReappliesInsteadOfDoubleCounting() {
		setSize(TABLE, LoaderSize.FIVE);
		service.unload();
		clearInvocations(sink);

		service.restore();
		service.remove(TABLE, "test");

		InOrder order = inOrder(sink);
		order.verify(sink).add(2, 2, 4);
		order.verify(sink).remove(2, 2, 4);
		verifyNoMoreInteractions(sink);
	}

	@Test
	void snapshotIsIsolatedFromLaterWrites() {
		setSize(TABLE, LoaderSize.ONE);
		Map<TablePos, LoaderSize> before = store.snapshot();
		service.cycle(TABLE);
		assertEquals(Set.of(TABLE), before.keySet());
		assertEquals(LoaderSize.ONE, before.get(TABLE));
	}

	/** Cycles {@code pos} from OFF up to {@code size}. */
	private void setSize(TablePos pos, LoaderSize size) {
		while (service.sizeAt(pos) != size) {
			service.cycle(pos);
		}
	}
}
