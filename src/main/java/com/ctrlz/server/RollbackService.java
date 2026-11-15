package com.ctrlz.server;

import com.ctrlz.core.activity.ActivityRecord;
import com.ctrlz.core.activity.ActivityStatus;
import com.ctrlz.core.consequence.ConsequenceKind;
import com.ctrlz.core.consequence.ConsequenceRecord;
import com.ctrlz.core.consequence.ConsequenceStatus;
import com.ctrlz.core.rollback.ConflictType;
import com.ctrlz.core.rollback.ConsequenceCollapser;
import com.ctrlz.core.rollback.RollbackEngine;
import com.ctrlz.core.rollback.RollbackListener;
import com.ctrlz.core.util.ByteIO;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Entry point for in game rollbacks.
 *
 * <p>Order of operations: seal what is still being recorded, permission check, collapse repeated
 * changes of one target, MineTracer cross audit (when installed), then a transactional restore that
 * either completes or is reverted completely.
 */
public final class RollbackService {
	private RollbackService() {
	}

	public record Result(boolean success, int restored, String conflict, String messageKey) {
	}

	public static Result rollback(ServerPlayer actor, long activityId) {
		CtrlZServer hub = CtrlZServer.get();
		if (hub == null || hub.store() == null) {
			return new Result(false, 0, "unavailable", "ctrlz.result.no_store");
		}
		// Everything below touches world state, so it must run on the server thread.
		if (!hub.server().isSameThread()) {
			hub.server().execute(() -> rollback(actor, activityId));
			return new Result(false, 0, "busy", "ctrlz.result.busy");
		}
		if (!hub.tryBeginRollback(actor.getUUID())) {
			// Fast repeated presses must never run the same transaction twice.
			return new Result(false, 0, "busy", "ctrlz.result.busy");
		}
		try {
			return rollbackNow(actor, hub, activityId);
		} finally {
			hub.endRollback(actor.getUUID());
		}
	}

	private static Result rollbackNow(ServerPlayer actor, CtrlZServer hub, long activityId) {
		ActivityRecord activity = hub.store().byId(activityId);
		if (activity == null) {
			return new Result(false, 0, "missing", "ctrlz.result.not_found");
		}
		boolean ownActivity = activity.playerId().equals(actor.getUUID());
		int required = ownActivity ? 0 : hub.config().rollbackPermissionLevel;
		if (!Permissions.hasLevel(actor, required)) {
			return new Result(false, 0, "permission", "ctrlz.result.no_permission");
		}
		// The activity the player just did is still open, and an open activity has no complete
		// consequence list yet. Closing it first is what makes "undo what I just did" possible.
		hub.sealIfOpen(activity);
		if (!activity.status().isRollbackCandidate()) {
			String key = activity.status() == ActivityStatus.ROLLED_BACK ? "ctrlz.result.already_rolled_back"
					: "ctrlz.result.not_rollbackable";
			return new Result(false, 0, "status", key);
		}
		Long last = hub.lastRollbackMillis().get(actor.getUUID());
		long now = System.currentTimeMillis();
		if (last != null && now - last < hub.config().rollbackCooldownTicks * 50L) {
			return new Result(false, 0, "cooldown", "ctrlz.result.cooldown");
		}
		List<ConsequenceRecord> consequences = ConsequenceCollapser.collapse(hub.store().consequences(activityId));
		if (consequences.isEmpty()) {
			return new Result(false, 0, "empty", "ctrlz.result.nothing_to_rollback");
		}
		Result incomplete = checkStructures(consequences);
		if (incomplete != null) {
			hub.markConflicted(activityId);
			return incomplete;
		}
		if (hub.config().useMineTracerAudit) {
			MineTracerBridge.Audit audit = MineTracerBridge.audit(activity, consequences);
			if (audit.conflict()) {
				return new Result(false, 0, "audit", "ctrlz.result.audit_conflict");
			}
		}
		hub.lastRollbackMillis().put(actor.getUUID(), now);
		return execute(actor, hub, activity, consequences);
	}

	/**
	 * A structure whose other half is not part of this transaction may not be restored: putting back
	 * half a bed or a door leaves a broken block that can no longer be undone. Refusing with a clear
	 * reason is the only safe outcome, a multi block structure is never restored partially.
	 */
	private static Result checkStructures(List<ConsequenceRecord> consequences) {
		Set<Long> positions = new HashSet<>();
		for (ConsequenceRecord consequence : consequences) {
			if (consequence.kind() == ConsequenceKind.BLOCK_STATE && consequence.hasPosition()) {
				positions.add(consequence.packedPos());
			}
		}
		for (ConsequenceRecord consequence : consequences) {
			if (consequence.kind() != ConsequenceKind.BLOCK_STATE || !consequence.hasPosition()) {
				continue;
			}
			net.minecraft.world.level.block.state.BlockState state = Snapshots
					.decodeBlockState(new String(consequence.before(), java.nio.charset.StandardCharsets.UTF_8));
			if (state == null) {
				continue;
			}
			BlockPos sibling = Snapshots.siblingPosition(state,
					new BlockPos(ByteIO.unpackX(consequence.packedPos()), ByteIO.unpackY(consequence.packedPos()),
							ByteIO.unpackZ(consequence.packedPos())));
			if (sibling == null) {
				continue;
			}
			if (!positions.contains(ByteIO.packBlockPos(sibling.getX(), sibling.getY(), sibling.getZ()))) {
				return new Result(false, 0, "incomplete_structure", "ctrlz.result.incomplete_structure");
			}
		}
		return null;
	}

