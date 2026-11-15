package com.ctrlz.history;

import java.util.List;
import java.util.Map;

/**
 * Result of an undo attempt. Every failure path of the spec has its own status so the action bar can
 * explain what happened instead of silently doing nothing.
 */
public record UndoResult(Status status, int restored, String detail) {
	public enum Status {
		OK,
		DISABLED,
		BUSY,
		NO_HISTORY,
		ALREADY_UNDONE,
		EXPIRED,
		COVERAGE_NONE,
		CONFLICT,
		AUDIT_FAILED,
		BLOCK_UNLOADED,
		STRUCTURE_INCOMPLETE,
		APPLY_REVERTED,
		APPLY_FROZEN,
		NOT_SERVER_THREAD
	}

	public static UndoResult of(Status status) {
		return new UndoResult(status, 0, "");
	}

	public static UndoResult of(Status status, String detail) {
		return new UndoResult(status, 0, detail);
	}

	public boolean success() {
		return status == Status.OK;
	}

	/**
	 * Pre-computed restore plan. {@code steps} are in reverse dependency order; {@code previousValues}
	 * carries whatever each step needs to undo itself if a later step fails (I2).
	 */
	public record Plan(Operation operation, List<Step> steps, Map<String, Integer> auditBefore) {
		public record Step(Snapshot snapshot, Object previousValue) {
		}
	}

	/** Raised by the platform planner; the message is safe to show to players. */
	public static final class PlanException extends Exception {
		private final Status status;

		public PlanException(Status status, String detail) {
			super(detail);
			this.status = status;
		}

		public Status status() {
			return status;
		}
	}
}
