package com.ctrlz.server;

import com.ctrlz.core.activity.ActivityKind;
import com.ctrlz.core.activity.ActivityRecord;
import com.ctrlz.core.activity.ActivityStat;
import com.ctrlz.core.consequence.ConsequenceKind;
import com.ctrlz.core.consequence.ConsequenceOrigin;
import com.ctrlz.core.consequence.ConsequenceRecord;
import com.ctrlz.core.util.ByteIO;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Callbacks invoked from the mixins.
 *
 * <p>All of them are wrapped defensively: a bug in the recorder must never break block placement,
 * container clicks or combat. Anything that fails is dropped instead of half recorded.
 */
public final class Hooks {
	private static final ThreadLocal<ArrayDeque<BlockSnapshot>> PENDING_BLOCKS = ThreadLocal
			.withInitial(ArrayDeque::new);
	private static final ThreadLocal<List<SlotSnapshot>> PENDING_SLOTS = new ThreadLocal<>();
	private static final ThreadLocal<Map<Integer, ItemStack>> PENDING_PICKUPS = ThreadLocal
			.withInitial(HashMap::new);

	private enum TargetType {
		INVENTORY, BLOCK, ENDER, IGNORED, UNSUPPORTED
	}

	/** Where a menu slot really lives. A double chest is two block entities behind one container. */
	private record Target(TargetType type, BlockPos pos, int slot, String label) {
		static Target of(TargetType type, int slot) {
			return new Target(type, null, slot, "");
		}
	}

	private record BlockSnapshot(BlockPos pos, BlockState state, byte[] blockEntityData) {
	}

	private record SlotSnapshot(int menuIndex, Target target, ItemStack stack) {
	}

	private Hooks() {
	}

	// ---------------------------------------------------------------- block changes

	public static void beforeSetBlock(Level level, BlockPos pos, BlockState oldState) {
		ArrayDeque<BlockSnapshot> stack = PENDING_BLOCKS.get();
		if (stack.size() > 64) {
			stack.clear();
		}
		byte[] blockEntity = new byte[0];
		try {
			if (level instanceof ServerLevel serverLevel && Recorder.active() && oldState.hasBlockEntity()) {
				CtrlZServer hub = CtrlZServer.get();
				long packed = ByteIO.packBlockPos(pos.getX(), pos.getY(), pos.getZ());
				if (hub.config().captureBlockEntityData
						&& hub.findCausal(Recorder.dimensionOf(serverLevel), packed, hub.currentTick()) != null) {
					// The block entity is destroyed together with the block, so this is the only
					// moment its contents (chest items, sign text, ...) can still be read.
					blockEntity = Snapshots.blockEntityData(serverLevel, pos);
				}
			}
		} catch (Throwable ignored) {
		}
		stack.push(new BlockSnapshot(pos.immutable(), oldState, blockEntity));
	}

	public static void afterSetBlock(Level level, BlockPos pos, BlockState newState) {
		ArrayDeque<BlockSnapshot> stack = PENDING_BLOCKS.get();
		BlockSnapshot snapshot = stack.isEmpty() ? null : stack.pop();
		try {
			if (snapshot == null || !(level instanceof ServerLevel serverLevel) || !snapshot.pos().equals(pos)) {
				return;
			}
			BlockState previousState = snapshot.state();
			if (previousState.equals(newState) || !Recorder.active()) {
				return;
			}
			CtrlZServer hub = CtrlZServer.get();
			String dimension = Recorder.dimensionOf(serverLevel);
			long packed = ByteIO.packBlockPos(pos.getX(), pos.getY(), pos.getZ());
			CtrlZServer.CausalLink link = hub.findCausal(dimension, packed, hub.currentTick());
			ActivityRecord activity = hub.activityForCausal(link);
			if (activity == null) {
				return;
			}
			String oldId = BuiltInRegistries.BLOCK.getKey(previousState.getBlock()).toString();
			// Recorded first on purpose: rollback replays newest first, so the block state is put
			// back before the block entity payload is loaded into it.
			if (snapshot.blockEntityData().length > 0) {
				hub.consequence(activity, ConsequenceKind.BLOCK_ENTITY_DATA, link.origin(), dimension, packed,
						oldId + "#be", snapshot.blockEntityData(), new byte[0], "block entity data",
						link.parentConsequenceId());
			}
			String before = Snapshots.encodeBlockState(previousState);
			String after = Snapshots.encodeBlockState(newState);
			hub.consequence(activity, ConsequenceKind.BLOCK_STATE, link.origin(), dimension, packed, oldId,
					before.getBytes(StandardCharsets.UTF_8), after.getBytes(StandardCharsets.UTF_8),
					before + " -> " + after, link.parentConsequenceId());
		} catch (Throwable ignored) {
		}
	}

