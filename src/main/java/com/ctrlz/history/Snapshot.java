package com.ctrlz.history;

import java.util.Map;

/**
 * Immutable copy of one piece of state taken before an operation modified it.
 *
 * <p>All payloads are opaque bytes or plain strings so this package stays free of Minecraft types and
 * can be unit tested without a game instance. Positions are packed with the same scheme the mod uses
 * on disk (26 bits x, 12 bits y, 26 bits z), dimensions are dimension ids.
 *
 * <p>The "state after the operation" is <em>not</em> part of the snapshot: it only exists once the
 * operation is committed, so it is kept in {@link Operation#afterFingerprints()} keyed by
 * {@link SnapshotKeys#of(Snapshot)}.
 */
public sealed interface Snapshot {
	SnapshotKind kind();

	/** One line for logs and the detail screen. */
	String describe();

	enum SnapshotKind {
		BLOCK, INVENTORY, CONTAINER, ENTITY, PLAYER_STATE
	}

	/** Inventory bucket a snapshot belongs to. */
	enum Scope {
		MAIN, HOTBAR, ARMOR, OFFHAND, CONTAINER
	}

	record Block(String dimension, long packedPos, String blockState, byte[] blockEntityNbt) implements Snapshot {
		@Override
		public SnapshotKind kind() {
			return SnapshotKind.BLOCK;
		}

		@Override
		public String describe() {
			return "block " + blockState + " @ " + packedPos;
		}
	}

	record Inventory(java.util.UUID player, Scope scope, Map<Integer, byte[]> items) implements Snapshot {
		@Override
		public SnapshotKind kind() {
			return SnapshotKind.INVENTORY;
		}

		@Override
		public String describe() {
			return "inventory " + scope + " slots=" + items.size();
		}
	}

	record Container(String dimension, long packedPos, byte[] blockEntityNbt, Map<Integer, byte[]> items)
			implements Snapshot {
		@Override
		public SnapshotKind kind() {
			return SnapshotKind.CONTAINER;
		}

		@Override
		public String describe() {
			return "container @ " + packedPos + " slots=" + items.size();
		}
	}

	record Entity(java.util.UUID uuid, String typeId, byte[] fullNbt, boolean existedBefore, double x, double y,
			double z) implements Snapshot {
		@Override
		public SnapshotKind kind() {
			return SnapshotKind.ENTITY;
		}

		@Override
		public String describe() {
			return "entity " + typeId + " " + uuid;
		}
	}

	record PlayerState(String dimension, double x, double y, double z, float yaw, float pitch, double vx,
			double vy, double vz, float health, int food, int xp, byte[] effects, int fireTicks)
			implements Snapshot {
		@Override
		public SnapshotKind kind() {
			return SnapshotKind.PLAYER_STATE;
		}

		@Override
		public String describe() {
			return "player state " + dimension + " @ " + String.format("%.1f %.1f %.1f", x, y, z);
		}
	}
}
