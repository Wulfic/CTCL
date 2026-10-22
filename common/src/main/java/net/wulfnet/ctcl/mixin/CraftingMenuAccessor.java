package net.wulfnet.ctcl.mixin;

import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The level and position a crafting menu was opened on: the server-side source of truth for which table a request means. */
@Mixin(CraftingMenu.class)
public interface CraftingMenuAccessor {
	@Accessor("access")
	ContainerLevelAccess ctcl$getAccess();
}
