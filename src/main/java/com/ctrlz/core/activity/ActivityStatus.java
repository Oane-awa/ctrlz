package com.ctrlz.core.activity;

/**
 * Lifecycle of a recorded activity.
 */
public enum ActivityStatus {
	RECORDING(0),
	SEALED(1),
	ROLLED_BACK(2),
	PARTIALLY_ROLLED_BACK(3),
	NOT_ROLLBACKABLE(4),
	INCOMPLETE(5),
	PRUNED(6);

	private final int id;

	ActivityStatus(int id) {
		this.id = id;
	}

	public int id() {
		return id;
	}

	public boolean isRollbackCandidate() {
		return this == SEALED || this == PARTIALLY_ROLLED_BACK;
	}

	public static ActivityStatus byId(int id) {
		for (ActivityStatus status : values()) {
			if (status.id == id) {
				return status;
			}
		}
		return INCOMPLETE;
	}
}
