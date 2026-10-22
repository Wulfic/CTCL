package net.wulfnet.ctcl.mixin;

import java.util.OptionalInt;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.wulfnet.ctcl.server.CtclNetworking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sends a newly opened crafting table menu its current size. {@code containerMenu} is set by the time openMenu returns. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
	@Inject(method = "openMenu", at = @At("RETURN"))
	private void ctcl$afterOpenMenu(MenuProvider provider, CallbackInfoReturnable<OptionalInt> cir) {
		if (cir.getReturnValue().isPresent()) {
			CtclNetworking.onMenuOpened((ServerPlayer) (Object) this);
		}
	}
}
