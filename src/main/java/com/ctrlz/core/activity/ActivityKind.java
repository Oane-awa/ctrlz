package com.ctrlz.core.activity;

/**
 * Every activity type the recorder understands.
 *
 * <p>The merge group defines what counts as "one continuous activity". Movement keeps merging
 * across direction changes, mining keeps merging while the player keeps breaking blocks in the
 * same area, and combat keeps merging while swings follow each other.
 */
public enum ActivityKind {
	MOVE(1, MergeGroup.MOVEMENT, 40),
	JUMP(2, MergeGroup.MOVEMENT, 10),
	ATTACK(3, MergeGroup.COMBAT, 100),
	BREAK_BLOCK(4, MergeGroup.MINING, 60),
	PLACE_BLOCK(5, MergeGroup.BUILDING, 60),
	USE_ITEM(6, MergeGroup.NONE, 0),
	OPEN_CONTAINER(7, MergeGroup.CONTAINER_ACCESS, 40),
	CLOSE_CONTAINER(8, MergeGroup.CONTAINER_ACCESS, 40),
	CRAFT(9, MergeGroup.CRAFTING, 60),
	MOVE_ITEM(10, MergeGroup.INVENTORY, 40),
	DROP_ITEM(11, MergeGroup.INVENTORY, 40),
	PICKUP_ITEM(12, MergeGroup.INVENTORY, 20),
	INTERACT_ENTITY(13, MergeGroup.NONE, 0),
	RESPAWN(14, MergeGroup.NONE, 0),
	UNKNOWN(0, MergeGroup.NONE, 0);

	public enum MergeGroup {
		MOVEMENT,
		COMBAT,
		MINING,
		BUILDING,
		CONTAINER_ACCESS,
		INVENTORY,
		CRAFTING,
		NONE
	}

	private final int id;
	private final MergeGroup group;
	private final int defaultMergeGapTicks;

	ActivityKind(int id, MergeGroup group, int defaultMergeGapTicks) {
		this.id = id;
		this.group = group;
		this.defaultMergeGapTicks = defaultMergeGapTicks;
	}

	public int id() {
		return id;
	}

	public MergeGroup group() {
		return group;
	}

	public int defaultMergeGapTicks() {
		return defaultMergeGapTicks;
	}

	public boolean canMergeWith(ActivityKind other) {
		return other != null && group == other.group && group != MergeGroup.NONE && this == other;
	}

	public static ActivityKind byId(int id) {
		for (ActivityKind kind : values()) {
			if (kind.id == id) {
				return kind;
			}
		}
		return UNKNOWN;
	}
}
