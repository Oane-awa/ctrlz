package com.ctrlz.v2;

import com.ctrlz.history.Operation;
import com.ctrlz.history.Snapshot;
import com.ctrlz.history.SnapshotKeys;
import com.ctrlz.history.StateFingerprint;
import com.ctrlz.history.UndoResult;
import com.ctrlz.platform.SnapshotCapture;
import com.ctrlz.platform.SnapshotCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Dry run of an undo.
 *
 * <p>Nothing is written here: the planner produces the ordered step list plus the audit baseline, and
 * refuses the whole operation if any target is unloaded, already changed by somebody else, or of a
 * kind this slice cannot restore. "Refuse everything" is deliberate — a partial restore would break
 * atomicity (I2) and can duplicate items.
 */
public final class UndoPlannerImpl {
	private final MinecraftServer server;
	private final ConservationAuditImpl audit;

	public UndoPlannerImpl(MinecraftServer server, ConservationAuditImpl audit) {
		this.server = server;
		this.audit = audit;
	}

	public UndoResult.Plan plan(Operation operation) throws UndoResult.PlanException {
		ServerLevel level = levelOf(server, operation.worldKey());
		if (level == null) {
			throw new UndoResult.PlanException(UndoResult.Status.BLOCK_UNLOADED, "dimension " + operation.worldKey());
		}
		List<UndoResult.Plan.Step> steps = new ArrayList<>();
		for (Snapshot snapshot : operation.snapshots()) {
			if (snapshot instanceof Snapshot.Block block) {
				steps.add(planBlock(operation, block, level));
			} else if (snapshot instanceof Snapshot.PlayerState state) {
				steps.add(planPlayerState(operation, state));
			} else if (snapshot instanceof Snapshot.Entity entity && !entity.existedBefore()) {
				Entity live = level.getEntity(entity.uuid());
				if (live == null) {
					// The drop was picked up or despawned: removing the block again would duplicate
					// the item that is now in somebody's inventory.
					throw new UndoResult.PlanException(UndoResult.Status.CONFLICT,
							"drop " + entity.uuid() + " is gone");
				}
				steps.add(new UndoResult.Plan.Step(entity, live));
			} else if (snapshot instanceof Snapshot.Entity) {
				throw new UndoResult.PlanException(UndoResult.Status.COVERAGE_NONE,
						"entity restore arrives with slice 2");
			} else {
				throw new UndoResult.PlanException(UndoResult.Status.COVERAGE_NONE,
						"unsupported snapshot " + snapshot.kind());
			}
		}
		if (steps.isEmpty()) {
			throw new UndoResult.PlanException(UndoResult.Status.COVERAGE_NONE, "no steps");
		}
		return new UndoResult.Plan(operation, List.copyOf(steps), audit.counts(operation));
	}

	private UndoResult.Plan.Step planPlayerState(Operation operation, Snapshot.PlayerState state)
			throws UndoResult.PlanException {
		net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayer(operation.playerUuid());
		if (player == null) {
			throw new UndoResult.PlanException(UndoResult.Status.CONFLICT,
					"player " + operation.playerUuid() + " is not online");
		}
		if (!com.ctrlz.server.EffectSnapshots.isCaptured(state.effects())) {
			throw new UndoResult.PlanException(UndoResult.Status.COVERAGE_NONE, "effects were not captured");
		}
		// The live effect set is what a failed apply has to write back.
		return new UndoResult.Plan.Step(state,
				new RestoreApplier.PreviousEffects(player, com.ctrlz.server.EffectSnapshots.encode(player)));
	}

	private UndoResult.Plan.Step planBlock(Operation operation, Snapshot.Block block, ServerLevel level)
			throws UndoResult.PlanException {
		BlockPos pos = SnapshotCodec.unpack(block.packedPos());
		if (!level.isLoaded(pos)) {
			throw new UndoResult.PlanException(UndoResult.Status.BLOCK_UNLOADED, pos.toShortString());
		}
		StateFingerprint expected = operation.afterFingerprints().get(SnapshotKeys.of(block));
		if (expected != null && !expected.isEmpty()) {
			StateFingerprint current = SnapshotCapture.blockFingerprint(level, pos);
			if (!expected.matches(current)) {
				throw new UndoResult.PlanException(UndoResult.Status.CONFLICT,
						pos.toShortString() + " is no longer " + expected.value());
			}
		}
		return new UndoResult.Plan.Step(block, level.getBlockState(pos));
	}

	public static ServerLevel levelOf(MinecraftServer server, String worldKey) {
		try {
			ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(worldKey));
			return server.getLevel(key);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
