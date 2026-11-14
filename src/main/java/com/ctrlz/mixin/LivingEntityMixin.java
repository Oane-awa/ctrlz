package com.ctrlz.mixin;

import com.ctrlz.server.Recorder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "jumpFromGround()V", at = @At("RETURN"))
	private void ctrlz$onJump(CallbackInfo info) {
		if ((Object) this instanceof ServerPlayer player) {
			Recorder.onJump(player);
		}
	}
}
