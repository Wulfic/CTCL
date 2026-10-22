package net.wulfnet.ctcl.gametest;

import static net.wulfnet.ctcl.gametest.GameTestSupport.placeTable;
import static net.wulfnet.ctcl.gametest.GameTestSupport.service;
import static net.wulfnet.ctcl.gametest.GameTestSupport.table;

import java.util.Optional;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.wulfnet.ctcl.core.LoaderService;
import net.wulfnet.ctcl.core.LoaderSize;
import net.wulfnet.ctcl.server.CtclNetworking;

/**
 * Drives the real cycle handler with a real player and a real {@code CraftingMenu}, so the
 * accessor mixin, vanilla's reach check, and the menu-open hook are all exercised. Every
 * rejection must leave the table's size untouched.
 *
 * <p>Loader-neutral bodies: each loader registers every case under the same name, with the
 * tick limit in {@code maxTicks} beside it.
 */
public final class NetworkingCases {
	private NetworkingCases() {
	}

	public static void aCycleFromTheOpenTableMenuAdvancesTheSize(GameTestHelper helper) {
		withPlayerAtTable(helper, 10, (player, pos) -> {
			openTable(player, pos);
			int id = player.containerMenu.containerId;

			helper.assertTrue(CtclNetworking.onCycle(player, id).equals(Optional.of(LoaderSize.ONE)), "first click should give 1");
			helper.assertTrue(CtclNetworking.onCycle(player, id).equals(Optional.of(LoaderSize.THREE)), "second click should give 3");
			helper.assertTrue(sizeAt(helper, pos) == LoaderSize.THREE, "stored size should be 3");
		});
	}

	public static void aWrongContainerIdChangesNothing(GameTestHelper helper) {
		withPlayerAtTable(helper, 11, (player, pos) -> {
			openTable(player, pos);

			helper.assertTrue(CtclNetworking.onCycle(player, player.containerMenu.containerId + 1).isEmpty(), "request should be rejected");
			helper.assertTrue(sizeAt(helper, pos).isOff(), "size should be unchanged");
		});
	}

	public static void aPlayerWithNoMenuOpenChangesNothing(GameTestHelper helper) {
		withPlayerAtTable(helper, 12, (player, pos) -> {
			// containerMenu is the player's own inventory menu, id 0.
			helper.assertTrue(CtclNetworking.onCycle(player, player.containerMenu.containerId).isEmpty(), "request should be rejected");
			helper.assertTrue(sizeAt(helper, pos).isOff(), "size should be unchanged");
		});
	}

	public static void aPlayerOutOfReachChangesNothing(GameTestHelper helper) {
		withPlayerAtTable(helper, 13, (player, pos) -> {
			openTable(player, pos);
			player.setPos(pos.getX() + 30.5, pos.getY(), pos.getZ() + 0.5);

			helper.assertTrue(CtclNetworking.onCycle(player, player.containerMenu.containerId).isEmpty(), "request should be rejected");
			helper.assertTrue(sizeAt(helper, pos).isOff(), "size should be unchanged");
		});
	}

	public static void aTableBrokenWhileItsMenuIsOpenChangesNothing(GameTestHelper helper) {
		withPlayerAtTable(helper, 14, (player, pos) -> {
			openTable(player, pos);
			int id = player.containerMenu.containerId;
			helper.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

			helper.assertTrue(CtclNetworking.onCycle(player, id).isEmpty(), "request should be rejected");
			helper.assertTrue(sizeAt(helper, pos).isOff(), "no entry should be created for the broken table");
		});
	}

	public static void aCrafterMenuChangesNothing(GameTestHelper helper) {
		withPlayerAtTable(helper, 15, (player, pos) -> {
			ServerLevel level = helper.getLevel();
			level.setBlock(pos, Blocks.CRAFTER.defaultBlockState(), Block.UPDATE_ALL);
			openTable(player, pos);
			helper.assertFalse(player.containerMenu == player.inventoryMenu, "the crafter menu should be open");

			helper.assertTrue(CtclNetworking.onCycle(player, player.containerMenu.containerId).isEmpty(), "request should be rejected");
			helper.assertTrue(sizeAt(helper, pos).isOff(), "size should be unchanged");
		});
	}

	/** Places a table in {@code region}, puts a real player beside it, runs {@code test}, and always removes the player. */
	private static void withPlayerAtTable(GameTestHelper helper, int region, BiConsumer<ServerPlayer, BlockPos> test) {
		ServerLevel level = helper.getLevel();
		BlockPos pos = placeTable(level, region);
		ServerPlayer player = GameTestSupport.joinTestPlayer(level);
		try {
			player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 1.5);
			test.accept(player, pos);
		} finally {
			player.closeContainer();
			level.getServer().getPlayerList().remove(player);
		}
		helper.succeed();
	}

	/** Opens whatever menu the block at {@code pos} provides; for a crafting table this runs the menu-open hook. */
	private static void openTable(ServerPlayer player, BlockPos pos) {
		ServerLevel level = player.level();
		player.openMenu(level.getBlockState(pos).getMenuProvider(level, pos));
	}

	private static LoaderSize sizeAt(GameTestHelper helper, BlockPos pos) {
		LoaderService service = service(helper.getLevel());
		return service.sizeAt(table(pos));
	}
}
