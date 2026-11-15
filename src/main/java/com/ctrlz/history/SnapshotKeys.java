package com.ctrlz.history;

/** Stable identity of a snapshot target, used to pair before state with the recorded after state. */
public final class SnapshotKeys {
	private SnapshotKeys() {
	}

	public static String of(Snapshot snapshot) {
		if (snapshot instanceof Snapshot.Block block) {
			return "block|" + block.dimension() + "|" + block.packedPos();
		}
		if (snapshot instanceof Snapshot.Container container) {
			return "container|" + container.dimension() + "|" + container.packedPos();
		}
		if (snapshot instanceof Snapshot.Inventory inventory) {
			return "inventory|" + inventory.player() + "|" + inventory.scope();
		}
		if (snapshot instanceof Snapshot.Entity entity) {
			return "entity|" + entity.uuid();
		}
		if (snapshot instanceof Snapshot.PlayerState state) {
			return "player|" + state.dimension();
		}
		return "unknown";
	}
}
