package com.ctrlz.server;

import com.ctrlz.core.activity.ActivityKind;
import com.ctrlz.core.activity.ActivityRecord;
import com.ctrlz.core.consequence.ConsequenceKind;
import com.ctrlz.core.consequence.ConsequenceOrigin;
import com.ctrlz.core.consequence.ConsequenceRecord;
import com.ctrlz.core.util.ByteIO;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Callbacks invoked from the mixins.
 *
 * <p>All of them are wrapped defensively: a bug in the recorder must never break block placement,
 * container clicks or combat. Anything that fails is dropped instead of half recorded.
 */
public final class Hooks {
	private static final ThreadLocal<java.util.ArrayDeque<BlockSnapshot>> PENDING_BLOCKS = ThreadLocal
			.withInitial(java.util.ArrayDeque::new);
	private static final ThreadLocal<List<SlotSnapshot>> PENDING_SLOTS = new ThreadLocal<>();
	private static final ThreadLocal<java.util.Map<Integer, ItemStack>> PENDING_PICKUPS = ThreadLocal
			.withInitial(java.util.HashMap::new);

	private record BlockSnapshot(BlockPos pos, BlockState state) {
	}

	private Hooks() {
	}

	private record SlotSnapshot(int menuIndex, int containerSlot, ItemStack stack, BlockPos pos, boolean container) {
	}

	public static void beforeSetBlock(Level level, BlockPos pos, BlockState oldState) {
		try {
			if (!(level instanceof ServerLevel)) {
				return;
			}
			PENDING_BLOCKS.get().push(new BlockSnapshot(pos.immutable(), oldState));
		} catch (Throwable ignored) {
		}
	}

	public static void afterSetBlock(Level level, BlockPos pos, BlockState newState) {
		try {
			if (!(level instanceof ServerLevel serverLevel)) {
				return;
			}
			java.util.ArrayDeque<BlockSnapshot> stack = PENDING_BLOCKS.get();
			BlockSnapshot snapshot = stack.isEmpty() ? null : stack.pop();
			if (snapshot == null || !snapshot.pos().equals(pos)) {
				return;
			}
			BlockState previousState = snapshot.state();
			if (previousState.equals(newState) || !Recorder.active()) {
				return;
			}
			CtrlZServer hub = CtrlZServer.get();
			if (hub == null) {
				return;
			}
			String dimension = serverLevel.dimension().identifier().toString();
			long packed = ByteIO.packBlockPos(pos.getX(), pos.getY(), pos.getZ());
			CtrlZServer.CausalLink link = hub.findCausal(dimension, packed, hub.currentTick());
			if (link == null) {
				return;
			}
			ActivityRecord activity = hub.activityForCausal(link);
			if (activity == null) {
				return;
			}
			String before = Snapshots.encodeBlockState(previousState);
			String after = Snapshots.encodeBlockState(newState);
			hub.consequence(activity, ConsequenceKind.BLOCK_STATE, link.origin(), dimension, packed,
					net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(newState.getBlock()).toString(),
					before.getBytes(StandardCharsets.UTF_8), after.getBytes(StandardCharsets.UTF_8),
					before + " -> " + after, link.parentConsequenceId());
		} catch (Throwable ignored) {
		}
	}

	/** Called by the item entity mixin before pickup handling, so the stack can be compared after. */
	public static void beforeItemTouch(net.minecraft.world.entity.item.ItemEntity itemEntity) {
		try {
			PENDING_PICKUPS.get().put(itemEntity.getId(), itemEntity.getItem().copy());
		} catch (Throwable ignored) {
		}
	}

	/** Records a pickup only when vanilla really consumed the item entity. */
	public static void afterItemTouch(net.minecraft.world.entity.item.ItemEntity itemEntity,
			net.minecraft.world.entity.player.Player player) {
		try {
			ItemStack before = PENDING_PICKUPS.get().remove(itemEntity.getId());
			if (before == null || before.isEmpty()) {
				return;
			}
			if (!(player instanceof ServerPlayer serverPlayer)) {
				return;
			}
			boolean consumed = itemEntity.isRemoved() || itemEntity.getItem().isEmpty();
			if (!consumed) {
				return;
			}
			Recorder.onItemPickedUp(serverPlayer, itemEntity, before);
		} catch (Throwable ignored) {
		}
	}

	public static void beforeMenuClick(AbstractContainerMenu menu, int slotIndex, net.minecraft.world.entity.player.Player player) {
		try {
			if (!(player instanceof ServerPlayer) || !Recorder.active()) {
				return;
			}
			List<SlotSnapshot> snapshots = new ArrayList<>();
			for (int i = 0; i < menu.slots.size(); i++) {
				Slot slot = menu.slots.get(i);
				BlockPos pos = null;
				boolean container = false;
				if (slot.container instanceof BlockEntity blockEntity) {
					pos = blockEntity.getBlockPos();
					container = true;
				}
				snapshots.add(new SlotSnapshot(i, slot.getContainerSlot(), slot.getItem().copy(), pos, container));
			}
			PENDING_SLOTS.set(snapshots);
		} catch (Throwable ignored) {
		}
	}

	public static void afterMenuClick(AbstractContainerMenu menu, int slotIndex, net.minecraft.world.entity.player.Player player) {
		try {
			List<SlotSnapshot> before = PENDING_SLOTS.get();
			PENDING_SLOTS.remove();
			if (before == null || !(player instanceof ServerPlayer serverPlayer) || !Recorder.active()) {
				return;
			}
			ServerLevel level = (ServerLevel) serverPlayer.level();
			String dimension = level.dimension().identifier().toString();
			CtrlZServer hub = CtrlZServer.get();
			if (hub == null) {
				return;
			}
			boolean crafted = slotIndex == 0 && !menu.slots.isEmpty()
					&& menu.getSlot(0).container instanceof net.minecraft.world.inventory.ResultContainer;
			ActivityRecord activity = hub.openAction(serverPlayer,
					crafted ? ActivityKind.CRAFT : ActivityKind.MOVE_ITEM);
			if (activity == null) {
				return;
			}
			for (SlotSnapshot snapshot : before) {
				ItemStack current = menu.getSlot(snapshot.menuIndex()).getItem();
				if (ItemStack.matches(snapshot.stack(), current)) {
					continue;
				}
				long packed = snapshot.pos() == null ? ConsequenceRecord.NO_POSITION
						: ByteIO.packBlockPos(snapshot.pos().getX(), snapshot.pos().getY(), snapshot.pos().getZ());
				String subject = snapshot.container() ? "container#" + snapshot.containerSlot()
						: "inv#" + serverPlayer.getUUID() + "#" + snapshot.containerSlot();
				hub.consequence(activity,
						snapshot.container() ? ConsequenceKind.CONTAINER_SLOTS : ConsequenceKind.INVENTORY_SLOTS,
						ConsequenceOrigin.DIRECT, dimension, packed, subject,
						Snapshots.encodeSingleItem(snapshot.stack(), level.registryAccess()),
						Snapshots.encodeSingleItem(current, level.registryAccess()),
						"slot " + snapshot.menuIndex() + ": " + Snapshots.describe(snapshot.stack()) + " -> "
								+ Snapshots.describe(current),
						0L);
				if (crafted) {
					hub.addStat(activity, com.ctrlz.core.activity.ActivityStat.ITEMS_CRAFTED,
							Math.max(1, current.getCount()));
				}
			}
		} catch (Throwable ignored) {
		}
	}
}
