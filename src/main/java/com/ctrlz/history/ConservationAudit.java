package com.ctrlz.history;

import java.util.Map;
import java.util.TreeMap;

/**
 * Item conservation check (I1).
 *
 * <p>Counts every item the undo could touch — player inventory, the affected containers and the item
 * entities inside the affected area — before and after applying the plan. The two multisets must be
 * identical; otherwise the restore would create or destroy items and is rejected.
 */
public interface ConservationAudit {
	Map<String, Integer> counts(Operation operation);

	static boolean same(Map<String, Integer> before, Map<String, Integer> after) {
		return new TreeMap<>(before).equals(new TreeMap<>(after));
	}

	static String describeDifference(Map<String, Integer> before, Map<String, Integer> after) {
		TreeMap<String, Integer> left = new TreeMap<>(before);
		TreeMap<String, Integer> right = new TreeMap<>(after);
		StringBuilder builder = new StringBuilder();
		for (Map.Entry<String, Integer> entry : left.entrySet()) {
			int other = right.getOrDefault(entry.getKey(), 0);
			if (other != entry.getValue()) {
				builder.append(entry.getKey()).append(' ').append(entry.getValue()).append("->").append(other)
						.append("; ");
			}
		}
		for (Map.Entry<String, Integer> entry : right.entrySet()) {
			if (!left.containsKey(entry.getKey())) {
				builder.append(entry.getKey()).append(" +").append(entry.getValue()).append("; ");
			}
		}
		return builder.toString();
	}
}
