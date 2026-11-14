package com.ctrlz.server;

import com.ctrlz.core.activity.ActivityKind;
import com.ctrlz.core.activity.ActivityRecord;
import com.ctrlz.core.activity.ActivityStat;
import com.ctrlz.core.activity.ActivityStatus;
import com.ctrlz.core.consequence.ConsequenceRecord;
import com.ctrlz.core.trace.TracePoint;
import com.ctrlz.core.util.ByteIO;
import com.ctrlz.net.Messages;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the read only view models the history screen consumes.
 */
public final class HistoryService {
	private HistoryService() {
	}

	public static Messages.HistoryDto history(ServerPlayer viewer, Messages.QueryDto query) {
		CtrlZServer hub = CtrlZServer.get();
		if (hub == null || hub.store() == null) {
			return new Messages.HistoryDto(0, 0, 0, List.of(), "unavailable", false, 0L, 0);
		}
		List<ActivityRecord> filtered = filter(hub.store().allActivities(), query);
		int offset = Math.max(0, query.offset());
		int limit = Math.max(1, query.limit());
		int total = filtered.size();
		List<Messages.ActivityDto> items = new ArrayList<>();
		for (int i = offset; i < Math.min(total, offset + limit); i++) {
			items.add(toDto(filtered.get(i)));
		}
		return new Messages.HistoryDto(offset, total, limit, items, MineTracerBridge.backendName(),
				MineTracerBridge.isAvailable(), hub.store().totalBytes(), hub.store().activityCount());
	}

	public static Messages.DetailDto detail(long activityId) {
		CtrlZServer hub = CtrlZServer.get();
		if (hub == null || hub.store() == null) {
			return null;
		}
		ActivityRecord activity = hub.store().byId(activityId);
		if (activity == null) {
			return null;
		}
		List<Messages.TraceDto> trace = new ArrayList<>();
		for (TracePoint point : activity.trace()) {
			trace.add(new Messages.TraceDto(point.x, point.y, point.z, point.tick, point.sprinting, point.sneaking));
		}
		List<Messages.ConsequenceDto> consequences = new ArrayList<>();
		for (ConsequenceRecord consequence : hub.store().consequences(activityId)) {
			int x = consequence.hasPosition() ? ByteIO.unpackX(consequence.packedPos()) : 0;
			int y = consequence.hasPosition() ? ByteIO.unpackY(consequence.packedPos()) : 0;
			int z = consequence.hasPosition() ? ByteIO.unpackZ(consequence.packedPos()) : 0;
			consequences.add(new Messages.ConsequenceDto(consequence.id(), consequence.kind().label(),
					consequence.origin().name(), consequence.tick(), x, y, z, consequence.subject(),
					consequence.detail(), consequence.status().name()));
		}
		return new Messages.DetailDto(toDto(activity), trace, consequences);
	}

	private static List<ActivityRecord> filter(List<ActivityRecord> all, Messages.QueryDto query) {
		String name = query.playerName() == null ? "" : query.playerName().trim().toLowerCase();
		ActivityKind kind = null;
		if (query.kind() != null && !query.kind().isEmpty()) {
			try {
				kind = ActivityKind.valueOf(query.kind());
			} catch (IllegalArgumentException ignored) {
			}
		}
		String dimension = query.dimension() == null ? "" : query.dimension();
		List<ActivityRecord> result = new ArrayList<>();
		for (ActivityRecord record : all) {
			if (!name.isEmpty() && !record.playerName().toLowerCase().contains(name)) {
				continue;
			}
			if (kind != null && record.kind() != kind) {
				continue;
			}
			if (query.onlyRollbackable() && !record.status().isRollbackCandidate()) {
				continue;
			}
			if (query.onlyWithConsequences() && record.consequenceCount() <= 0) {
				continue;
			}
			if (!dimension.isEmpty() && !dimension.equals(record.dimension())) {
				continue;
			}
			result.add(record);
		}
		result.sort(Comparator.comparingLong(ActivityRecord::startMillis).reversed());
		return result;
	}

	private static Messages.ActivityDto toDto(ActivityRecord record) {
		int x = ByteIO.unpackX(record.startPacked());
		int y = ByteIO.unpackY(record.startPacked());
		int z = ByteIO.unpackZ(record.startPacked());
		int[] stats = new int[ActivityStat.COUNT];
		System.arraycopy(record.stats(), 0, stats, 0, stats.length);
		ActivityStatus status = record.status();
		return new Messages.ActivityDto(record.id(), record.playerName(), record.kind().name(), record.dimension(),
				record.startTick(), record.endTick(), record.startMillis(), record.endMillis(), x, y, z,
				record.distanceBlocks(), record.consequenceCount(), status.name(), record.statusReason(), stats);
	}
}
