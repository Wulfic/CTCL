package net.wulfnet.ctcl.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.wulfnet.ctcl.server.CtclServer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Releases a table's tickets the moment its block is removed or pushed. Added as an
 * override on CraftingTableBlock only, so no other block pays for it. Best-effort:
 * vanilla skips this call for block updates without UPDATE_NEIGHBORS, and the periodic
 * validation in {@link CtclServer} catches those.
 */
@Mixin(CraftingTableBlock.class)
public abstract class CraftingTableBlockMixin extends Block {
	private CraftingTableBlockMixin(Properties properties) {
		super(properties);
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
		CtclServer.onTableBlockRemoved(level, pos);
	}
}
