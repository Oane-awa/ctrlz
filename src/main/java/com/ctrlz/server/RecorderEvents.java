package com.ctrlz.server;

import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Fabric event wiring. Only events that fire authoritatively on the server are used; movement,
 * container clicks and block changes come from mixins because vanilla has no event for them.
 */
public final class RecorderEvents {
	private RecorderEvents() {
	}

	public static void register() {
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
				try {
					Recorder.onBlockBreak(serverPlayer, serverLevel, pos, state, blockEntity);
				} catch (Throwable ignored) {
				}
			}
			return true;
		});

		UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
			if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel) {
				try {
					ItemStack held = player.getItemInHand(hand);
					if (Recorder.isBlockItem(held)) {
						Recorder.onBlockPlaced(serverPlayer, hitResult.getBlockPos(), held);
					} else {
						Recorder.onItemUsed(serverPlayer, held);
					}
				} catch (Throwable ignored) {
				}
			}
			return InteractionResult.PASS;
		});

		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel) {
				try {
					Recorder.onItemUsed(serverPlayer, player.getItemInHand(hand));
				} catch (Throwable ignored) {
				}
			}
			return InteractionResult.PASS;
		});

		AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
			if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel) {
				try {
					Recorder.onAttack(serverPlayer, entity, isCritical(serverPlayer));
				} catch (Throwable ignored) {
				}
			}
			return InteractionResult.PASS;
		});
	}

	/** Vanilla's own critical hit conditions, evaluated before the damage is applied. */
	public static boolean isCritical(Player player) {
		return player.fallDistance > 0.0F && !player.onGround() && !player.onClimbable() && !player.isInWater()
				&& !player.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS) && !player.isPassenger()
				&& !player.isSprinting() && player.getAttackStrengthScale(0.5F) > 0.9F;
	}

	private static boolean unused(Level level, InteractionHand hand) {
		return level == null || hand == null;
	}
}
