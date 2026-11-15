package com.ctrlz.history;

import java.util.List;
import java.util.UUID;

/**
 * A committed, undoable root operation.
 *
 * <p>{@code operationId} identifies it, {@code appliedSeq} makes undo idempotent, and
 * {@code createdPlayTick} decides when it falls out of the 1 hour window.
 */
public final class Operation {
	private final UUID operationId;
	private final UUID playerUuid;
	private final String worldKey;
	private final long seq;
	private final long createdPlayTick;
	private final long committedPlayTick;
	private final OperationType type;
	private final String gameMode;
	private final Coverage coverage;
	private final List<Snapshot> snapshots;
	private final java.util.Map<String, StateFingerprint> afterFingerprints;
	private final boolean p2Touched;

	private boolean undone;
	private long appliedSeq;
	private boolean blocked;
	private String blockedReason = "";

	Operation(UUID operationId, UUID playerUuid, String worldKey, long seq, long createdPlayTick,
			long committedPlayTick, OperationType type, String gameMode, Coverage coverage, List<Snapshot> snapshots,
			java.util.Map<String, StateFingerprint> afterFingerprints, boolean p2Touched) {
		this.operationId = operationId;
		this.playerUuid = playerUuid;
		this.worldKey = worldKey;
		this.seq = seq;
		this.createdPlayTick = createdPlayTick;
		this.committedPlayTick = committedPlayTick;
		this.type = type;
		this.gameMode = gameMode;
		this.coverage = coverage;
		this.snapshots = List.copyOf(snapshots);
		this.afterFingerprints = java.util.Map.copyOf(afterFingerprints);
		this.p2Touched = p2Touched;
	}

	/** Restores a persisted record. */
	public static Operation restored(UUID operationId, UUID playerUuid, String worldKey, long seq,
			long createdPlayTick, long committedPlayTick, OperationType type, String gameMode, Coverage coverage,
			List<Snapshot> snapshots, java.util.Map<String, StateFingerprint> afterFingerprints, boolean undone,
			long appliedSeq, boolean p2Touched) {
		Operation operation = new Operation(operationId, playerUuid, worldKey, seq, createdPlayTick,
				committedPlayTick, type, gameMode, coverage, snapshots, afterFingerprints, p2Touched);
		operation.undone = undone;
		operation.appliedSeq = appliedSeq;
		return operation;
	}

	public UUID operationId() {
		return operationId;
	}

	public UUID playerUuid() {
		return playerUuid;
	}

	public String worldKey() {
		return worldKey;
	}

	public long seq() {
		return seq;
	}

	public long createdPlayTick() {
		return createdPlayTick;
	}

	public long committedPlayTick() {
		return committedPlayTick;
	}

	public OperationType type() {
		return type;
	}

	public String gameMode() {
		return gameMode;
	}

	public Coverage coverage() {
		return coverage;
	}

	public List<Snapshot> snapshots() {
		return snapshots;
	}

	/** State the operation left behind, keyed by {@link SnapshotKeys#of(Snapshot)}. */
	public java.util.Map<String, StateFingerprint> afterFingerprints() {
		return afterFingerprints;
	}

	public boolean p2Touched() {
		return p2Touched;
	}

	public boolean undone() {
		return undone;
	}

	public long appliedSeq() {
		return appliedSeq;
	}

	public boolean blocked() {
		return blocked;
	}

	public String blockedReason() {
		return blockedReason;
	}

	/** Marks the undo finished. The applied counter is the idempotency half key. */
	public void markUndone() {
		undone = true;
		appliedSeq++;
	}

	public void block(String reason) {
		blocked = true;
		blockedReason = reason == null ? "" : reason;
	}

	public boolean isUndoable(long playTick) {
		return !undone && !blocked && coverage.isUndoable() && type != OperationType.MOVE
				&& !isExpired(playTick);
	}

	public boolean isExpired(long playTick) {
		return playTick - createdPlayTick > HistoryStack.RETENTION_PLAY_TICKS;
	}

	public int blockSnapshotCount() {
		int count = 0;
		for (Snapshot snapshot : snapshots) {
			if (snapshot.kind() == Snapshot.SnapshotKind.BLOCK) {
				count++;
			}
		}
		return count;
	}

	public int entitySnapshotCount() {
		int count = 0;
		for (Snapshot snapshot : snapshots) {
			if (snapshot.kind() == Snapshot.SnapshotKind.ENTITY) {
				count++;
			}
		}
		return count;
	}

	/** Rough byte size used for the 32 MB budget. */
	public long estimatedBytes() {
		long size = 256L;
		for (Snapshot snapshot : snapshots) {
			size += 96L;
			if (snapshot instanceof Snapshot.Block block) {
				size += block.blockState().length() + block.blockEntityNbt().length;
			} else if (snapshot instanceof Snapshot.Container container) {
				size += container.blockEntityNbt().length;
				for (byte[] item : container.items().values()) {
					size += item.length;
				}
			} else if (snapshot instanceof Snapshot.Inventory inventory) {
				for (byte[] item : inventory.items().values()) {
					size += item.length;
				}
			} else if (snapshot instanceof Snapshot.Entity entity) {
				size += entity.fullNbt().length;
			} else if (snapshot instanceof Snapshot.PlayerState state) {
				size += state.effects().length;
			}
		}
		return size;
	}
}
