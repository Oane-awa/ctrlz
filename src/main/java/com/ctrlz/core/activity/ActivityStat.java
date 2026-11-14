package com.ctrlz.core.activity;

/**
 * Fixed slot counters attached to an activity. Using a fixed array keeps the on disk record
 * tiny and lets the GUI show aggregates without deserialising every consequence.
 */
public enum ActivityStat {
	BLOCKS_BROKEN,
	BLOCKS_PLACED,
	ATTACKS,
	CRITICAL_ATTACKS,
	DAMAGE_DEALT_X100,
	JUMPS,
	CONTAINERS_OPENED,
	ITEMS_MOVED,
	ITEMS_CRAFTED,
	ITEMS_DROPPED,
	ITEMS_PICKED_UP,
	BLOCKS_WALKED_CM,
	TICKS;

	public static final int COUNT = values().length;
}
