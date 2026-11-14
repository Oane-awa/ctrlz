package com.ctrlz.mixin;

import com.ctrlz.core.activity.ActivityKind;
import com.ctrlz.core.activity.ActivityRecord;
import com.ctrlz.core.activity.ActivityStat;
import com.ctrlz.server.CtrlZServer;
import com.ctrlz.server.Recorder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;", at = @At("RETURN"))
	private void ctrlz$onDrop(ItemStack stack, boolean dropAround, CallbackInfoReturnable<ItemEntity> info) {
		if ((Object) this instanceof ServerPlayer player && info.getReturnValue() != null) {
			Recorder.onItemDropped(player, stack);
		}
	}

	@Inject(method = "crit(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
	private void ctrlz$onCrit(Entity target, org.spongepowered.asm.mixin.injection.callback.CallbackInfo info) {
		if ((Object) this instanceof ServerPlayer player) {
			CtrlZServer hub = CtrlZServer.get();
			if (hub == null) {
				return;
			}
			ActivityRecord activity = hub.currentActivity(player);
			if (activity == null) {
				activity = hub.openAction(player, ActivityKind.ATTACK);
			}
			if (activity != null && activity.kind() == ActivityKind.ATTACK) {
				hub.addStat(activity, ActivityStat.CRITICAL_ATTACKS, 1);
			}
		}
	}
}
