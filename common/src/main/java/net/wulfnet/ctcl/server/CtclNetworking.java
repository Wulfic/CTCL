package net.wulfnet.ctcl.server;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.wulfnet.ctcl.core.CycleRequests;
import net.wulfnet.ctcl.core.LoaderSize;
import net.wulfnet.ctcl.core.OpenTableMenu;
import net.wulfnet.ctcl.core.TablePos;
import net.wulfnet.ctcl.mixin.CraftingMenuAccessor;
import net.wulfnet.ctcl.net.LoaderSizeS2C;

/**
 * Server side of the button: validates cycle requests and keeps every open menu on a table in sync.
 * Each loader registers the payload types and routes {@code CycleLoaderC2S} to {@link #onCycle}.
 */
public final class CtclNetworking {
	private static PayloadSender sender;

	private CtclNetworking() {
	}

	/** Called once by the loader's entrypoint, before any level loads. */
	public static void init(PayloadSender loaderSender) {
		sender = loaderSender;
	}

	/** Handles a cycle request. Returns the new size, or empty if the request was rejected. */
	public static Optional<LoaderSize> onCycle(ServerPlayer player, int containerId) {
		MenuView menu = new MenuView(player, player.containerMenu);
		return CycleRequests.resolve(player.getScoreboardName(), containerId, menu)
			.flatMap(pos -> CtclServer.requireService(menu.level, "a cycle request from " + player.getScoreboardName()).map(service -> {
				LoaderSize size = service.cycle(pos);
				broadcast(menu.level, pos, size);
				return size;
			}));
	}

	public static void onMenuOpened(ServerPlayer player) {
		if (!(player.containerMenu instanceof CraftingMenu crafting)) {
			return;
		}
		MenuView menu = new MenuView(player, crafting);
		menu.tablePos().ifPresent(pos -> CtclServer.requireService(menu.level, "a crafting menu opening")
			.ifPresent(service -> send(player, crafting.containerId, service.sizeAt(pos))));
	}

	/** Every player looking at this table sees the new size, not just the one who clicked. */
	private static void broadcast(ServerLevel level, TablePos pos, LoaderSize size) {
		for (ServerPlayer viewer : level.getServer().getPlayerList().getPlayers()) {
			if (viewer.containerMenu instanceof CraftingMenu crafting) {
				MenuView view = new MenuView(viewer, crafting);
				if (view.level == level && view.tablePos().equals(Optional.of(pos))) {
					send(viewer, crafting.containerId, size);
				}
			}
		}
	}

	private static void send(ServerPlayer player, int containerId, LoaderSize size) {
		if (sender == null) {
			throw new IllegalStateException("CtclNetworking.init was never called: the loader entrypoint didn't wire networking");
		}
		// A LAN guest without the mod can't receive it: they simply get no button.
		if (sender.canSend(player, LoaderSizeS2C.TYPE)) {
			sender.send(player, new LoaderSizeS2C(containerId, size.side()));
		}
	}

	/** Adapts a live menu to the pure validation in {@link CycleRequests}. */
	private static final class MenuView implements OpenTableMenu {
		private final ServerPlayer player;
		private final AbstractContainerMenu menu;
		private final ServerLevel level;
		private final BlockPos blockPos;

		MenuView(ServerPlayer player, AbstractContainerMenu menu) {
			this.player = player;
			this.menu = menu;
			// evaluate() wraps the result in Optional.of, so the lambda must never return null.
			Optional<Located> located = menu instanceof CraftingMenu crafting
				? ((CraftingMenuAccessor) crafting).ctcl$getAccess().evaluate(Located::new)
				: Optional.empty();
			this.level = located.map(Located::level).filter(ServerLevel.class::isInstance).map(ServerLevel.class::cast).orElse(null);
			this.blockPos = this.level == null ? null : located.get().pos();
		}

		@Override
		public int containerId() {
			return menu.containerId;
		}

		@Override
		public boolean isCraftingMenu() {
			return menu instanceof CraftingMenu;
		}

		@Override
		public Optional<TablePos> tablePos() {
			return blockPos == null ? Optional.empty() : Optional.of(LoaderData.toTablePos(blockPos));
		}

		@Override
		public boolean stillValid() {
			return menu.stillValid(player);
		}

		@Override
		public boolean isCraftingTableAt(TablePos pos) {
			return level != null && level.getBlockState(LoaderData.toBlockPos(pos)).is(Blocks.CRAFTING_TABLE);
		}

		private record Located(Level level, BlockPos pos) {
		}
	}
}
