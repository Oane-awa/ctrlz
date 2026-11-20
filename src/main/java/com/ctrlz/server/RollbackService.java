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
import com.ctrlz.core.rollback.RollbackPreflight;
import com.ctrlz.core.storage.ActivityStore;
import com.ctrlz.core.util.ByteIO;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
			boolean truncated, String conflict, String messageKey, String detail, long boundaryId,
			boolean compensationFailed) {

		/** Key only result, for callers that have no boundary or compensation information. */
		public Result(boolean success, long targetActivityId, int activities, int restored, int skipped,
				boolean truncated, String conflict, String messageKey, String detail) {
			this(success, targetActivityId, activities, restored, skipped, truncated, conflict, messageKey, detail, 0L,
					false);
		}

		static Result refusal(String conflict, String messageKey) {
			return new Result(false, 0L, 0, 0, 0, false, conflict, messageKey, "");
		}

		Result withTarget(long id) {
			return new Result(success, id, activities, restored, skipped, truncated, conflict, messageKey, detail,
					boundaryId, compensationFailed);
		}
	}

	/** How many records the hotkey scan looks back before it gives up. */
	private static final int SCAN_LIMIT = 256;

	/** No-op listener: the outcome object already carries everything the caller needs. */
	private static final RollbackListener QUIET = new RollbackListener() {
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
	};

	/** {@code G} row click: restore the state recorded after {@code activityId}, keeping that record. */
	public static Result restoreToActivity(ServerPlayer actor, long activityId) {
		return restore(actor, activityId, true);
	}

	/**
	 * {@code Z}: strictly LIFO.
	 *
	 * <p>The scan walks the player's own history newest first and stops at the first record that
	 * matters. Records with nothing to undo are skipped (they changed no state), already undone
	 * records are skipped (their effects are gone), but a permanent boundary is
	 * <em>never</em> skipped: the undo stops there, keeps every record, and says why. Skipping it
	 * would apply older snapshots on top of a change that is still in place.
	 */
	public static Result restoreLast(ServerPlayer actor) {
		CtrlZServer hub = CtrlZServer.get();
		if (hub == null || hub.store() == null) {
			return Result.refusal("unavailable", "ctrlz.result.no_store");
		}
		hub.flushSession(actor.getUUID());
		for (ActivityRecord activity : hub.store().recentActivities(actor.getUUID(), SCAN_LIMIT)) {
			if (activity.kind().isMovementKind()) {
				continue;
			}
			ActivityStatus status = activity.status();
			if (status == ActivityStatus.ROLLED_BACK || status == ActivityStatus.PRUNED) {
				continue;
			}
			if (isBoundary(hub, activity)) {
				return boundaryRefusal(hub, activity, "ctrlz.result.boundary");
			}
			if (!status.isRollbackCandidate()) {
				continue;
			}
			if (hub.store().consequenceCount(activity.id()) == 0) {
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
		// (1) The activity the player just did is still open, and an open activity has no complete
		// consequence list yet. Sealing first is what makes "undo what I just did" possible.
		hub.flushSession(target.playerId());
		boolean ownActivity = target.playerId().equals(actor.getUUID());
		int required = ownActivity ? 0 : hub.config().rollbackPermissionLevel;
		if (!Permissions.hasLevel(actor, required)) {
			return Result.refusal("permission", "ctrlz.result.no_permission");
		}
		// (2) The target itself must be a node whose state can be reproduced.
		if (target.status() == ActivityStatus.ROLLED_BACK) {
			return Result.refusal("status", "ctrlz.result.already_rolled_back").withTarget(target.id());
		}
		if (target.status() == ActivityStatus.INCOMPLETE || target.status() == ActivityStatus.PRUNED) {
			return Result.refusal("status", "ctrlz.result.not_rollbackable").withTarget(target.id());
		}
		if (!keepTarget && isBoundary(hub, target)) {
			// The hotkey undoes the target itself, so a boundary here is the answer.
			return boundaryRefusal(hub, target, "ctrlz.result.boundary");
		}
		Long last = hub.lastRollbackMillis().get(actor.getUUID());
		long now = System.currentTimeMillis();
		if (last != null && now - last < hub.config().rollbackCooldownTicks * 50L) {
			return Result.refusal("cooldown", "ctrlz.result.cooldown");
		}
		if (!keepTarget && store.consequenceCount(target.id()) == 0) {
			return Result.refusal("empty", "ctrlz.result.nothing_to_rollback");
		}

		// (3) History boundary scan: never cross a record that can not be undone (AUDIT-003).
		Map<Long, List<ConsequenceRecord>> cache = new HashMap<>();
		RestorePlan.Branch branch = RestorePlan.branch(nodesOf(store, target.playerId()), target.id(),
				target.playerId(), keepTarget, id -> hasUnrecoverable(consequencesOf(store, cache, id)));
		if (branch.blocked()) {
			ActivityRecord blocker = store.byId(branch.boundary().id());
			if (blocker == null) {
				return new Result(false, target.id(), 0, 0, 0, false, ConflictType.BOUNDARY.name(),
						"ctrlz.result.boundary_cross", "", branch.boundary().id(), false);
			}
			return boundaryRefusal(hub, blocker, "ctrlz.result.boundary_cross");
		}
		List<Group> groups = new ArrayList<>(branch.victims().size());
		for (Long id : branch.victims()) {
			ActivityRecord victim = store.byId(id);
			if (victim == null) {
				continue;
			}
			groups.add(new Group(victim, ConsequenceCollapser.collapse(consequencesOf(store, cache, id))));
		}
		if (!keepTarget && groups.isEmpty()) {
			return Result.refusal("empty", "ctrlz.result.nothing_to_rollback");
		}
		ServerRestorer restorer = new ServerRestorer(hub.server(), pickedUpInBatch(groups));

		// (4) Record integrity: an undecodable payload is refused before anything is simulated.
		for (Group group : groups) {
			for (ConsequenceRecord consequence : group.consequences()) {
				if (!consequence.origin().isAttributable()) {
					continue;
				}
				ConflictType integrity = restorer.checkIntegrity(consequence);
				if (integrity.isBlocking()) {
					return refused(hub, target, group.activity(), integrity,
							describe(hub.server(), group.activity(), consequence, null));
				}
			}
		}
		// (5) Multi block structures are restored completely or not at all.
		for (Group group : groups) {
			Result incomplete = checkStructures(group.consequences());
			if (incomplete != null) {
				return incomplete.withTarget(target.id());
			}
		}
		// (6) Whole chain dry run on a virtual world state (AUDIT-004). Nothing is written here.
		List<RollbackPreflight.Batch> batches = new ArrayList<>(groups.size());
		for (Group group : groups) {
			batches.add(new RollbackPreflight.Batch(group.activity().id(), group.consequences()));
		}
		RollbackPreflight.Outcome simulated = RollbackPreflight.simulate(batches, restorer);
		if (!simulated.ok()) {
			RollbackPreflight.Failure failed = simulated.failure();
			ActivityRecord owner = store.byId(failed.activityId());
			String detail = owner == null ? "" : describe(hub.server(), owner, failed.change(), failed.actual());
			LOGGER.info("CtrlZ restore preflight refused target={} activity={} conflict={} {}", target.id(),
					failed.activityId(), failed.type(), detail);
			return refused(hub, target, owner, failed.type(), detail);
		}

		// (7) Apply, newest activity first. A failure re-applies what was already undone.
		int restored = 0;
		int skipped = 0;
		List<Group> undone = new ArrayList<>();
		ConflictType failure = null;
		String failureDetail = "";
		ActivityRecord failureOwner = null;
		hub.beginSuppression();
		try {
			for (Group group : groups) {
				RollbackEngine.Outcome outcome = RollbackEngine.rollback(group.consequences(), restorer, QUIET);
				if (!outcome.success()) {
					failure = outcome.conflict().isBlocking() ? outcome.conflict() : ConflictType.APPLY_FAILED;
					failureDetail = outcome.reason();
					failureOwner = group.activity();
					break;
				}
				restored += outcome.restored();
				skipped += outcome.skipped();
				undone.add(group);
			}
			// (8) Verification: the world must now be exactly where the simulation said it would be.
			if (failure == null) {
				ConflictType verification = verify(restorer, simulated);
				if (verification.isBlocking()) {
					failure = verification;
					failureDetail = "restored state does not match the simulated state";
					failureOwner = target;
				}
			}
			if (failure != null) {
				// Nothing is truncated on failure: the history still describes the world we restored.
				boolean compensated = reapplyAll(restorer, undone);
				if (!compensated) {
					failure = ConflictType.COMPENSATION_FAILED;
					failureDetail = failureDetail + " (compensation failed, world may be inconsistent)";
				}
				String detail = describe(failureOwner, failureDetail);
				long ownerId = failureOwner == null ? 0L : failureOwner.id();
				hub.recordDiagnostic(ownerId, failure.name() + " " + failureDetail);
				LOGGER.warn("CtrlZ restore aborted target={} conflict={} reason={}; {} activities re-applied{}",
						target.id(), failure, failureDetail, undone.size(), compensated ? "" : " (failed)");
				return new Result(false, target.id(), 0, 0, 0, false, failure.name(), messageFor(failure), detail,
						0L, !compensated && !undone.isEmpty());
			}

			// (9) The world now is the anchor node, so the newer records are obsolete.
			long anchor = RestorePlan.anchor(target.id(), keepTarget);
			int deleted;
			try {
				deleted = store.deleteActivitiesAfter(target.playerId(), anchor);
				store.flush(true);
			} catch (java.io.IOException e) {
				// The world is already at the anchor, but the history still lists operations that no
				// longer describe it. Reporting that is better than a success the next restart would
				// contradict, and it can be retried after the disk problem is gone.
				LOGGER.error("CtrlZ restored target={} but could not truncate the history", target.id(), e);
				return new Result(false, target.id(), groups.size(), restored, skipped, false, "truncate_failed",
						"ctrlz.result.truncate_failed", describe(target, String.valueOf(e.getMessage())));
			}
			hub.pruneDiagnostics();
			hub.lastRollbackMillis().put(actor.getUUID(), now);
			if (hub.config().announceRollback && actor.connection != null) {
				actor.sendSystemMessage(Component.translatable("ctrlz.notice.rolled_back", restored));
			}
			LOGGER.info("CtrlZ restore ok target={} keep={} activities={} restored={} truncated={}", target.id(),
					keepTarget, groups.size(), restored, deleted);
			return new Result(true, target.id(), groups.size(), restored, skipped, deleted > 0, "", "ctrlz.result.ok",
					describe(target, "ok"));
		} finally {
			hub.endSuppression();
			// What the restore changed must not be recorded as a new activity on the next tick.
			hub.resyncBaselines();
			safeFlush(hub);
		}
	}

	private record Group(ActivityRecord activity, List<ConsequenceRecord> consequences) {
	}

	/** Minimal view of the player's history, as {@link RestorePlan} expects it. */
	private static List<RestorePlan.Node> nodesOf(ActivityStore store, UUID playerId) {
		List<ActivityRecord> feed = store.recentActivities(playerId, 1024);
		List<RestorePlan.Node> nodes = new ArrayList<>(feed.size());
		for (ActivityRecord record : feed) {
			nodes.add(new RestorePlan.Node(record.id(), record.playerId(), record.status(),
					record.kind().isMovementKind()));
		}
		return nodes;
	}

	/** Reads an activity's consequences once and reuses them for the boundary scan and the plan. */
	private static List<ConsequenceRecord> consequencesOf(ActivityStore store,
			Map<Long, List<ConsequenceRecord>> cache, long activityId) {
		return cache.computeIfAbsent(activityId, store::consequences);
	}

	/**
	 * Whether a record changed something that can never be restored.
	 *
	 * <p>New records say so with their status. This check exists for history written before that
	 * flag existed: the payload already marks such a change with the {@code unsupported#} subject,
	 * so older worlds get the same boundary instead of silently pretending to be restorable.
	 */
	private static boolean hasUnrecoverable(List<ConsequenceRecord> consequences) {
		for (ConsequenceRecord consequence : consequences) {
			if (consequence.kind() == ConsequenceKind.CONTAINER_SLOTS
					&& consequence.subject().startsWith("unsupported#")) {
				return true;
			}
		}
		return false;
	}

	/** A permanent history boundary: the restore must stop here, never skip it. */
	private static boolean isBoundary(CtrlZServer hub, ActivityRecord activity) {
		if (activity == null) {
			return false;
		}
		ActivityStatus status = activity.status();
		if (status == ActivityStatus.NOT_ROLLBACKABLE || status == ActivityStatus.INCOMPLETE) {
			return true;
		}
		if (status != ActivityStatus.SEALED && status != ActivityStatus.PARTIALLY_ROLLED_BACK) {
			return false;
		}
		return hasUnrecoverable(hub.store().consequences(activity.id()));
	}

	private static String boundaryReason(ActivityRecord activity) {
		if (activity.status() == ActivityStatus.NOT_ROLLBACKABLE) {
			return activity.statusReason().isEmpty() ? "ctrlz.reason.not_rollbackable" : activity.statusReason();
		}
		if (activity.status() == ActivityStatus.INCOMPLETE) {
			return "ctrlz.reason.incomplete";
		}
		return "ctrlz.reason.unsupported";
	}

	private static Result boundaryRefusal(CtrlZServer hub, ActivityRecord activity, String messageKey) {
		String reason = boundaryReason(activity);
		String detail = describe(activity, Component.translatable(reason).getString());
		hub.recordDiagnostic(activity.id(), "BOUNDARY " + reason);
		LOGGER.info("CtrlZ restore stopped at history boundary activity={} reason={}", activity.id(), reason);
		return new Result(false, activity.id(), 0, 0, 0, false, ConflictType.BOUNDARY.name(), messageKey, detail,
				activity.id(), false);
	}

	/** Refusal with a diagnostic trail: which record, when, what it expected and what it found. */
	private static Result refused(CtrlZServer hub, ActivityRecord target, ActivityRecord owner, ConflictType type,
			String detail) {
		long ownerId = owner == null ? 0L : owner.id();
		hub.recordDiagnostic(ownerId, type.name() + " " + detail);
		return new Result(false, target.id(), 0, 0, 0, false, type.name(), messageFor(type), detail, 0L, false);
	}

	/** Human readable description of a failed state comparison, for the GUI and the log. */
	private static String describe(MinecraftServer server, ActivityRecord activity, ConsequenceRecord change,
			byte[] actual) {
		if (change == null) {
			return describe(activity, "");
		}
		StringBuilder text = new StringBuilder(describe(activity, ""));
		if (text.length() > 0) {
			text.append(' ');
		}
		text.append(change.kind().label()).append(' ').append(change.subject());
		if (change.hasPosition()) {
			text.append(" @").append(ByteIO.unpackX(change.packedPos())).append(',')
					.append(ByteIO.unpackY(change.packedPos())).append(',')
					.append(ByteIO.unpackZ(change.packedPos()));
		}
		text.append(": expected ").append(payloadText(server, change.kind(), change.after()));
		text.append(", found ").append(payloadText(server, change.kind(), actual));
		return text.toString();
	}

	/** {@code #id KIND 12:00:00 detail} for a record, or just the detail when the record is gone. */
	private static String describe(ActivityRecord activity, String detail) {
		StringBuilder text = new StringBuilder();
		if (activity != null) {
			text.append('#').append(activity.id()).append(' ').append(activity.kind()).append(' ')
					.append(java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(activity.startMillis()),
							java.time.ZoneId.systemDefault()));
			if (activity.startPacked() != 0L) {
				text.append(" @").append(ByteIO.unpackX(activity.startPacked())).append(',')
						.append(ByteIO.unpackY(activity.startPacked())).append(',')
						.append(ByteIO.unpackZ(activity.startPacked()));
			}
		}
		if (detail != null && !detail.isEmpty()) {
			if (text.length() > 0) {
				text.append(' ');
			}
			text.append(detail);
		}
		return text.toString().trim();
	}

	private static String payloadText(MinecraftServer server, ConsequenceKind kind, byte[] payload) {
		if (payload == null) {
			return "unknown";
		}
		if (payload.length == 0) {
			return "empty";
		}
		return switch (kind) {
			case BLOCK_STATE -> new String(payload, StandardCharsets.UTF_8);
			case CONTAINER_SLOTS, INVENTORY_SLOTS, ITEM_ENTITY_SPAWN -> {
				String described = Snapshots.describe(Snapshots.decodeSingleItem(payload, server.registryAccess()));
				yield described.isEmpty() ? "empty" : described;
			}
			default -> payload.length + " bytes";
		};
	}

	/**
	 * Post restore verification: every object the simulation touched must now hold exactly the value
	 * the simulation predicted. Without this a restore that silently wrote nothing would look like
	 * a success.
	 */
	private static ConflictType verify(ServerRestorer restorer, RollbackPreflight.Outcome simulated) {
		for (Map.Entry<String, byte[]> entry : simulated.expected().entrySet()) {
			byte[] actual = restorer.readByKey(entry.getKey());
			if (actual == null || !java.util.Arrays.equals(actual, entry.getValue())) {
				LOGGER.warn("CtrlZ verification mismatch at {} (expected {} bytes, actual {})", entry.getKey(),
						entry.getValue().length, actual == null ? "unreadable" : actual.length + " bytes");
				return ConflictType.VERIFY_FAILED;
			}
		}
		return ConflictType.NONE;
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

	/** Forward re-apply in causal order, used to compensate an aborted multi activity restore. */
	private static boolean reapply(ServerRestorer restorer, Group group) {
		List<ConsequenceRecord> ordered = new ArrayList<>(group.consequences());
		ordered.sort(Comparator.comparingInt(ConsequenceRecord::tick).thenComparingLong(ConsequenceRecord::id));
		boolean complete = true;
		for (ConsequenceRecord consequence : ordered) {
			if (!consequence.origin().isAttributable()) {
				continue;
			}
			try {
				if (!restorer.reapply(consequence)) {
					complete = false;
				}
			} catch (RuntimeException e) {
				// Report, never swallow: a compensation that did not happen leaves the world in a
				// state the history no longer describes.
				LOGGER.error("CtrlZ could not re-apply {} of activity {}", consequence.kind().label(),
						group.activity().id(), e);
				complete = false;
			}
		}
		return complete;
	}

	/** Compensates every fully applied group, newest first, and reports whether it fully worked. */
	private static boolean reapplyAll(ServerRestorer restorer, List<Group> undone) {
		boolean complete = true;
		for (int index = undone.size() - 1; index >= 0; index--) {
			if (!reapply(restorer, undone.get(index))) {
				complete = false;
			}
		}
		return complete;
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
			case PAYLOAD_CORRUPT -> "ctrlz.result.payload_corrupt";
			case ITEM_MISMATCH -> "ctrlz.result.item_mismatch";
			case BOUNDARY -> "ctrlz.result.boundary";
			case APPLY_FAILED -> "ctrlz.result.apply_failed";
			case COMPENSATION_FAILED -> "ctrlz.result.compensation_failed";
			case VERIFY_FAILED -> "ctrlz.result.verify_failed";
			case BACKEND_UNAVAILABLE -> "ctrlz.result.no_snapshot";
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