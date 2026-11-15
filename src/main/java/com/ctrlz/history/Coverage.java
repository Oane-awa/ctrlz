package com.ctrlz.history;

/** How much of an operation can be restored. Only FULL and PARTIAL are undoable. */
public enum Coverage {
	/** Every P0 element was captured and validated. */
	FULL,
	/** Everything restorable was captured, but the operation touched P2 state. */
	PARTIAL,
	/** P0 capture is incomplete: never committed, never undoable. */
	NONE;

	public boolean isUndoable() {
		return this != NONE;
	}
}
