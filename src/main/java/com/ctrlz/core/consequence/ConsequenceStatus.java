package com.ctrlz.core.consequence;

public enum ConsequenceStatus {
	PENDING(0),
	ROLLED_BACK(1),
	CONFLICT(2),
	SKIPPED(3),
	LOST(4);

	private final int id;

	ConsequenceStatus(int id) {
		this.id = id;
	}

	public int id() {
		return id;
	}

	public static ConsequenceStatus byId(int id) {
		for (ConsequenceStatus status : values()) {
			if (status.id == id) {
				return status;
			}
		}
		return PENDING;
	}
}
