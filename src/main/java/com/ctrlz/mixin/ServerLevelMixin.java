package com.ctrlz.mixin;

import com.ctrlz.engine.EngineRouter;
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
		EngineRouter.routeAddEntity((ServerLevel) (Object) this, entity, info.getReturnValueZ());
	}
}
