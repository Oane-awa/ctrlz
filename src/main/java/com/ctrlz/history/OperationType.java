package com.ctrlz.history;

/** Root operations the undo system understands. Stage 1 implements the first three. */
public enum OperationType {
	BREAK_BLOCK,
	PLACE_BLOCK,
	CONTAINER_MOVE,
	CRAFT,
	DROP_ITEM,
	ATTACK,
	FLUID_PLACE,
	PISTON_PUSH,
	/** Continuous movement: recorded for diagnostics only, never selected by the undo key. */
	MOVE;

	public boolean isMovement() {
		return this == MOVE;
	}

	public boolean touchesWorldBlocks() {
		return this == BREAK_BLOCK || this == PLACE_BLOCK || this == FLUID_PLACE || this == PISTON_PUSH;
	}
}
