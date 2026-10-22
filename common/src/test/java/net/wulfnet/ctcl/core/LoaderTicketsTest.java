package net.wulfnet.ctcl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoaderTicketsTest {
	private static final TablePos A = new TablePos(40, 64, 40);
	private static final TablePos B_SAME_CHUNK = new TablePos(33, 70, 47);

	@Mock
	private TicketSink sink;
	private LoaderTickets tickets;

	@BeforeEach
	void setUp() {
		tickets = new LoaderTickets(sink);
	}

	@Test
	void ticketRadiusAddsTheEntityTickingMargin() {
		assertEquals(2, LoaderTickets.ticketRadius(LoaderSize.ONE));
		assertEquals(4, LoaderTickets.ticketRadius(LoaderSize.FIVE));
		assertEquals(6, LoaderTickets.ticketRadius(LoaderSize.NINE));
	}

	@Test
	void applyAddsOneTicketAtTheTablesChunk() {
		tickets.apply(A, LoaderSize.FIVE);
		verify(sink).add(2, 2, 4);
		verifyNoMoreInteractions(sink);
		assertEquals(1, tickets.activeTickets());
	}

	@Test
	void offNeverTouchesTheSink() {
		tickets.apply(A, LoaderSize.OFF);
		tickets.release(A, LoaderSize.OFF);
		verifyNoInteractions(sink);
	}

	@Test
	void releaseBalancesApply() {
		tickets.apply(A, LoaderSize.THREE);
		tickets.release(A, LoaderSize.THREE);
		InOrder order = inOrder(sink);
		order.verify(sink).add(2, 2, 3);
		order.verify(sink).remove(2, 2, 3);
		verifyNoMoreInteractions(sink);
		assertEquals(0, tickets.activeTickets());
	}

	@Test
	void twoTablesSharingATicketKeepItUntilBothAreOff() {
		tickets.apply(A, LoaderSize.FIVE);
		tickets.apply(B_SAME_CHUNK, LoaderSize.FIVE);
		verify(sink).add(2, 2, 4);

		tickets.release(A, LoaderSize.FIVE);
		verifyNoMoreInteractions(sink);

		tickets.release(B_SAME_CHUNK, LoaderSize.FIVE);
		verify(sink).remove(2, 2, 4);
		verifyNoMoreInteractions(sink);
	}

	@Test
	void differentSizesInOneChunkAreIndependentTickets() {
		tickets.apply(A, LoaderSize.FIVE);
		tickets.apply(B_SAME_CHUNK, LoaderSize.NINE);
		tickets.release(B_SAME_CHUNK, LoaderSize.NINE);
		verify(sink).add(2, 2, 4);
		verify(sink).add(2, 2, 6);
		verify(sink).remove(2, 2, 6);
		verifyNoMoreInteractions(sink);
		assertEquals(1, tickets.activeTickets());
	}

	@Test
	void releaseWithoutApplyChangesNothing() {
		tickets.release(A, LoaderSize.FIVE);
		verifyNoInteractions(sink);
		assertEquals(0, tickets.activeTickets());
	}

	@Test
	void aStrayReleaseCannotStealAnotherTablesTicket() {
		tickets.apply(A, LoaderSize.FIVE);
		tickets.release(A, LoaderSize.FIVE);
		tickets.apply(B_SAME_CHUNK, LoaderSize.FIVE);
		tickets.release(A, LoaderSize.FIVE);
		InOrder order = inOrder(sink);
		order.verify(sink).add(2, 2, 4);
		order.verify(sink).remove(2, 2, 4);
		order.verify(sink).add(2, 2, 4);
		verifyNoMoreInteractions(sink);
		assertEquals(1, tickets.activeTickets());
	}

	@Test
	void aDuplicateApplyNeedsOnlyOneRelease() {
		tickets.apply(A, LoaderSize.FIVE);
		tickets.apply(A, LoaderSize.FIVE);
		tickets.release(A, LoaderSize.FIVE);
		verify(sink).add(2, 2, 4);
		verify(sink).remove(2, 2, 4);
		verifyNoMoreInteractions(sink);
		assertEquals(0, tickets.activeTickets());
	}

	@Test
	void forgetDropsHoldersWithoutTouchingTheSink() {
		tickets.apply(A, LoaderSize.FIVE);
		tickets.forget();
		verify(sink).add(2, 2, 4);
		verifyNoMoreInteractions(sink);
		assertEquals(0, tickets.activeTickets());
	}
}
