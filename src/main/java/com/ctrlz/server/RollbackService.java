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
	 * Newest rollbackable activity of the player. Activities without any recorded consequence are
	 * skipped: "rolling them back" would report success while changing nothing.
	 */
	public static Result rollbackLast(ServerPlayer actor) {
		CtrlZServer hub = CtrlZServer.get();
		if (hub == null || hub.store() == null) {
			return new Result(false, 0, "unavailable", "ctrlz.result.no_store");
		}
		hub.flushSession(actor.getUUID());
		for (ActivityRecord activity : hub.store().recentActivities(actor.getUUID(), 64)) {
			if (!activity.status().isRollbackCandidate() || hub.store().consequenceCount(activity.id()) == 0) {
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
