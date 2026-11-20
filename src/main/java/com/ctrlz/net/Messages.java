package com.ctrlz.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Opcodes and the JSON payload shapes exchanged between the server and the history/config screens.
 */
public final class Messages {
	public static final Gson GSON = new GsonBuilder().serializeNulls().create();

	// client -> server
	public static final int C2S_REQUEST_HISTORY = 1;
	public static final int C2S_REQUEST_DETAIL = 2;
	public static final int C2S_REQUEST_ROLLBACK = 3;
	public static final int C2S_ROLLBACK_LAST = 4;
	public static final int C2S_REQUEST_CONFIG = 5;
	public static final int C2S_UPDATE_CONFIG = 6;

	// server -> client
	public static final int S2C_HISTORY = 101;
	public static final int S2C_DETAIL = 102;
	public static final int S2C_ROLLBACK_RESULT = 103;
	public static final int S2C_CONFIG = 104;
	public static final int S2C_NOTICE = 105;

	private Messages() {
	}

	public record QueryDto(String playerName, String kind, boolean onlyRollbackable, boolean onlyWithConsequences,
			String dimension, int offset, int limit) {
		public static QueryDto defaultQuery(int offset) {
			return new QueryDto("", "", false, false, "", offset, 12);
		}
	}

	public record ActivityDto(long id, String player, String kind, String dimension, int startTick, int endTick,
			long startMillis, long endMillis, int x, int y, int z, double distance, int consequences, String status,
			String reason, int[] stats) {
	}

	public record HistoryDto(int offset, int total, int pageSize, List<ActivityDto> items, String backend,
			boolean backendAvailable, long storageBytes, int storedActivities) {
	}

	public record TraceDto(double x, double y, double z, int tick, boolean sprinting, boolean sneaking) {
	}

	public record ConsequenceDto(long id, String kind, String origin, int tick, int x, int y, int z, String subject,
			String detail, String status) {
	}

	public record DetailDto(ActivityDto activity, List<TraceDto> trace, List<ConsequenceDto> consequences,
			String lastFailure) {
	}

	public record RollbackRequestDto(long activityId, boolean rollbackLast) {
	}

	/**
	 * The single answer shape of a restore, shared by the GUI row click and the Z hotkey.
	 *
	 * <p>{@code activities} / {@code restored} / {@code skipped} describe what was actually written,
	 * {@code truncated} tells the client whether newer history rows disappeared, {@code detail}
	 * carries the concrete reason (which record, what was expected, what was found),
	 * {@code boundaryId} is the permanent history boundary the restore stopped at, and
	 * {@code compensationFailed} warns that a failed restore could not be rolled back either. The
	 * client never infers success from anything else.
	 */
	public record RollbackResultDto(boolean success, long targetId, int activities, int restored, int skipped,
			boolean truncated, String conflict, String message, String detail, long boundaryId,
			boolean compensationFailed) {

		public RollbackResultDto(boolean success, long targetId, int activities, int restored, int skipped,
				boolean truncated, String conflict, String message, String detail) {
			this(success, targetId, activities, restored, skipped, truncated, conflict, message, detail, 0L, false);
		}

		public static RollbackResultDto notice(String key) {
			return new RollbackResultDto(false, 0L, 0, 0, 0, false, "notice", key, "", 0L, false);
		}
	}

	public record NoticeDto(String key, String detail) {
	}

	public record ConfigDto(String json) {
	}

	public record DetailRequestDto(long activityId) {
	}

	public static List<ActivityDto> emptyActivities() {
		return new ArrayList<>();
	}
}
