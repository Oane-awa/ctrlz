package com.ctrlz.server;

import com.ctrlz.core.activity.ActivityKind;
import com.ctrlz.core.activity.ActivityRecord;
import com.ctrlz.core.activity.ActivityStat;
import com.ctrlz.core.consequence.ConsequenceKind;
import com.ctrlz.core.consequence.ConsequenceOrigin;
import com.ctrlz.core.util.ByteIO;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns vanilla events into activity records and consequence records.
 *
 * <p>Every handler is defensive: a recording failure can never break the player's action, and
 * anything that cannot be captured reliably is simply left out instead of being guessed.
 */
public final class Recorder {
	private Recorder() {
	}

	public static String dimensionOf(ServerLevel level) {
		return level.dimension().identifier().toString();
	}

	static boolean active() {
		CtrlZServer hub = CtrlZServer.get();
		return hub != null && hub.store() != null && hub.config() != null && hub.config().enabled
				&& !hub.suppressed();
	}

	public static void onBlockBreak(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState oldState,
			BlockEntity oldEntity) {
		if (!active() || !CtrlZServer.get().config().recordBlockBreak) {
			return;
		}
		CtrlZServer hub = CtrlZServer.get();
		ActivityRecord activity = hub.openAction(player, ActivityKind.BREAK_BLOCK);
		if (activity == null) {
			return;
		}
		// This event fires BEFORE the block is removed, so the world still shows the old state here.
		// The real before/after pair (and the block entity data) is captured by the setBlock hook;
		// this handler only opens the activity and marks the position, which is what lets that hook
		// attribute the change, the drops and any redstone reaction to this activity.
		hub.touch(activity, dimensionOf(level), ByteIO.packBlockPos(pos.getX(), pos.getY(), pos.getZ()));
		hub.addStat(activity, ActivityStat.BLOCKS_BROKEN, 1);
	}

	public static void onBlockPlaced(ServerPlayer player, BlockPos pos, ItemStack held) {
		if (!active() || !CtrlZServer.get().config().recordBlockPlace) {
			return;
		}
		CtrlZServer hub = CtrlZServer.get();
		ActivityRecord activity = hub.openAction(player, ActivityKind.PLACE_BLOCK);
		if (activity == null) {
			return;
		}
		hub.touch(activity, dimensionOf((ServerLevel) player.level()),
				ByteIO.packBlockPos(pos.getX(), pos.getY(), pos.getZ()));
		hub.addStat(activity, ActivityStat.BLOCKS_PLACED, 1);
	}

	/** Using an item or block (doors, levers, buttons, chests...) with the clicked position. */
	public static void onItemUsed(ServerPlayer player, ItemStack stack, BlockPos pos) {
		if (!active() || !CtrlZServer.get().config().recordUseItem) {
			return;
		}
		CtrlZServer hub = CtrlZServer.get();
		ActivityRecord activity = hub.openAction(player, ActivityKind.USE_ITEM);
		if (activity != null && pos != null) {
			hub.touch(activity, dimensionOf((ServerLevel) player.level()),
					ByteIO.packBlockPos(pos.getX(), pos.getY(), pos.getZ()));
		}
	}

	public static void onItemUsed(ServerPlayer player, ItemStack stack) {
		onItemUsed(player, stack, null);
	}

	public static void onContainerOpened(ServerPlayer player) {
		if (!active() || !CtrlZServer.get().config().recordContainer) {
			return;
		}
		CtrlZServer hub = CtrlZServer.get();
		ActivityRecord activity = hub.openAction(player, ActivityKind.OPEN_CONTAINER);
		if (activity != null) {
			hub.addStat(activity, ActivityStat.CONTAINERS_OPENED, 1);
		}
	}

	public static void onJump(ServerPlayer player) {
		if (!active()) {
			return;
		}
		// A jump belongs to the walk it happens in; it is a counter on the movement activity.
		CtrlZServer.get().recordJump(player);
	}

	static boolean isBlockItem(ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.getItem() instanceof BlockItem;
	}

