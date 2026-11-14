package com.ctrlz.core.consequence;

/**
 * Every kind of world mutation the mod can attribute to an activity.
 *
 * <p>Activities are what the player did; consequences are what the world did because of it.
 * They are stored in a separate file and linked to the owning activity id.
 */
public enum ConsequenceKind {
	BLOCK_STATE(1, "block"),
	BLOCK_ENTITY_DATA(2, "block_entity"),
	CONTAINER_SLOTS(3, "container"),
	INVENTORY_SLOTS(4, "inventory"),
	ITEM_ENTITY_SPAWN(5, "item_entity"),
	ENTITY_HEALTH(6, "entity_health"),
	ENTITY_STATE(7, "entity_state"),
	ENTITY_REMOVED(8, "entity_removed"),
	PLAYER_STATE(9, "player_state"),
	ITEM_DURABILITY(10, "durability"),
	EXPERIENCE(11, "experience"),
	SCHEDULED_TICK(12, "scheduled_tick"),
	FLUID_STATE(13, "fluid");

	private final int id;
	private final String label;

	ConsequenceKind(int id, String label) {
		this.id = id;
		this.label = label;
	}

	public int id() {
		return id;
	}

	public String label() {
		return label;
	}

	public static ConsequenceKind byId(int id) {
		for (ConsequenceKind kind : values()) {
			if (kind.id == id) {
				return kind;
			}
		}
		return null;
	}
}