	// ---------------------------------------------------------------- dropped items

	/** Set while a player throws an item, so the generic spawn hook does not record it twice. */
	private static final ThreadLocal<Boolean> PLAYER_DROP = ThreadLocal.withInitial(() -> Boolean.FALSE);

	public static void beginPlayerDrop() {
		PLAYER_DROP.set(Boolean.TRUE);
	}

	public static void endPlayerDrop(ServerPlayer player, ItemStack stack, ItemEntity entity) {
		PLAYER_DROP.set(Boolean.FALSE);
		try {
			if (entity != null) {
				Recorder.onItemDropped(player, stack, entity);
			}
		} catch (Throwable ignored) {
		}
	}

	/** Items that spawn out of a block an activity changed (broken blocks, emptied chests...). */
	public static void afterAddEntity(ServerLevel level, Entity entity, boolean added) {
		try {
			if (!added || PLAYER_DROP.get() || !(entity instanceof ItemEntity item) || !Recorder.active()) {
				return;
			}
			CtrlZServer hub = CtrlZServer.get();
			BlockPos blockPos = item.blockPosition();
			long packed = ByteIO.packBlockPos(blockPos.getX(), blockPos.getY(), blockPos.getZ());
			CtrlZServer.CausalLink link = hub.findCausal(Recorder.dimensionOf(level), packed, hub.currentTick());
			ActivityRecord activity = hub.activityForCausal(link);
			if (activity == null || item.getItem().isEmpty()) {
				return;
			}
			hub.consequence(activity, ConsequenceKind.ITEM_ENTITY_SPAWN, ConsequenceOrigin.INDIRECT,
					Recorder.dimensionOf(level), packed, item.getStringUUID(), new byte[0],
					Snapshots.encodeSingleItem(item.getItem(), level.registryAccess()),
					"dropped " + Snapshots.describe(item.getItem()), link.parentConsequenceId());
		} catch (Throwable ignored) {
		}
	}

	// ---------------------------------------------------------------- item pickup

	/** Called before pickup handling so the stack can be compared afterwards. */
	public static void beforeItemTouch(ItemEntity itemEntity) {
		try {
			PENDING_PICKUPS.get().put(itemEntity.getId(), itemEntity.getItem().copy());
		} catch (Throwable ignored) {
		}
	}

	/** Records a pickup only when vanilla really consumed the item entity. */
	public static void afterItemTouch(ItemEntity itemEntity, Player player) {
		try {
			ItemStack before = PENDING_PICKUPS.get().remove(itemEntity.getId());
			if (before == null || before.isEmpty() || !(player instanceof ServerPlayer serverPlayer)) {
				return;
			}
			boolean consumed = itemEntity.isRemoved() || itemEntity.getItem().isEmpty();
			if (consumed) {
				Recorder.onItemPickedUp(serverPlayer, itemEntity, before);
			}
		} catch (Throwable ignored) {
		}
	}

	// ---------------------------------------------------------------- container clicks

	public static void beforeMenuClick(AbstractContainerMenu menu, int slotIndex, Player player) {
		try {
			if (!(player instanceof ServerPlayer) || !Recorder.active()) {
				PENDING_SLOTS.remove();
				return;
			}
			List<SlotSnapshot> snapshots = new ArrayList<>();
			for (int i = 0; i < menu.slots.size(); i++) {
				Slot slot = menu.slots.get(i);
				Target target = resolve(slot.container, slot.getContainerSlot(), player);
				if (target.type() != TargetType.IGNORED) {
					snapshots.add(new SlotSnapshot(i, target, slot.getItem().copy()));
				}
			}
			PENDING_SLOTS.set(snapshots);
		} catch (Throwable ignored) {
			PENDING_SLOTS.remove();
		}
	}

