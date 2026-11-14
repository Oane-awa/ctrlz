package com.ctrlz.mixin;

import com.ctrlz.server.Hooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	@Inject(method = "addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z", at = @At("RETURN"))
	private void ctrlz$afterAddFreshEntity(Entity entity, CallbackInfoReturnable<Boolean> info) {
		Hooks.afterAddEntity((ServerLevel) (Object) this, entity, info.getReturnValueZ());
	}
}
