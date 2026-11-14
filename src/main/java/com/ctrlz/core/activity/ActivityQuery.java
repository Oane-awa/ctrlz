package com.ctrlz.core.activity;

import java.util.ArrayList;
import java.util.List;

/**
 * Filter and paging description used by the history GUI and by the server when it builds a page.
 */
public final class ActivityQuery {
	public String playerName = "";
	public ActivityKind kindFilter;
	public boolean onlyRollbackable;
	public boolean onlyWithConsequences;
	public long fromMillis;
	public long toMillis;
	public String dimension = "";
	public int offset;
	public int limit = 12;

	public List<ActivityRecord> apply(List<ActivityRecord> source) {
		List<ActivityRecord> filtered = new ArrayList<>();
		String name = playerName == null ? "" : playerName.trim().toLowerCase();
		for (ActivityRecord record : source) {
			if (!name.isEmpty() && !record.playerName().toLowerCase().contains(name)) {
				continue;
			}
			if (kindFilter != null && record.kind() != kindFilter) {
				continue;
			}
			if (onlyRollbackable && !record.status().isRollbackCandidate()) {
				continue;
			}
			if (onlyWithConsequences && record.consequenceCount() <= 0) {
				continue;
			}
			if (fromMillis > 0L && record.endMillis() < fromMillis) {
				continue;
			}
			if (toMillis > 0L && record.startMillis() > toMillis) {
				continue;
			}
			if (dimension != null && !dimension.isEmpty() && !dimension.equals(record.dimension())) {
				continue;
			}
			filtered.add(record);
		}
		filtered.sort((a, b) -> Long.compare(b.startMillis(), a.startMillis()));
		if (offset >= filtered.size()) {
			return List.of();
		}
		int end = Math.min(filtered.size(), offset + Math.max(1, limit));
		return new ArrayList<>(filtered.subList(offset, end));
	}
}
