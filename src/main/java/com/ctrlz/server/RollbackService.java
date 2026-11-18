package com.ctrlz.server;

import com.ctrlz.core.activity.ActivityRecord;
import com.ctrlz.core.activity.ActivityStatus;
import com.ctrlz.core.consequence.ConsequenceKind;
import com.ctrlz.core.consequence.ConsequenceRecord;
import com.ctrlz.core.rollback.ConflictType;
import com.ctrlz.core.rollback.ConsequenceCollapser;
import com.ctrlz.core.rollback.RestorePlan;
import com.ctrlz.core.rollback.RollbackEngine;
import com.ctrlz.core.rollback.RollbackListener;
import com.ctrlz.core.storage.ActivityStore;
import com.ctrlz.core.util.ByteIO;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The one and only restore entry point.
 *
 * <p>Both the {@code G} screen (a clicked row) and the {@code Z} key end up here:
 * <ul>
 * <li>{@code G} → {@link #restoreToActivity} → restore the state that activity left behind, keeping it</li>
 * <li>{@code Z} → {@link #restoreLast} → the server picks the newest rollbackable action and undoes it,
 * which also removes it from the history</li>
 * </ul>
 * They differ only in which node is the anchor and whether the anchor survives; the plan, the state
 * checks, the two phase apply, the truncation and the result object are shared. Keeping two separate
 * code paths is what previously made a GUI restore and a hotkey undo produce different worlds.
 *
 * <p>Order of operations (a partial restore is never reported as a success):
 * <ol>
 * <li>seal whatever is still being recorded, so the newest action is part of the decision</li>
 * <li>collect every one of this player's activities after the anchor, newest first</li>
 * <li>validate all of them (target existence, state conflicts, multi block completeness) before
 * writing anything</li>
 * <li>apply the inverse consequences, newest activity first</li>
 * <li>only after every activity was restored: delete the records after the anchor and flush</li>
 * </ol>
 * If a write fails midway, the already restored activities are re-applied forward, the history is left
 * untouched and the failure is reported with its reason.
 */
public final class RollbackService {
	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("CtrlZ");

	private RollbackService() {
	}

	/**
	 * One result shape for both entry points.
	 *
	 * @param activities how many recorded operations were undone
	 * @param restored   how many recorded consequences were written back
	 * @param skipped    consequences that could not be attributed and were left alone
	 * @param truncated  whether the newer history records were actually deleted
	 */
	public record Result(boolean success, long targetActivityId, int activities, int restored, int skipped,
			boolean truncated, String conflict, String messageKey, String detail) {

		static Result refusal(String conflict, String messageKey) {
			return new Result(false, 0L, 0, 0, 0, false, conflict, messageKey, "");
		}

		Result withTarget(long id) {
			return new Result(success, id, activities, restored, skipped, truncated, conflict, messageKey, detail);
		}
	}

	/** {@code G} row click: restore the state recorded after {@code activityId}, keeping that record. */
	public static Result restoreToActivity(ServerPlayer actor, long activityId) {
		return restore(actor, activityId, true);
	}

	/**
	 * {@code Z}: the newest rollbackable action becomes the anchor and is undone, so it also leaves the
	 * history. Walks are never chosen here — undoing one would teleport the player instead of undoing
	 * the block or item change they meant — but an explicit GUI restore does rewind them, because a
	 * restore has to reproduce the whole recorded state of the node.
	 */
	public static Result restoreLast(ServerPlayer actor) {
		CtrlZServer hub = CtrlZServer.get();
		if (hub == null || hub.store() == null) {
			return Result.refusal("unavailable", "ctrlz.result.no_store");
		}
		hub.flushSession(actor.getUUID());
		for (ActivityRecord activity : hub.store().recentActivities(actor.getUUID(), 96)) {
			if (!activity.status().isRollbackCandidate() || hub.store().consequenceCount(activity.id()) == 0
					|| hub.isConflicted(activity.id()) || activity.kind().isMovementKind()) {
				continue;
			}
			return restore(actor, activity.id(), false);
		}
		return Result.refusal("empty", "ctrlz.result.nothing_to_rollback");
	}

	/** The single implementation used by both entry points. */
	public static Result restore(ServerPlayer actor, long targetActivityId, boolean keepTarget) {
		CtrlZServer hub = CtrlZServer.get();
		if (hub == null || hub.store() == null) {
			return Result.refusal("unavailable", "ctrlz.result.no_store");
		}
		// Everything below touches world state, so it must run on the server thread.
		if (!hub.server().isSameThread()) {
			hub.server().execute(() -> restore(actor, targetActivityId, keepTarget));
			return Result.refusal("busy", "ctrlz.result.busy");
		}
		UUID playerId = actor.getUUID();
		if (!hub.tryBeginRollback(playerId)) {
			// Fast repeated presses must never run the same transaction twice.
			return Result.refusal("busy", "ctrlz.result.busy");
		}
		try {
			return restoreNow(actor, hub, targetActivityId, keepTarget);
		} finally {
			hub.endRollback(playerId);
		}
	}

	private static Result restoreNow(ServerPlayer actor, CtrlZServer hub, long targetActivityId, boolean keepTarget) {
		ActivityStore store = hub.store();
		ActivityRecord target = store.byId(targetActivityId);
		if (target == null) {
			return Result.refusal("missing", "ctrlz.result.not_found");
		}
		// The activity the player just did is still open, and an open activity has no complete
		// consequence list yet. Sealing first is what makes "undo what I just did" possible.
		hub.flushSession(target.playerId());
		boolean ownActivity = target.playerId().equals(actor.getUUID());
		int required = ownActivity ? 0 : hub.config().rollbackPermissionLevel;
		if (!Permissions.hasLevel(actor, required)) {
			return Result.refusal("permission", "ctrlz.result.no_permission");
		}
		if (!target.status().isRollbackCandidate()) {
			String key = target.status() == ActivityStatus.ROLLED_BACK ? "ctrlz.result.already_rolled_back"
					: "ctrlz.result.not_rollbackable";
			return Result.refusal("status", key);
		}
		Long last = hub.lastRollbackMillis().get(actor.getUUID());
		long now = System.currentTimeMillis();
		if (last != null && now - last < hub.config().rollbackCooldownTicks * 50L) {
			return Result.refusal("cooldown", "ctrlz.result.cooldown");
		}
		if (!keepTarget && store.consequenceCount(target.id()) == 0) {
			return Result.refusal("empty", "ctrlz.result.nothing_to_rollback");
		}

		List<Group> groups = collectGroups(hub, target, keepTarget);
		if (!keepTarget && groups.isEmpty()) {
			return Result.refusal("empty", "ctrlz.result.nothing_to_rollback");
		}
		ServerRestorer restorer = new ServerRestorer(hub.server(), pickedUpInBatch(groups));

		// Phase 1: validate everything before the first write.
		for (Group group : groups) {
			Result incomplete = checkStructures(group.consequences());
			if (incomplete != null) {
				hub.markConflicted(target.id());
				return incomplete.withTarget(target.id());
			}
			for (ConsequenceRecord consequence : group.consequences()) {
				if (!consequence.origin().isAttributable()) {
					continue;
				}
				ConflictType conflict = restorer.check(consequence);
				if (conflict.isBlocking()) {
					hub.markConflicted(target.id());
					LOGGER.info("CtrlZ restore refused target={} activity={} conflict={} kind={}", target.id(),
							group.activity().id(), conflict, consequence.kind().label());
					return new Result(false, target.id(), 0, 0, 0, false, conflict.name(), messageFor(conflict),
							consequence.kind().label() + "@" + consequence.tick());
				}
			}
		}

		// Phase 2: apply, newest activity first. A failure re-applies what was already undone.
		int restored = 0;
		int skipped = 0;
		List<Group> undone = new ArrayList<>();
		ConflictType failure = null;
		String failureDetail = "";
		hub.beginSuppression();
		try {
			for (Group group : groups) {
				RollbackEngine.Outcome outcome = RollbackEngine.rollback(group.consequences(), restorer,
						new RollbackListener() {
							@Override
							public void onConflict(ConsequenceRecord consequence, ConflictType conflict) {
							}

							@Override
							public void onRestored(ConsequenceRecord consequence) {
							}

							@Override
							public void onSkipped(ConsequenceRecord consequence, String reason) {
							}

							@Override
							public void onRevertedTransaction(ConsequenceRecord consequence) {
							}
						});
				if (!outcome.success()) {
					failure = outcome.conflict();
					failureDetail = outcome.reason();
					break;
				}
				restored += outcome.restored();
				skipped += outcome.skipped();
				undone.add(group);
			}
			if (failure != null) {
				// Nothing is truncated on failure: the history still describes the world we restored.
				for (int index = undone.size() - 1; index >= 0; index--) {
					reapply(restorer, undone.get(index));
				}
				hub.markConflicted(target.id());
				LOGGER.warn("CtrlZ restore aborted target={} conflict={} reason={}; {} activities re-applied",
						target.id(), failure, failureDetail, undone.size());
				return new Result(false, target.id(), 0, 0, 0, false, failure.name(), messageFor(failure),
						failureDetail);
			}

			// Phase 3: the world now is the anchor node, so the newer records are obsolete.
			long anchor = keepTarget ? target.id() : target.id() - 1L;
			int deleted;
			try {
				deleted = store.deleteActivitiesAfter(target.playerId(), anchor);
				store.flush(true);
			} catch (java.io.IOException e) {
				// The world is already at the anchor, but the history still lists operations that no
				// longer describe it. Reporting that is better than a success the next restart would
				// contradict, and it can be retried after the disk problem is gone.
				LOGGER.error("CtrlZ restored target={} but could not truncate the history", target.id(), e);
				hub.resyncBaselines();
				return new Result(false, target.id(), groups.size(), restored, skipped, false, "truncate_failed",
						"ctrlz.result.truncate_failed", String.valueOf(e.getMessage()));
			}
			hub.lastRollbackMillis().put(actor.getUUID(), now);
			if (hub.config().announceRollback) {
				actor.sendSystemMessage(Component.translatable("ctrlz.notice.rolled_back", restored));
			}
			LOGGER.info("CtrlZ restore ok target={} keep={} activities={} restored={} truncated={}", target.id(),
					keepTarget, groups.size(), restored, deleted);
			return new Result(true, target.id(), groups.size(), restored, skipped, deleted > 0, "", "ctrlz.result.ok",
					"");
		} finally {
			hub.endSuppression();
			// What the restore changed must not be recorded as a new activity on the next tick.
			hub.resyncBaselines();
			safeFlush(hub);
		}
	}

	private record Group(ActivityRecord activity, List<ConsequenceRecord> consequences) {
	}

	/**
	 * Every one of the owner's activities after the anchor, newest first.
	 *
	 * <p>The anchor itself is excluded when it has to survive (GUI) and included when it is the thing
	 * being undone (Z). Records that can never be restored, and already undone ones, are skipped for
	 * the state changes but still deleted with the rest of the branch.
	 */
	private static List<Group> collectGroups(CtrlZServer hub, ActivityRecord target, boolean keepTarget) {
		List<ActivityRecord> feed = hub.store().recentActivities(target.playerId(), 1024);
		List<RestorePlan.Node> nodes = new ArrayList<>(feed.size());
		for (ActivityRecord record : feed) {
			nodes.add(new RestorePlan.Node(record.id(), record.playerId(), record.status(),
					record.kind().isMovementKind()));
		}
		List<Long> victims = RestorePlan.victims(nodes, target.id(), target.playerId(), keepTarget);
		List<Group> groups = new ArrayList<>(victims.size());
		for (Long id : victims) {
			ActivityRecord victim = hub.store().byId(id);
			if (victim == null) {
				continue;
			}
			groups.add(new Group(victim, ConsequenceCollapser.collapse(hub.store().consequences(id))));
		}
		return groups;
	}

	/** Item entities that this batch itself puts back, so their pickups are not treated as external. */
	private static Set<String> pickedUpInBatch(List<Group> groups) {
		Set<String> pickedUp = new HashSet<>();
		for (Group group : groups) {
			for (ConsequenceRecord consequence : group.consequences()) {
				if (consequence.kind() == ConsequenceKind.ITEM_ENTITY_SPAWN && consequence.after().length == 0) {
					pickedUp.add(consequence.subject());
				}
			}
		}
		return pickedUp;
	}

	/** Forward re-apply in causal order, used to undo an aborted multi activity restore. */
	private static void reapply(ServerRestorer restorer, Group group) {
		List<ConsequenceRecord> ordered = new ArrayList<>(group.consequences());
		ordered.sort(Comparator.comparingInt(ConsequenceRecord::tick).thenComparingLong(ConsequenceRecord::id));
		for (ConsequenceRecord consequence : ordered) {
			if (!consequence.origin().isAttributable()) {
				continue;
			}
			try {
				restorer.reapply(consequence);
			} catch (RuntimeException ignored) {
				// Best effort: the failure itself is already being reported.
			}
		}
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
				return Result.refusal("incomplete_structure", "ctrlz.result.incomplete_structure");
			}
		}
		return null;
	}

	private static String messageFor(ConflictType conflict) {
		return switch (conflict) {
			case TARGET_MISSING -> "ctrlz.result.target_missing";
			case UNSUPPORTED_MODDED_STATE -> "ctrlz.result.unsupported";
			case NO_SNAPSHOT -> "ctrlz.result.no_snapshot";
			default -> "ctrlz.result.conflict";
		};
	}

	private static void safeFlush(CtrlZServer hub) {
		try {
			hub.store().flush(true);
		} catch (Exception ignored) {
		}
	}
}