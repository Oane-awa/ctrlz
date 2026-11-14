package com.ctrlz.core.rollback;

import com.ctrlz.core.consequence.ConsequenceKind;
import com.ctrlz.core.consequence.ConsequenceRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Folds every change of the same target into one original -> final pair.
 *
 * <p>Consequences recorded after an activity was sealed (late redstone, fluids) are separate
 * records of the same block, and validating each of them against the world on its own would report
 * conflicts that are not real: the first one expects the state the later one already changed.
 */
public final class ConsequenceCollapser {
	private ConsequenceCollapser() {
	}

	public static List<ConsequenceRecord> collapse(List<ConsequenceRecord> consequences) {
		List<ConsequenceRecord> ordered = new ArrayList<>(consequences);
		ordered.sort(Comparator.comparingInt(ConsequenceRecord::tick).thenComparingLong(ConsequenceRecord::id));
		Map<String, ConsequenceRecord> merged = new LinkedHashMap<>();
		for (ConsequenceRecord next : ordered) {
			boolean block = next.kind() == ConsequenceKind.BLOCK_STATE
					|| next.kind() == ConsequenceKind.BLOCK_ENTITY_DATA;
			String key = next.kind().id() + "|" + next.dimension() + "|" + next.packedPos() + "|"
					+ (block ? "" : next.subject());
			ConsequenceRecord first = merged.get(key);
			if (first == null) {
				merged.put(key, next);
				continue;
			}
			ConsequenceRecord combined = new ConsequenceRecord(next.id(), next.activityId(), next.kind(),
					first.origin(), next.tick(), next.dimension(), next.packedPos(), next.subject(), first.before(),
					next.after(), first.depth(), first.parentId(), first.createdMillis(), next.detail());
			combined.setStatus(next.status());
			merged.put(key, combined);
		}
		return new ArrayList<>(merged.values());
	}
}
