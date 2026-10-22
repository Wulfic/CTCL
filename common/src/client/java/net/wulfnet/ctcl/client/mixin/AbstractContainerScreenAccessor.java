package net.wulfnet.ctcl.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The GUI's on-screen origin, which moves when the recipe book opens or closes. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
	@Accessor("leftPos")
	int ctcl$getLeftPos();

	@Accessor("topPos")
	int ctcl$getTopPos();

	@Accessor("imageWidth")
	int ctcl$getImageWidth();
}
