package net.wulfnet.ctcl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CycleRequestsTest {
	private static final TablePos TABLE = new TablePos(40, 64, 40);
	private static final int OPEN_ID = 7;

	@Mock
	private OpenTableMenu menu;

	@Test
	void aValidRequestResolvesToTheMenusTable() {
		when(menu.containerId()).thenReturn(OPEN_ID);
		when(menu.isCraftingMenu()).thenReturn(true);
		when(menu.tablePos()).thenReturn(Optional.of(TABLE));
		when(menu.stillValid()).thenReturn(true);
		when(menu.isCraftingTableAt(TABLE)).thenReturn(true);

		assertEquals(Optional.of(TABLE), CycleRequests.resolve("p", OPEN_ID, menu));
	}

	@Test
	void aWrongContainerIdIsRejectedBeforeLookingAtTheMenu() {
		when(menu.containerId()).thenReturn(OPEN_ID);

		assertEquals(Optional.empty(), CycleRequests.resolve("p", OPEN_ID + 1, menu));
		verify(menu, never()).tablePos();
	}

	@Test
	void aNonCraftingMenuIsRejected() {
		when(menu.containerId()).thenReturn(OPEN_ID);
		when(menu.isCraftingMenu()).thenReturn(false);

		assertEquals(Optional.empty(), CycleRequests.resolve("p", OPEN_ID, menu));
		verify(menu, never()).tablePos();
	}

	@Test
	void aCraftingMenuWithNoTableIsRejected() {
		when(menu.containerId()).thenReturn(OPEN_ID);
		when(menu.isCraftingMenu()).thenReturn(true);
		when(menu.tablePos()).thenReturn(Optional.empty());

		assertEquals(Optional.empty(), CycleRequests.resolve("p", OPEN_ID, menu));
	}

	@Test
	void aStaleMenuIsRejected() {
		when(menu.containerId()).thenReturn(OPEN_ID);
		when(menu.isCraftingMenu()).thenReturn(true);
		when(menu.tablePos()).thenReturn(Optional.of(TABLE));
		when(menu.stillValid()).thenReturn(false);

		assertEquals(Optional.empty(), CycleRequests.resolve("p", OPEN_ID, menu));
		verify(menu, never()).isCraftingTableAt(TABLE);
	}

	@Test
	void aMenuWhoseBlockIsNoLongerACraftingTableIsRejected() {
		when(menu.containerId()).thenReturn(OPEN_ID);
		when(menu.isCraftingMenu()).thenReturn(true);
		when(menu.tablePos()).thenReturn(Optional.of(TABLE));
		when(menu.stillValid()).thenReturn(true);
		when(menu.isCraftingTableAt(TABLE)).thenReturn(false);

		assertEquals(Optional.empty(), CycleRequests.resolve("p", OPEN_ID, menu));
	}
}
