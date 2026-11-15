package com.ctrlz.history;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Capture context of a single operation: from the first captured change until commit or abort.
 *
 * <p>Only one transaction per player is active at a time. A duplicate capture for a target that is
 * already recorded is ignored (the original "before" state wins) and counted as a warning, so a
 * multi stage modification can never overwrite the state it has to restore.
 */
public final class Transaction {
	/** How long an operation may stay open before it is sealed as partial. */
	public static final int TIMEOUT_TICKS = 20;

	private final UUID operationId;
	private final UUID playerUuid;
	private final String worldKey;
	private final OperationType type;
	private final String gameMode;
	private final long createdPlayTick;
	private final long deadlinePlayTick;
	private final String actorTag;

	private final Map<String, Snapshot> primary = new LinkedHashMap<>();
	private final List<Snapshot> chain = new ArrayList<>();
	private final Map<String, StateFingerprint> after = new LinkedHashMap<>();
	private Coverage hint = Coverage.FULL;
	private int duplicateCaptures;
	private boolean sealed;

	private Transaction(UUID operationId, UUID playerUuid, String worldKey, OperationType type, String gameMode,
			long createdPlayTick, long deadlinePlayTick, String actorTag) {
		this.operationId = operationId;
		this.playerUuid = playerUuid;
		this.worldKey = worldKey;
		this.type = type;
		this.gameMode = gameMode;
		this.createdPlayTick = createdPlayTick;
		this.deadlinePlayTick = deadlinePlayTick;
		this.actorTag = actorTag;
	}

	public static Transaction open(UUID playerUuid, String worldKey, OperationType type, String gameMode,
			long playTick) {
		return new Transaction(UUID.randomUUID(), playerUuid, worldKey, type, gameMode, playTick,
				playTick + TIMEOUT_TICKS, null);
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

	public OperationType type() {
		return type;
	}

	public String gameMode() {
		return gameMode;
	}

	public long createdPlayTick() {
		return createdPlayTick;
	}

	public String actorTag() {
		return actorTag;
	}

	public boolean isSealed() {
		return sealed;
	}

	public boolean hasSnapshots() {
		return !primary.isEmpty();
	}

	public int duplicateCaptures() {
		return duplicateCaptures;
	}

	public int snapshotCount() {
		return primary.size() + chain.size();
	}

	/**
	 * Records the state of a target before it was modified.
	 *
	 * @return true when stored, false when this target was already captured (duplicate ignored)
	 */
	public boolean capturePrimary(String targetKey, Snapshot snapshot) {
		if (primary.containsKey(targetKey)) {
			duplicateCaptures++;
			return false;
		}
		primary.put(targetKey, snapshot);
		return true;
	}

	/** Records the state the operation left behind, used later for conflict detection. */
	public void recordAfter(String targetKey, StateFingerprint fingerprint) {
		after.put(targetKey, fingerprint);
	}

	public Map<String, StateFingerprint> after() {
		return after;
	}

	/** Read-only view of the root targets captured so far, keyed by {@link SnapshotKeys}. */
	public Map<String, Snapshot> primary() {
		return java.util.Collections.unmodifiableMap(primary);
	}

	/** Attaches a change that belongs to this operation but is not itself the root target. */
	public void captureChained(Snapshot snapshot) {
		chain.add(snapshot);
	}

	public void degrade(Coverage coverage) {
		if (coverage.ordinal() > hint.ordinal()) {
			hint = coverage;
		}
	}

	public boolean expired(long playTick) {
		return playTick >= deadlinePlayTick;
	}

	public boolean isFull() {
		return hint == Coverage.FULL;
	}

	/**
	 * Seals the transaction and produces the operation that goes into the history stack.
	 * Returns null when there is nothing restorable (coverage NONE).
	 */
	public Operation seal(long playTick, long seq) {
		sealed = true;
		Coverage coverage = primary.isEmpty() ? Coverage.NONE : hint;
		if (!coverage.isUndoable()) {
			return null;
		}
		List<Snapshot> snapshots = new ArrayList<>(primary.values());
		snapshots.addAll(chain);
		return new Operation(operationId, playerUuid, worldKey, seq, createdPlayTick, playTick, type, gameMode,
				coverage, snapshots, new LinkedHashMap<>(after), coverage == Coverage.PARTIAL);
	}
}
