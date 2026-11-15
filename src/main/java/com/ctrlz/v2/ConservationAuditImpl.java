package com.ctrlz.v2;

import com.ctrlz.history.ConservationAudit;
import com.ctrlz.history.Operation;
import com.ctrlz.history.Snapshot;
import com.ctrlz.history.UndoResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Item conservation check for the undo (I1).
 *
 * <p>Slice 0 audits two things: the player's inventory and the drop entities this operation created.
 * Undoing a break is allowed to remove exactly those drops and nothing else, so the expected state
 * after the restore is "before minus the drops in the plan". Any other difference means the restore
 * would create or destroy items and is refused.
 */
public final class ConservationAuditImpl implements ConservationAudit {
	private final MinecraftServer server;

	public ConservationAuditImpl(MinecraftServer server) {
		this.server = server;
	}

	@Override
	public Map<String, Integer> counts(Operation operation) {
		Map<String, Integer> counts = new TreeMap<>();
		ServerPlayer player = server.getPlayerList().getPlayer(operation.playerUuid());
		if (player != null) {
			Inventory inventory = player.getInventory();
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
				apply(counts, inventory.getItem(slot), 1);
			}
		}
		for (Snapshot snapshot : operation.snapshots()) {
			if (snapshot instanceof Snapshot.Entity entity && !entity.existedBefore()) {
				Entity live = findEntity(operation.worldKey(), entity.uuid());
				if (live instanceof ItemEntity item) {
					apply(counts, item.getItem(), 1);
				}
			}
		}
		return counts;
	}

	/**
	 * The counts the audit expects once the plan has been applied.
	 *
	 * <p>The baseline must be the one captured during the dry run: recomputing it after the apply
	 * would already miss the drops that were just removed and would flag every correct undo.
	 */
	public Map<String, Integer> expectedAfter(Map<String, Integer> before, List<UndoResult.Plan.Step> steps) {
		Map<String, Integer> expected = new TreeMap<>(before);
		for (UndoResult.Plan.Step step : steps) {
			if (step.snapshot() instanceof Snapshot.Entity entity && !entity.existedBefore()
					&& step.previousValue() instanceof ItemEntity item) {
				apply(expected, item.getItem(), -1);
			}
		}
		return expected;
	}

	private Entity findEntity(String worldKey, java.util.UUID uuid) {
		var level = UndoPlannerImpl.levelOf(server, worldKey);
		return level == null ? null : level.getEntity(uuid);
	}

	private static void apply(Map<String, Integer> counts, ItemStack stack, int sign) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		int amount = sign * stack.getCount();
		counts.merge(id, amount, Integer::sum);
		counts.values().removeIf(value -> value == 0);
	}
}