	/**
	 * Newest rollbackable <em>action</em> of the player.
	 *
	 * <p>Walking is never undone by the hotkey: a walk carries a player position, so undoing it after
	 * a block operation would teleport the player instead of restoring the block, and waiting a while
	 * would silently change what the key does. Movement stays available from the history screen,
	 * where the row is chosen explicitly.
	 *
	 * <p>Activities without any consequence are skipped ("rolling them back" would report success
	 * while changing nothing), and activities whose state check just failed are skipped for a while
	 * so one conflicting entry cannot disable the hotkey.
	 */
	public static Result rollbackLast(ServerPlayer actor) {
		CtrlZServer hub = CtrlZServer.get();
		if (hub == null || hub.store() == null) {
			return new Result(false, 0, "unavailable", "ctrlz.result.no_store");
		}
		hub.flushSession(actor.getUUID());
		for (ActivityRecord activity : hub.store().recentActivities(actor.getUUID(), 96)) {
			if (!activity.status().isRollbackCandidate() || hub.store().consequenceCount(activity.id()) == 0
					|| hub.isConflicted(activity.id()) || activity.kind().isMovementKind()) {
				continue;
			}
			return rollback(actor, activity.id());
		}
		return new Result(false, 0, "empty", "ctrlz.result.nothing_to_rollback");
	}

	private static Result execute(ServerPlayer actor, CtrlZServer hub, ActivityRecord activity,
			List<ConsequenceRecord> consequences) {
		Set<String> pickedUpInBatch = new HashSet<>();
		for (ConsequenceRecord consequence : consequences) {
			if (consequence.kind() == ConsequenceKind.ITEM_ENTITY_SPAWN && consequence.after().length == 0) {
				pickedUpInBatch.add(consequence.subject());
			}
		}
		hub.beginSuppression();
		try {
			final int[] restored = { 0 };
			RollbackEngine.Outcome outcome = RollbackEngine.rollback(consequences,
					new ServerRestorer(hub.server(), pickedUpInBatch), new RollbackListener() {
						@Override
						public void onConflict(ConsequenceRecord consequence, ConflictType conflict) {
						}

						@Override
						public void onRestored(ConsequenceRecord consequence) {
							restored[0]++;
							recordStatus(hub, consequence, ConsequenceStatus.ROLLED_BACK);
						}

						@Override
						public void onSkipped(ConsequenceRecord consequence, String reason) {
							recordStatus(hub, consequence, ConsequenceStatus.SKIPPED);
						}

						@Override
						public void onRevertedTransaction(ConsequenceRecord consequence) {
							recordStatus(hub, consequence, ConsequenceStatus.PENDING);
						}
					});
			if (outcome.success()) {
				activity.setStatus(ActivityStatus.ROLLED_BACK, "");
				safeUpdateStatus(hub, activity);
				if (hub.config().announceRollback) {
					actor.sendSystemMessage(Component.translatable("ctrlz.notice.rolled_back", restored[0]));
				}
				return new Result(true, outcome.restored(), "", "ctrlz.result.ok");
			}
			// Remember the failure so the hotkey moves on to the next activity instead of retrying
			// the same impossible one forever.
			hub.markConflicted(activity.id());
			// A state conflict is not permanent: the player can undo the newer activity first and
			// try again. Only what can never be restored is marked as such.
			if (outcome.conflict() == ConflictType.UNSUPPORTED_MODDED_STATE
					|| outcome.conflict() == ConflictType.NO_SNAPSHOT) {
				activity.setStatus(ActivityStatus.NOT_ROLLBACKABLE, "ctrlz.reason.unsupported");
				safeUpdateStatus(hub, activity);
			}
			return new Result(false, 0, outcome.conflict().name(), messageFor(outcome.conflict()));
		} finally {
			hub.endSuppression();
			// What the rollback changed must not be recorded as a new activity on the next tick.
			hub.resyncBaselines();
			safeFlush(hub);
		}
	}

	private static String messageFor(ConflictType conflict) {
		return switch (conflict) {
			case TARGET_MISSING -> "ctrlz.result.target_missing";
			case UNSUPPORTED_MODDED_STATE -> "ctrlz.result.unsupported";
			case NO_SNAPSHOT -> "ctrlz.result.no_snapshot";
			default -> "ctrlz.result.conflict";
		};
	}

	private static void safeUpdateStatus(CtrlZServer hub, ActivityRecord activity) {
		try {
			hub.store().updateStatus(activity);
			hub.store().flush(false);
		} catch (Exception ignored) {
		}
	}

	private static void safeFlush(CtrlZServer hub) {
		try {
			hub.store().flush(true);
		} catch (Exception ignored) {
		}
	}

	private static void recordStatus(CtrlZServer hub, ConsequenceRecord consequence, ConsequenceStatus status) {
		try {
			hub.store().appendConsequenceStatus(consequence.id(), status);
		} catch (Exception ignored) {
		}
	}
}
