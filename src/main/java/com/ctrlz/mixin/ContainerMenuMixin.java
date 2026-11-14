package com.ctrlz.mixin;

import com.ctrlz.server.Hooks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerMenu.class)
public abstract class ContainerMenuMixin {
	@Inject(method = "clicked(IILnet/minecraft/world/inventory/ContainerInput;Lnet/minecraft/world/entity/player/Player;)V", at = @At("HEAD"))
	private void ctrlz$beforeClick(int slotIndex, int button, ContainerInput input, Player player,
			CallbackInfo info) {
		Hooks.beforeMenuClick((AbstractContainerMenu) (Object) this, slotIndex, player);
	}

	@Inject(method = "clicked(IILnet/minecraft/world/inventory/ContainerInput;Lnet/minecraft/world/entity/player/Player;)V", at = @At("RETURN"))
	private void ctrlz$afterClick(int slotIndex, int button, ContainerInput input, Player player,
			CallbackInfo info) {
		Hooks.afterMenuClick((AbstractContainerMenu) (Object) this, slotIndex, player);
	}
}