	static List<ItemStack> snapshotInventory(Player player) {
		List<ItemStack> stacks = new ArrayList<>();
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			stacks.add(player.getInventory().getItem(slot).copy());
		}
		return stacks;
	}

	static ServerLevel levelOf(Entity entity) {
		return entity.level() instanceof ServerLevel level ? level : null;
	}

	public static void onAttack(ServerPlayer player, Entity target, boolean critical) {
		if (!active() || !CtrlZServer.get().config().recordAttack) {
			return;
		}
		CtrlZServer hub = CtrlZServer.get();
		ActivityRecord activity = hub.openAction(player, ActivityKind.ATTACK);
		if (activity == null) {
			return;
		}
		hub.addStat(activity, ActivityStat.ATTACKS, 1);
		if (critical) {
			hub.addStat(activity, ActivityStat.CRITICAL_ATTACKS, 1);
		}
		ServerLevel level = levelOf(player);
		if (target instanceof LivingEntity living && level != null) {
			long packed = ByteIO.packBlockPos(living.blockPosition().getX(), living.blockPosition().getY(),
					living.blockPosition().getZ());
			byte[] health = Float.toString(living.getHealth()).getBytes(StandardCharsets.UTF_8);
			String label = BuiltInRegistries.ENTITY_TYPE.getKey(living.getType()).toString();
			hub.consequence(activity, ConsequenceKind.ENTITY_HEALTH, ConsequenceOrigin.DIRECT, dimensionOf(level),
					packed, living.getStringUUID(), health, health,
					"attack " + label + (critical ? " (critical)" : ""), 0L);
			queueHealthCapture(activity, living.blockPosition(), dimensionOf(level), living.getHealth(),
					living.getUUID());
		}
	}

	public static void onEntityDamaged(LivingEntity entity, float damageTaken, Entity attacker) {
		if (!active() || attacker == null || !(attacker instanceof ServerPlayer player)) {
			return;
		}
		CtrlZServer hub = CtrlZServer.get();
		ActivityRecord activity = hub.currentActivity(player);
		if (activity == null) {
			activity = hub.openAction(player, ActivityKind.ATTACK);
		}
		if (activity == null || activity.kind() != ActivityKind.ATTACK) {
			return;
		}
		hub.addStat(activity, ActivityStat.DAMAGE_DEALT_X100, Math.round(damageTaken * 100.0F));
		ServerLevel level = levelOf(player);
		if (level == null) {
			return;
		}
		long packed = ByteIO.packBlockPos(entity.blockPosition().getX(), entity.blockPosition().getY(),
				entity.blockPosition().getZ());
		String before = Float.toString(entity.getHealth() + damageTaken);
		String after = Float.toString(entity.getHealth());
		hub.consequence(activity, ConsequenceKind.ENTITY_HEALTH, ConsequenceOrigin.DIRECT, dimensionOf(level), packed,
				entity.getStringUUID(), before.getBytes(StandardCharsets.UTF_8),
				after.getBytes(StandardCharsets.UTF_8), "health " + before + " -> " + after, 0L);
	}

	/** A player threw an item: the inventory slot change is picked up by the tick diff, the entity here. */
	public static void onItemDropped(ServerPlayer player, ItemStack stack, Entity itemEntity) {
		if (!active() || !CtrlZServer.get().config().recordItemDrop || stack.isEmpty()) {
			return;
		}
		CtrlZServer hub = CtrlZServer.get();
		ActivityRecord activity = hub.openAction(player, ActivityKind.DROP_ITEM);
		if (activity == null) {
			return;
		}
		hub.addStat(activity, ActivityStat.ITEMS_DROPPED, stack.getCount());
		ServerLevel level = levelOf(player);
		if (level != null && itemEntity instanceof net.minecraft.world.entity.item.ItemEntity dropped) {
			long packed = ByteIO.packBlockPos(dropped.blockPosition().getX(), dropped.blockPosition().getY(),
					dropped.blockPosition().getZ());
			hub.consequence(activity, ConsequenceKind.ITEM_ENTITY_SPAWN, ConsequenceOrigin.DIRECT,
					dimensionOf(level), packed, dropped.getStringUUID(), new byte[0],
					Snapshots.encodeSingleItem(dropped.getItem(), level.registryAccess()),
					"dropped " + Snapshots.describe(dropped.getItem()), 0L);
		}
	}

	public static void onItemPickedUp(ServerPlayer player, Entity itemEntity, ItemStack stack) {
		if (!active() || !CtrlZServer.get().config().recordItemPickup || stack.isEmpty()) {
			return;
		}
		CtrlZServer hub = CtrlZServer.get();
		ActivityRecord activity = hub.currentActivity(player);
		if (activity == null) {
			activity = hub.openAction(player, ActivityKind.PICKUP_ITEM);
		}
		if (activity == null) {
			return;
		}
		hub.addStat(activity, ActivityStat.ITEMS_PICKED_UP, Math.max(1, stack.getCount()));
		ServerLevel level = levelOf(player);
		if (level == null) {
			return;
		}
		long packed = ByteIO.packBlockPos(itemEntity.blockPosition().getX(), itemEntity.blockPosition().getY(),
				itemEntity.blockPosition().getZ());
		hub.consequence(activity, ConsequenceKind.ITEM_ENTITY_SPAWN, ConsequenceOrigin.DIRECT, dimensionOf(level),
				packed, itemEntity.getStringUUID(),
				Snapshots.encodeSingleItem(stack, level.registryAccess()), new byte[0],
				"picked up " + Snapshots.describe(stack), 0L);
		// Remember that this entity now lives in an inventory: rolling back whatever dropped it must
		// not run before this pickup is undone, or the item would exist twice.
		hub.markPickedUp(itemEntity.getStringUUID());
	}

	/** Called from the container menu mixin with the slot snapshot diff already computed. */
	private static final java.util.Map<java.util.UUID, PendingAttack> PENDING_ATTACKS = new java.util.LinkedHashMap<>();

	private record PendingAttack(long activityId, java.util.UUID playerId, float healthBefore, String dimension,
			long packed) {
	}

	public static void queueHealthCapture(ActivityRecord activity, net.minecraft.core.BlockPos pos, String dimension,
			float healthBefore, java.util.UUID target) {
		if (activity == null) {
			return;
		}
		PENDING_ATTACKS.put(target, new PendingAttack(activity.id(), activity.playerId(), healthBefore, dimension,
				ByteIO.packBlockPos(pos.getX(), pos.getY(), pos.getZ())));
	}

	/** Resolves queued attack targets once the damage has actually been applied. */
	public static void tickPending() {
		if (PENDING_ATTACKS.isEmpty()) {
			return;
		}
		CtrlZServer hub = CtrlZServer.get();
		if (hub == null || hub.store() == null) {
			PENDING_ATTACKS.clear();
			return;
		}
		java.util.List<java.util.UUID> done = new java.util.ArrayList<>();
		for (java.util.Map.Entry<java.util.UUID, PendingAttack> entry : PENDING_ATTACKS.entrySet()) {
			PendingAttack pending = entry.getValue();
			ActivityRecord activity = hub.store().byId(pending.activityId());
			if (activity == null) {
				done.add(entry.getKey());
				continue;
			}
			net.minecraft.world.entity.Entity entity = hub.findEntity(entry.getKey());
			if (!(entity instanceof LivingEntity living)) {
				done.add(entry.getKey());
				continue;
			}
			float health = living.getHealth();
			if (health == pending.healthBefore()) {
				continue;
			}
			hub.consequence(activity, ConsequenceKind.ENTITY_HEALTH, ConsequenceOrigin.DIRECT, pending.dimension(),
					pending.packed(), entry.getKey().toString(),
					Float.toString(pending.healthBefore()).getBytes(StandardCharsets.UTF_8),
					Float.toString(health).getBytes(StandardCharsets.UTF_8),
					"health " + pending.healthBefore() + " -> " + health, 0L);
			done.add(entry.getKey());
		}
		for (java.util.UUID uuid : done) {
			PENDING_ATTACKS.remove(uuid);
		}
	}
}

