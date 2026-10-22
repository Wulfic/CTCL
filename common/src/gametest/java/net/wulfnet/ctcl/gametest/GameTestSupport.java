package net.wulfnet.ctcl.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.wulfnet.ctcl.core.LoaderService;
import net.wulfnet.ctcl.core.LoaderSize;
import net.wulfnet.ctcl.core.TablePos;
import net.wulfnet.ctcl.server.CtclServer;
import net.wulfnet.ctcl.server.LoaderData;

/**
 * Shared setup. Each test owns a numbered region far from spawn and from every other test,
 * because tests run in parallel and the server gametest world persists between runs.
 */
final class GameTestSupport {
	private static final int REGION_SPACING = 4096;
	private static final int REGION_BASE = 200_000;
	private static final int Y = -60;

	private GameTestSupport() {
	}

	/**
	 * Places a crafting table in region {@code index}, loading its chunk first, and clears
	 * the blocks around it so no test depends on what an earlier run left behind.
	 */
	static BlockPos placeTable(ServerLevel level, int index) {
		// Middle of a chunk, so a table on the chunk's edge can't mask an off-by-one.
		BlockPos pos = new BlockPos(REGION_BASE + index * REGION_SPACING + 8, Y, REGION_BASE + 8);
		level.getChunk(pos);
		for (BlockPos around : BlockPos.betweenClosed(pos.offset(-3, 0, -3), pos.offset(3, 1, 3))) {
			level.setBlock(around, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
		level.setBlock(pos, Blocks.CRAFTING_TABLE.defaultBlockState(), Block.UPDATE_ALL);
		return pos;
	}

	/**
	 * Joins a real player with an in-memory connection. Same steps as vanilla's
	 * {@code makeMockServerPlayerInLevel}, which 1.21.11 deprecates for removal. It can't
	 * receive mod payloads, like a LAN guest without the mod. Remove it with
	 * {@code getPlayerList().remove(player)}.
	 */
	static ServerPlayer joinTestPlayer(ServerLevel level) {
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "ctcl-test-player"), false);
		ServerPlayer player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
		return player;
	}

	static LoaderService service(ServerLevel level) {
		return CtclServer.service(level).orElseThrow(() -> new IllegalStateException("no loader service for " + level.dimension()));
	}

	static void setSize(LoaderService service, BlockPos pos, LoaderSize size) {
		TablePos table = table(pos);
		for (int clicks = 0; service.sizeAt(table) != size; clicks++) {
			if (clicks > LoaderSize.values().length) {
				throw new IllegalStateException("cycle never reached " + size);
			}
			service.cycle(table);
		}
	}

	static TablePos table(BlockPos pos) {
		return LoaderData.toTablePos(pos);
	}

}
