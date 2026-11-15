package com.ctrlz.v2;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Event wiring of the v2 engine. Registered only when {@code ctrlz.engine=v2}, so the legacy
 * recorder never sees the same break twice.
 */
public final class V2Events {
	private static final Logger LOGGER = LoggerFactory.getLogger("CtrlZ");

	private V2Events() {
	}

	public static void register() {
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
				try {
					V2Recorder.beginBreak(serverPlayer, serverLevel, pos);
				} catch (Throwable t) {
					LOGGER.error("CtrlZ v2 failed to open a break transaction at {}", pos, t);
				}
			}
			return true;
		});

		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (player instanceof ServerPlayer serverPlayer && level instanceof ServerLevel serverLevel) {
				try {
					V2Recorder.endBreak(serverPlayer, serverLevel, pos);
				} catch (Throwable t) {
					LOGGER.error("CtrlZ v2 failed to close a break transaction at {}", pos, t);
				}
			}
		});
	}
}
