package com.ctrlz.core.rollback;

/**
 * Why a restore cannot proceed. Every value has its own player facing message, because "the world
 * changed" is not an explanation the player can act on.
 */
public enum ConflictType {
	NONE,
	/** A block is no longer in the state the activity left behind. */
	STATE_MISMATCH,
	/** The item in a slot differs from the recorded one. */
	ITEM_MISMATCH,
	/** The player, container, dimension or entity is not available any more. */
	TARGET_MISSING,
	BLOCK_ENTITY_LOST,
	/** The recorded change lives in storage that cannot be restored at all. */
	UNSUPPORTED_MODDED_STATE,
	NO_SNAPSHOT,
	/** The recorded payload cannot be decoded, so nothing may be written from it. */
	PAYLOAD_CORRUPT,
	/** A permanent history boundary blocks the restore. */
	BOUNDARY,
	/** Writing the plan threw; the applied prefix was compensated. */
	APPLY_FAILED,
	/** The compensation itself failed: the world may be inconsistent. */
	COMPENSATION_FAILED,
	/** The world did not end up where the simulation said it would. */
	VERIFY_FAILED,
	BACKEND_UNAVAILABLE;

	public boolean isBlocking() {
		return this != NONE;
	}
}
