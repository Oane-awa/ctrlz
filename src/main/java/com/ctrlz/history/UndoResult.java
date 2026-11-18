package com.ctrlz.history;

import java.util.List;
import java.util.Map;

/**
 * Result of an undo attempt. Every failure path of the spec has its own status so the action bar can
 * explain what happened instead of silently doing nothing.
 *
 * <p>{@code blocked} is the difference between "this attempt failed" and "this record is frozen for
 * good". A blocked record ends the undo chain: the failure is cached on the operation, later key
 * presses replay the cached answer without running the dry run again, and the undo never moves on to
 * an older record (skipping would break LIFO and can duplicate items).
 */
public record UndoResult(Status status, int restored, String detail, boolean blocked) {
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

	/** Non blocking result; the record stays undoable. */
	public UndoResult(Status status, int restored, String detail) {
		this(status, restored, detail, false);
	}

	public static UndoResult of(Status status) {
		return new UndoResult(status, 0, "");
	}

	public static UndoResult of(Status status, String detail) {
		return new UndoResult(status, 0, detail);
	}

	/**
	 * Refusal that freezes the record. Used for every abort that happened during the dry run and
	 * for the post-apply audit, because re-running those checks would keep producing the same answer
	 * (and possibly a different one, which is worse).
	 */
	public static UndoResult blocked(Status status, String detail) {
		return new UndoResult(status, 0, detail, true);
	}

	public UndoResult asBlocked() {
		return new UndoResult(status, restored, detail, true);
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
