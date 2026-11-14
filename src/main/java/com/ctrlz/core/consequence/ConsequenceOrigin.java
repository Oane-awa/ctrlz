package com.ctrlz.core.consequence;

/**
 * How a consequence was reached from a player activity.
 *
 * <p>This is the causal classification required by the rollback rules: only DIRECT and
 * INDIRECT/DELAYED consequences that carry a reliable causal trail may be reverted together
 * with the activity, and UNATTRIBUTED changes are never claimed by an activity.
 */
public enum ConsequenceOrigin {
	DIRECT(0),
	INDIRECT(1),
	DELAYED(2),
	UNATTRIBUTED(3);

	private final int id;

	ConsequenceOrigin(int id) {
		this.id = id;
	}

	public int id() {
		return id;
	}

	public boolean isAttributable() {
		return this != UNATTRIBUTED;
	}

	public static ConsequenceOrigin byId(int id) {
		for (ConsequenceOrigin origin : values()) {
			if (origin.id == id) {
				return origin;
			}
		}
		return UNATTRIBUTED;
	}
}
