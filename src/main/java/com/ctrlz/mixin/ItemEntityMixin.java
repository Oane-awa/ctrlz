package com.ctrlz.mixin;

import com.ctrlz.engine.EngineRouter;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
	@Inject(method = "playerTouch(Lnet/minecraft/world/entity/player/Player;)V", at = @At("HEAD"))
	private void ctrlz$beforePlayerTouch(Player player, CallbackInfo info) {
		EngineRouter.routeItemTouchBefore((ItemEntity) (Object) this);
	}

	@Inject(method = "playerTouch(Lnet/minecraft/world/entity/player/Player;)V", at = @At("RETURN"))
	private void ctrlz$afterPlayerTouch(Player player, CallbackInfo info) {
		ItemEntity self = (ItemEntity) (Object) this;
		if (!self.level().isClientSide()) {
			EngineRouter.routeItemTouchAfter(self, player);
		}
	}
}
