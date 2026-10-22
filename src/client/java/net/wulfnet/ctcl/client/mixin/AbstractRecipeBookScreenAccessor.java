package net.wulfnet.ctcl.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** In a narrow window the open recipe book is drawn over the crafting GUI, not beside it. */
@Mixin(AbstractRecipeBookScreen.class)
public interface AbstractRecipeBookScreenAccessor {
	@Accessor("recipeBookComponent")
	RecipeBookComponent<?> ctcl$getRecipeBookComponent();

	@Accessor("widthTooNarrow")
	boolean ctcl$isWidthTooNarrow();
}
