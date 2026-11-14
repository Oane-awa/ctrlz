package com.ctrlz.core.rollback;

public enum ConflictType {
	NONE,
	STATE_MISMATCH,
	TARGET_MISSING,
	BLOCK_ENTITY_LOST,
	UNSUPPORTED_MODDED_STATE,
	NO_SNAPSHOT,
	BACKEND_UNAVAILABLE;

	public boolean isBlocking() {
		return this != NONE;
	}
}
