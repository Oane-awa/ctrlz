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
		long anchor = anchor(targetId, keepTarget);
		List<Long> ids = new ArrayList<>();
		if (nodes == null) {
			return ids;
		}
		for (Node node : nodes) {
			if (node == null || !node.playerId().equals(playerId) || node.id() <= anchor) {
				continue;
			}
			ActivityStatus status = node.status();
			if (status == ActivityStatus.ROLLED_BACK || status == ActivityStatus.NOT_ROLLBACKABLE
					|| status == ActivityStatus.INCOMPLETE || status == ActivityStatus.PRUNED) {
				continue;
			}
			// The hotkey must undo the thing the player meant, not teleport them first.
			if (!keepTarget && node.movement()) {
				continue;
			}
			ids.add(node.id());
		}
		ids.sort(Comparator.reverseOrder());
		return ids;
	}
}
