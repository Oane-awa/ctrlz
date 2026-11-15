package com.ctrlz.history;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * In-memory history with the retention policy from the spec: a 1 hour play-time window plus a hard
 * 32 MB budget, no record count limit. Eviction runs on every commit (no background scanner).
 */
public final class HistoryStack {
	/** 1 hour of play time. */
	public static final long RETENTION_PLAY_TICKS = 72000L;
	/** Hard memory budget. */
	public static final long MEMORY_LIMIT_BYTES = 32L * 1024L * 1024L;

	private final ArrayDeque<Operation> operations = new ArrayDeque<>();
	private long estimatedBytes;
	private long evicted;

	public void add(Operation operation, long playTick) {
		operations.addLast(operation);
		estimatedBytes += operation.estimatedBytes();
		evictExpired(playTick);
		enforceMemoryLimit();
	}

	/** Drops records that fell out of the 1 hour window. */
	public void evictExpired(long playTick) {
		while (!operations.isEmpty() && operations.peekFirst().isExpired(playTick)) {
			Operation removed = operations.pollFirst();
			estimatedBytes -= removed.estimatedBytes();
			evicted++;
		}
	}

	/** FIFO eviction: the oldest record goes first even if it is still inside the window. */
	private void enforceMemoryLimit() {
		while (estimatedBytes > MEMORY_LIMIT_BYTES && !operations.isEmpty()) {
			Operation removed = operations.pollFirst();
			estimatedBytes -= removed.estimatedBytes();
			evicted++;
		}
	}

	/**
	 * The operation the undo key acts on: newest first, skipping undone, blocked, expired,
	 * coverage NONE and movement records.
	 */
	public Operation latestUndoable(long playTick) {
		var iterator = operations.descendingIterator();
		while (iterator.hasNext()) {
			Operation operation = iterator.next();
			if (operation.isUndoable(playTick)) {
				return operation;
			}
		}
		return null;
	}

	public List<Operation> newestFirst() {
		List<Operation> list = new ArrayList<>(operations.size());
		var iterator = operations.descendingIterator();
		while (iterator.hasNext()) {
			list.add(iterator.next());
		}
		return list;
	}

	public Operation findById(UUID operationId) {
		for (Operation operation : operations) {
			if (operation.operationId().equals(operationId)) {
				return operation;
			}
		}
		return null;
	}

	public void purge() {
		operations.clear();
		estimatedBytes = 0L;
	}

	public int count() {
		return operations.size();
	}

	public long estimatedBytes() {
		return estimatedBytes;
	}

	public long evictedCount() {
		return evicted;
	}

	public long oldestPlayTick() {
		Operation oldest = operations.peekFirst();
		return oldest == null ? -1L : oldest.createdPlayTick();
	}

	public void replaceAll(List<Operation> restored) {
		purge();
		for (Operation operation : restored) {
			operations.addLast(operation);
			estimatedBytes += operation.estimatedBytes();
		}
		enforceMemoryLimit();
	}
}
