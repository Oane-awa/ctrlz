package com.ctrlz.mixin;

import com.ctrlz.server.Recorder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
	@Inject(method = "openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;", at = @At("RETURN"))
	private void ctrlz$onOpenMenu(MenuProvider provider, CallbackInfoReturnable<OptionalInt> info) {
		if (info.getReturnValue().isPresent()) {
			Recorder.onContainerOpened((ServerPlayer) (Object) this);
		}
	}
}
