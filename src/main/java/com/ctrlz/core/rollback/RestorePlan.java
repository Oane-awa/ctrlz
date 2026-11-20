package com.ctrlz.core.rollback;

import com.ctrlz.core.activity.ActivityStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The boundary rules of "restore to this node".
 *
 * <p>History order is the activity id, which the store allocates monotonically. Ids therefore stay
 * ordered even when several operations happen in the same tick, which is why nothing here looks at a
 * timestamp: two actions in one tick are still two distinct nodes in a fixed order.
 *
 * <p>Kept free of Minecraft types on purpose so the boundary can be unit tested.
 */
public final class RestorePlan {
	private RestorePlan() {
	}

	/** Minimal view of one recorded activity. */
	public record Node(long id, UUID playerId, ActivityStatus status, boolean movement) {
	}

	/**
	 * The last id that survives the truncation.
	 *
	 * <p>A GUI restore keeps its target (the node "after C"), so C and everything older stay. Undoing
	 * the newest action through {@code Z} has no surviving target, so the anchor is one below it.
	 */
	public static long anchor(long targetId, boolean keepTarget) {
		return keepTarget ? targetId : targetId - 1L;
	}

	/**
	 * Ids of the operations that must be undone, newest first.
	 *
	 * <p>Only the owner's branch is touched — another player's history is independent and must not be
	 * deleted by somebody else's restore. Records that can never be restored, and already undone ones,
	 * are left out of the state changes but still belong to the truncated branch.
	 *
	 * @param keepTarget true for a GUI restore (the target survives), false for a hotkey undo
	 */
	public static List<Long> victims(List<Node> nodes, long targetId, UUID playerId, boolean keepTarget) {
		return branch(nodes, targetId, playerId, keepTarget, null).victims();
	}

	/** A record a restore must not cross, with the translation key that explains why. */
	public record Boundary(long id, String reasonKey) {
	}

	/**
	 * The branch a restore has to undo plus the first permanent boundary it would have to cross.
	 *
	 * <p>A boundary is not a runtime conflict: it means a recorded change can never be inverted
	 * (unsupported storage), or the record never finished (crash). Restores never skip it, because
	 * applying older snapshots on top of a change that stays in place would restore a world that
	 * never existed.
	 */
	public record Branch(List<Long> victims, Boundary boundary) {
		public boolean blocked() {
			return boundary != null;
		}
	}

	/**
	 * Walks the owner's branch behind {@code targetId} and stops at the first boundary.
	 *
	 * @param unrecoverableContent optional test for records whose <em>contents</em> cannot be
	 *                             restored even though their status says nothing about it (history
	 *                             written by older versions); may be null
	 */
	public static Branch branch(List<Node> nodes, long targetId, UUID playerId, boolean keepTarget,
			java.util.function.LongPredicate unrecoverableContent) {
		long anchor = anchor(targetId, keepTarget);
		List<Long> ids = new ArrayList<>();
		if (nodes != null) {
			for (Node node : nodes) {
				if (node == null || !node.playerId().equals(playerId) || node.id() <= anchor) {
					continue;
				}
				ActivityStatus status = node.status();
				if (status == ActivityStatus.NOT_ROLLBACKABLE) {
					return new Branch(List.of(), new Boundary(node.id(), "ctrlz.reason.not_rollbackable"));
				}
				if (status == ActivityStatus.INCOMPLETE) {
					return new Branch(List.of(), new Boundary(node.id(), "ctrlz.reason.incomplete"));
				}
				if (status == ActivityStatus.ROLLED_BACK || status == ActivityStatus.PRUNED) {
					continue;
				}
				if (unrecoverableContent != null && unrecoverableContent.test(node.id())) {
					return new Branch(List.of(), new Boundary(node.id(), "ctrlz.reason.unsupported"));
				}
				// The hotkey must undo the thing the player meant, not teleport them first.
				if (!keepTarget && node.movement()) {
					continue;
				}
				ids.add(node.id());
			}
		}
		ids.sort(Comparator.reverseOrder());
		return new Branch(ids, null);
	}
}
