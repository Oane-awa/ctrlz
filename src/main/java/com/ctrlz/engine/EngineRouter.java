package com.ctrlz.engine;

import com.ctrlz.server.Hooks;
import com.ctrlz.server.Recorder;
import com.ctrlz.v2.V2Recorder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one and only place where "which undo engine is running" is decided.
 *
 * <p>Both engines ship in the same jar, but exactly one of them is active for the whole session.
 * Mixins and event callbacks never look at the engine themselves: they call the {@code route*}
 * methods below, which is what keeps the two engines from ever running side by side (a half
 * migrated state would break atomicity in a way that is very hard to debug).
 *
 * <p>The engine is resolved once, from {@code ctrlz.engine}, before any hook is registered. There is
 * no hot switching: a later {@link #resolve(Engine)} call with a different value is ignored with a
 * warning. Switching engines is only supported by restarting the game.
 */
public final class EngineRouter {
	private static final Logger LOGGER = LoggerFactory.getLogger("CtrlZ");

	public enum Engine {
		LEGACY,
		V2;

		public static Engine parse(String raw) {
			return raw != null && "v2".equalsIgnoreCase(raw.trim()) ? V2 : LEGACY;
		}

		public String id() {
			return this == V2 ? "v2" : "legacy";
		}
	}

	private static volatile Engine active = Engine.LEGACY;
	private static volatile boolean resolved;

	private EngineRouter() {
	}

	/** Resolves the engine. Only the first call wins; this is not a toggle. */
	public static void resolve(Engine engine) {
		if (resolved) {
			if (active != engine) {
				LOGGER.warn("CtrlZ engine is already '{}'; ignoring request to switch to '{}'. "
						+ "Engine selection only applies at startup.", active.id(), engine.id());
			}
			return;
		}
		active = engine;
		resolved = true;
		LOGGER.info("CtrlZ engine resolved to '{}'", engine.id());
	}

	public static Engine active() {
		return active;
	}

	public static boolean isLegacy() {
		return active == Engine.LEGACY;
	}

	public static boolean isV2() {
		return active == Engine.V2;
	}

	public static boolean resolved() {
		return resolved;
	}

	/** Test hook: forget the resolution so a unit test can start from a known engine. */
	public static void resetForTest(Engine engine) {
		active = engine;
		resolved = true;
	}

	// ------------------------------------------------------------------ routing surface

	public static void routeSetBlockBefore(Level level, BlockPos pos, BlockState previous) {
		if (isLegacy()) {
			Hooks.beforeSetBlock(level, pos, previous);
		} else {
			V2Recorder.beforeSetBlock(level, pos);
		}
	}

	public static void routeSetBlockAfter(Level level, BlockPos pos, BlockState current) {
		if (isLegacy()) {
			Hooks.afterSetBlock(level, pos, current);
		} else {
			V2Recorder.afterSetBlock(level, pos);
		}
	}

	public static void routeAddEntity(ServerLevel level, Entity entity, boolean added) {
		if (isLegacy()) {
			Hooks.afterAddEntity(level, entity, added);
		} else {
			V2Recorder.afterAddEntity(level, entity, added);
		}
	}

	public static void routeJump(ServerPlayer player) {
		if (isLegacy()) {
			Recorder.onJump(player);
		}
	}

	public static void routeMenuOpened(ServerPlayer player) {
		if (isLegacy()) {
			Recorder.onContainerOpened(player);
		}
	}

	public static void routeMenuClickBefore(AbstractContainerMenu menu, int slotIndex, Player player) {
		if (isLegacy()) {
			Hooks.beforeMenuClick(menu, slotIndex, player);
		}
	}

	public static void routeMenuClickAfter(AbstractContainerMenu menu, int slotIndex, Player player) {
		if (isLegacy()) {
			Hooks.afterMenuClick(menu, slotIndex, player);
		}
	}

	public static void routeItemTouchBefore(ItemEntity item) {
		if (isLegacy()) {
			Hooks.beforeItemTouch(item);
		}
	}

	public static void routeItemTouchAfter(ItemEntity item, Player player) {
		if (isLegacy()) {
			Hooks.afterItemTouch(item, player);
		}
	}

	public static void routePlayerDropBegin() {
		if (isLegacy()) {
			Hooks.beginPlayerDrop();
		}
	}

	public static void routePlayerDropEnd(ServerPlayer player, ItemStack stack, ItemEntity dropped) {
		if (isLegacy()) {
			Hooks.endPlayerDrop(player, stack, dropped);
		}
	}
}
