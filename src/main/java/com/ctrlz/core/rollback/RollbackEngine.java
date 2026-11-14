package com.ctrlz.core.rollback;

import com.ctrlz.core.consequence.ConsequenceOrigin;
import com.ctrlz.core.consequence.ConsequenceRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Transactional per activity rollback.
 *
 * <p>Rules enforced here:
 * <ul>
 * <li>consequences are restored in strict reverse causal order</li>
 * <li>every target is checked before anything is written, so a conflict aborts the whole
 * transaction instead of producing a half restored world</li>
 * <li>if a write fails halfway the already applied steps are re-applied forward, leaving no
 * duplicated items, no lost blocks and no orphaned entities</li>
 * </ul>
 */
public final class RollbackEngine {
	public record Outcome(boolean success, int restored, int skipped, ConflictType conflict, String reason) {
		public static Outcome failed(ConflictType conflict, String reason) {
			return new Outcome(false, 0, 0, conflict, reason);
		}
	}

	private RollbackEngine() {
	}

	public static List<ConsequenceRecord> orderForRestore(List<ConsequenceRecord> consequences) {
		List<ConsequenceRecord> ordered = new ArrayList<>(consequences);
		ordered.sort(Comparator.comparingInt(ConsequenceRecord::tick).thenComparingLong(ConsequenceRecord::id));
		java.util.Collections.reverse(ordered);
		return ordered;
	}

	public static Outcome rollback(List<ConsequenceRecord> consequences, ConsequenceRestorer restorer,
			RollbackListener listener) {
		List<ConsequenceRecord> ordered = orderForRestore(consequences);
		if (ordered.isEmpty()) {
			return new Outcome(true, 0, 0, ConflictType.NONE, "");
		}
		for (ConsequenceRecord consequence : ordered) {
			if (!consequence.origin().isAttributable()) {
				continue;
			}
			ConflictType conflict = restorer.check(consequence);
			if (conflict.isBlocking()) {
				listener.onConflict(consequence, conflict);
				return Outcome.failed(conflict, consequence.kind().label() + "@" + consequence.tick());
			}
		}
		List<ConsequenceRecord> applied = new ArrayList<>();
		int restored = 0;
		for (ConsequenceRecord consequence : ordered) {
			if (consequence.origin() == ConsequenceOrigin.UNATTRIBUTED) {
				listener.onSkipped(consequence, "unattributed");
				continue;
			}
			boolean ok;
			try {
				ok = restorer.restore(consequence);
			} catch (RuntimeException e) {
				ok = false;
			}
			if (!ok) {
				for (int i = applied.size() - 1; i >= 0; i--) {
					try {
						restorer.reapply(applied.get(i));
						listener.onRevertedTransaction(applied.get(i));
					} catch (RuntimeException ignored) {
					}
				}
				return new Outcome(false, 0, 0, ConflictType.STATE_MISMATCH,
						"restore failed at " + consequence.kind().label() + "@" + consequence.tick());
			}
			applied.add(consequence);
			restored++;
			listener.onRestored(consequence);
		}
		return new Outcome(true, restored, ordered.size() - restored, ConflictType.NONE, "");
	}
}
