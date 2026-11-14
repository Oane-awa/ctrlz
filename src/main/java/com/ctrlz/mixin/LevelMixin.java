package com.ctrlz.mixin;

import com.ctrlz.server.Hooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks the four argument {@code setBlock}, the one every block change finally goes through. The
 * three argument overload only forwards to it, so hooking that one missed direct callers such as
 * {@code destroyBlock}.
 */
@Mixin(Level.class)
public abstract class LevelMixin {
	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
	private void ctrlz$beforeSetBlock(BlockPos pos, BlockState state, int flags, int recursion,
			CallbackInfoReturnable<Boolean> info) {
		Hooks.beforeSetBlock((Level) (Object) this, pos, ((Level) (Object) this).getBlockState(pos));
	}

	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
	private void ctrlz$afterSetBlock(BlockPos pos, BlockState state, int flags, int recursion,
			CallbackInfoReturnable<Boolean> info) {
		Hooks.afterSetBlock((Level) (Object) this, pos, ((Level) (Object) this).getBlockState(pos));
	}
}

