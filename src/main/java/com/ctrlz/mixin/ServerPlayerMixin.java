package com.ctrlz.mixin;

import com.ctrlz.server.Hooks;
import com.ctrlz.server.Recorder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
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

	/**
	 * The drop key and inventory drops call the three argument overload directly, so hooking the two
	 * argument one never saw them.
	 */
	@Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;", at = @At("HEAD"))
	private void ctrlz$beforeDrop(ItemStack stack, boolean dropAround, boolean thrower,
			CallbackInfoReturnable<ItemEntity> info) {
		Hooks.beginPlayerDrop();
	}

	@Inject(method = "drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;", at = @At("RETURN"))
	private void ctrlz$afterDrop(ItemStack stack, boolean dropAround, boolean thrower,
			CallbackInfoReturnable<ItemEntity> info) {
		Hooks.endPlayerDrop((ServerPlayer) (Object) this, stack, info.getReturnValue());
	}
}

