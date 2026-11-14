package com.ctrlz.server;

import com.ctrlz.core.activity.ActivityRecord;
import com.ctrlz.core.activity.ActivityStatus;
import com.ctrlz.core.consequence.ConsequenceRecord;
import com.ctrlz.core.consequence.ConsequenceStatus;
import com.ctrlz.core.rollback.ConflictType;
import com.ctrlz.core.rollback.RollbackEngine;
import com.ctrlz.core.rollback.RollbackListener;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Entry point for in game rollbacks.
 *
 * <p>Order of operations: permission check, state conflict check, MineTracer cross audit (when the
 * optional backend is installed), then a transactional restore that either completes or is reverted
 * completely.
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
		if (!activity.status().isRollbackCandidate()) {
			return new Result(false, 0, "status", "ctrlz.result.not_rollbackable");
		}
		Long last = hub.lastRollbackMillis().get(actor.getUUID());
		long now = System.currentTimeMillis();
		if (last != null && now - last < hub.config().rollbackCooldownTicks * 50L) {
			return new Result(false, 0, "cooldown", "ctrlz.result.cooldown");
		}
		List<ConsequenceRecord> consequences = hub.store().consequences(activityId);
		if (hub.config().useMineTracerAudit) {
			MineTracerBridge.Audit audit = MineTracerBridge.audit(activity, consequences);
			if (audit.conflict()) {
				markNotRollbackable(hub, activity, "ctrlz.reason.audit_conflict");
				return new Result(false, 0, "audit", "ctrlz.result.audit_conflict");
			}
		}
		hub.lastRollbackMillis().put(actor.getUUID(), now);
		hub.beginSuppression();
		try {
			final int[] restored = { 0 };
			RollbackEngine.Outcome outcome = RollbackEngine.rollback(consequences,
					new ServerRestorer(hub.server()), new RollbackListener() {
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
			markNotRollbackable(hub, activity, "ctrlz.reason.conflict");
			return new Result(false, 0, outcome.conflict().name(), "ctrlz.result.conflict");
		} finally {
			hub.endSuppression();
			safeFlush(hub);
		}
	}

	public static Result rollbackLast(ServerPlayer actor) {
		CtrlZServer hub = CtrlZServer.get();
		if (hub == null || hub.store() == null) {
			return new Result(false, 0, "unavailable", "ctrlz.result.no_store");
		}
		List<ActivityRecord> recent = hub.store().recentActivities(actor.getUUID(), 8);
		for (ActivityRecord activity : recent) {
			if (activity.status() == ActivityStatus.ROLLED_BACK) {
				continue;
			}
			if (activity.status().isRollbackCandidate()) {
				return rollback(actor, activity.id());
			}
		}
		return new Result(false, 0, "empty", "ctrlz.result.nothing_to_rollback");
	}

	private static void markNotRollbackable(CtrlZServer hub, ActivityRecord activity, String reason) {
		activity.setStatus(ActivityStatus.NOT_ROLLBACKABLE, reason);
		safeUpdateStatus(hub, activity);
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
