package com.ctrlz.history;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryStackTest {
	private static final UUID PLAYER = UUID.randomUUID();

	private static Operation operation(long seq, long createdTick, OperationType type, int blockCount) {
		Transaction transaction = Transaction.open(PLAYER, "minecraft:overworld", type, "SURVIVAL", createdTick);
		for (int i = 0; i < blockCount; i++) {
			transaction.capturePrimary("block|" + seq + "|" + i,
					new Snapshot.Block("minecraft:overworld", i, "minecraft:stone", new byte[16]));
		}
		Operation sealed = transaction.seal(createdTick, seq);
		assertNotNull(sealed);
		return sealed;
	}

	@Test
	void expiredRecordsAreEvictedOnCommit() {
		HistoryStack stack = new HistoryStack();
		stack.add(operation(1, 0, OperationType.BREAK_BLOCK, 1), 0);
		stack.add(operation(2, 100, OperationType.BREAK_BLOCK, 1), 100);
		assertEquals(2, stack.count());

		stack.add(operation(3, HistoryStack.RETENTION_PLAY_TICKS + 200L, OperationType.BREAK_BLOCK, 1),
				HistoryStack.RETENTION_PLAY_TICKS + 200L);

		assertEquals(1, stack.count());
		assertEquals(2, stack.evictedCount());
	}

	@Test
	void latestUndoableSkipsUndoneBlockedExpiredAndMovementRecords() {
		HistoryStack stack = new HistoryStack();
		Operation breakBlock = operation(1, 10, OperationType.BREAK_BLOCK, 1);
		Operation move = operation(2, 11, OperationType.MOVE, 1);
		Operation place = operation(3, 12, OperationType.PLACE_BLOCK, 1);
		stack.add(breakBlock, 12);
		stack.add(move, 12);
		stack.add(place, 12);

		assertEquals(place, stack.latestUndoable(12));

		place.markUndone();
		assertEquals(breakBlock, stack.latestUndoable(12), "movement records are not undo targets");

		breakBlock.block("audit");
		assertNull(stack.latestUndoable(12));

		Operation fresh = operation(4, 13, OperationType.BREAK_BLOCK, 1);
		stack.add(fresh, 13);
		assertNull(stack.latestUndoable(13 + HistoryStack.RETENTION_PLAY_TICKS + 1),
				"everything outside the window is no longer undoable");
	}

	@Test
	void undoTargetStopsAtABlockedRecordInsteadOfSkippingIt() {
		HistoryStack stack = new HistoryStack();
		Operation older = operation(1, 10, OperationType.BREAK_BLOCK, 1);
		Operation newer = operation(2, 11, OperationType.BREAK_BLOCK, 1);
		stack.add(older, 11);
		stack.add(newer, 11);

		newer.block(UndoResult.Status.CONFLICT, "someone rebuilt there");

		assertEquals(newer, stack.undoTarget(11), "the blocked top record is still the undo target");
		assertEquals(older, stack.latestUndoable(11),
				"the skip-blocked selector would have undone the older record — the old behaviour v2 forbids");

		newer.markUndone();
		assertEquals(older, stack.undoTarget(11), "only an undone record lets the chain move down");
	}

	@Test
	void blockedRecordReplaysItsCachedResultAndIsNotUndoable() {
		Operation operation = operation(1, 10, OperationType.BREAK_BLOCK, 1);
		operation.block(UndoResult.Status.AUDIT_FAILED, "chest 1 -> 2");

		UndoResult result = operation.blockedResult();
		assertTrue(result.blocked());
		assertFalse(result.success());
		assertEquals(UndoResult.Status.AUDIT_FAILED, result.status());
		assertEquals("chest 1 -> 2", result.detail());
		assertEquals(UndoResult.Status.AUDIT_FAILED, operation.blockedStatus());
		assertFalse(operation.isUndoable(11), "a blocked record must never run the dry run again");
	}

	@Test
	void plainRefusalDoesNotBlockTheRecord() {
		Operation operation = operation(1, 10, OperationType.BREAK_BLOCK, 1);
		assertFalse(UndoResult.of(UndoResult.Status.BUSY).blocked());
		assertFalse(operation.blocked());
		assertFalse(operation.blockedResult().blocked());
		assertTrue(operation.isUndoable(11));
	}

	@Test
	void memoryLimitEvictsOldestEvenInsideTheWindow() {
		HistoryStack stack = new HistoryStack();
		int blockCount = 4096;
		int payload = 2048;
		int records = 0;
		while (stack.evictedCount() == 0 && records < 64) {
			Transaction transaction = Transaction.open(PLAYER, "minecraft:overworld", OperationType.BREAK_BLOCK,
					"SURVIVAL", records);
			for (int i = 0; i < blockCount; i++) {
				transaction.capturePrimary("block|" + records + "|" + i,
						new Snapshot.Block("minecraft:overworld", i, "minecraft:stone", new byte[payload]));
			}
			stack.add(transaction.seal(records, records), 0);
			records++;
		}

		assertTrue(stack.evictedCount() > 0, "the 32 MB budget must evict records");
		assertTrue(stack.estimatedBytes() <= HistoryStack.MEMORY_LIMIT_BYTES);
		assertTrue(stack.count() < records, "FIFO eviction dropped the oldest record");
		assertEquals(records - 1, stack.newestFirst().get(0).seq());
	}

	@Test
	void purgeEmptiesTheStack() {
		HistoryStack stack = new HistoryStack();
		stack.add(operation(1, 0, OperationType.PLACE_BLOCK, 2), 0);
		stack.purge();
		assertEquals(0, stack.count());
		assertEquals(0L, stack.estimatedBytes());
		assertEquals(-1L, stack.oldestPlayTick());
	}

	@Test
	void recordsSurviveThroughFindById() {
		HistoryStack stack = new HistoryStack();
		Operation operation = operation(7, 5, OperationType.CONTAINER_MOVE, 1);
		stack.add(operation, 5);
		assertEquals(operation, stack.findById(operation.operationId()));
		assertNull(stack.findById(UUID.randomUUID()));
	}

	@Test
	void restoredOperationsKeepIdempotencyState() {
		Operation operation = Operation.restored(UUID.randomUUID(), PLAYER, "minecraft:overworld", 3, 40, 41,
				OperationType.BREAK_BLOCK, "CREATIVE", Coverage.PARTIAL,
				List.of(new Snapshot.Block("minecraft:overworld", 1, "minecraft:dirt", new byte[0])),
				Map.of("block|minecraft:overworld|1", new StateFingerprint("minecraft:air#0:0")), true, 2, true);

		assertTrue(operation.undone());
		assertEquals(2L, operation.appliedSeq());
		assertEquals(Coverage.PARTIAL, operation.coverage());
		assertFalse(operation.isUndoable(42));
		assertEquals("minecraft:air#0:0",
				operation.afterFingerprints().get("block|minecraft:overworld|1").value());
	}
}