	public static void afterMenuClick(AbstractContainerMenu menu, int slotIndex, Player player) {
		try {
			List<SlotSnapshot> before = PENDING_SLOTS.get();
			PENDING_SLOTS.remove();
			if (before == null || !(player instanceof ServerPlayer serverPlayer) || !Recorder.active()) {
				return;
			}
			ServerLevel level = (ServerLevel) serverPlayer.level();
			String dimension = Recorder.dimensionOf(level);
			CtrlZServer hub = CtrlZServer.get();
			boolean crafted = slotIndex == 0 && !menu.slots.isEmpty() && menu.getSlot(0).container instanceof ResultContainer;
			ActivityRecord activity = null;
			for (SlotSnapshot snapshot : before) {
				ItemStack current = menu.getSlot(snapshot.menuIndex()).getItem();
				if (ItemStack.matches(snapshot.stack(), current)) {
					continue;
				}
				if (activity == null) {
					activity = hub.openAction(serverPlayer, crafted ? ActivityKind.CRAFT : ActivityKind.MOVE_ITEM);
					if (activity == null) {
						return;
					}
				}
				record(hub, activity, serverPlayer, level, dimension, snapshot, current);
				if (crafted && snapshot.menuIndex() == 0) {
					hub.addStat(activity, ActivityStat.ITEMS_CRAFTED, Math.max(1, snapshot.stack().getCount()));
				}
			}
			if (activity != null && !crafted) {
				hub.addStat(activity, ActivityStat.ITEMS_MOVED, 1);
			}
		} catch (Throwable ignored) {
		}
	}

	private static void record(CtrlZServer hub, ActivityRecord activity, ServerPlayer player, ServerLevel level,
			String dimension, SlotSnapshot snapshot, ItemStack current) {
		Target target = snapshot.target();
		ConsequenceKind kind = target.type() == TargetType.INVENTORY ? ConsequenceKind.INVENTORY_SLOTS
				: ConsequenceKind.CONTAINER_SLOTS;
		long packed = target.pos() == null ? ConsequenceRecord.NO_POSITION
				: ByteIO.packBlockPos(target.pos().getX(), target.pos().getY(), target.pos().getZ());
		String subject = switch (target.type()) {
			case INVENTORY -> "inv#" + player.getUUID() + "#" + target.slot();
			case ENDER -> "ender#" + player.getUUID() + "#" + target.slot();
			case BLOCK -> "container#" + target.slot();
			default -> "unsupported#" + target.label() + "#" + target.slot();
		};
		hub.consequence(activity, kind, ConsequenceOrigin.DIRECT, dimension, packed, subject,
				Snapshots.encodeSingleItem(snapshot.stack(), level.registryAccess()),
				Snapshots.encodeSingleItem(current, level.registryAccess()),
				"slot " + target.slot() + ": " + Snapshots.describe(snapshot.stack()) + " -> "
						+ Snapshots.describe(current),
				0L);
	}

	/**
	 * Works out which real storage a menu slot belongs to. Reading {@code slot.container} as "the
	 * player's inventory unless it is a block entity" wrote double chest slots into the player's
	 * inventory on rollback, which duplicated and destroyed items.
	 */
	private static Target resolve(Container container, int slot, Player player) {
		if (container == player.getInventory()) {
			return Target.of(TargetType.INVENTORY, slot);
		}
		if (container == player.getEnderChestInventory()) {
			return Target.of(TargetType.ENDER, slot);
		}
		if (container instanceof CraftingContainer || container instanceof ResultContainer) {
			return Target.of(TargetType.IGNORED, slot);
		}
		if (container instanceof BlockEntity blockEntity) {
			return new Target(TargetType.BLOCK, blockEntity.getBlockPos(), slot, "");
		}
		if (container instanceof CompoundContainer) {
			Container first = compoundPart(container, "container1");
			Container second = compoundPart(container, "container2");
			if (first != null && second != null) {
				int size = first.getContainerSize();
				return slot < size ? resolve(first, slot, player) : resolve(second, slot - size, player);
			}
		}
		return new Target(TargetType.UNSUPPORTED, null, slot, container.getClass().getSimpleName());
	}

	private static Container compoundPart(Container compound, String field) {
		try {
			Field f = CompoundContainer.class.getDeclaredField(field);
			f.setAccessible(true);
			return (Container) f.get(compound);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}
}
