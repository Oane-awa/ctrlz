package com.ctrlz.history;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionTest {
	private static final UUID PLAYER = UUID.randomUUID();

	@Test
	void duplicateCaptureKeepsTheFirstStateAndIsCounted() {
		Transaction transaction = Transaction.open(PLAYER, "minecraft:overworld", OperationType.PLACE_BLOCK,
				"SURVIVAL", 100);
		String key = "block|minecraft:overworld|123";

		assertTrue(transaction.capturePrimary(key,
				new Snapshot.Block("minecraft:overworld", 123, "minecraft:air", new byte[0])));
		assertFalse(transaction.capturePrimary(key,
				new Snapshot.Block("minecraft:overworld", 123, "minecraft:stone", new byte[0])),
				"the original before state must win");

		assertEquals(1, transaction.duplicateCaptures());
		assertEquals(1, transaction.snapshotCount());
	}

	@Test
	void emptyTransactionSealsToNothingBecauseCoverageIsNone() {
		Transaction transaction = Transaction.open(PLAYER, "minecraft:overworld", OperationType.BREAK_BLOCK,
				"SURVIVAL", 100);
		assertNull(transaction.seal(101, 1));
	}

	@Test
	void sealedOperationCarriesAfterFingerprintsAndCoverage() {
		Transaction transaction = Transaction.open(PLAYER, "minecraft:overworld", OperationType.BREAK_BLOCK,
				"CREATIVE", 100);
		String key = "block|minecraft:overworld|55";
		transaction.capturePrimary(key, new Snapshot.Block("minecraft:overworld", 55, "minecraft:stone", new byte[0]));
		transaction.recordAfter(key, new StateFingerprint("minecraft:air#0:0"));
		transaction.degrade(Coverage.PARTIAL);

		Operation operation = transaction.seal(103, 9);

		assertNotNull(operation);
		assertEquals(Coverage.PARTIAL, operation.coverage());
		assertTrue(operation.p2Touched());
		assertEquals(9L, operation.seq());
		assertEquals(100L, operation.createdPlayTick());
		assertEquals("CREATIVE", operation.gameMode());
		assertEquals("minecraft:air#0:0", operation.afterFingerprints().get(key).value());
	}

	@Test
	void transactionExpiresAfterItsTimeout() {
		Transaction transaction = Transaction.open(PLAYER, "minecraft:overworld", OperationType.BREAK_BLOCK,
				"SURVIVAL", 100);
		assertFalse(transaction.expired(100 + Transaction.TIMEOUT_TICKS - 1));
		assertTrue(transaction.expired(100 + Transaction.TIMEOUT_TICKS));
	}

	@Test
	void degradationOnlyEverMovesTowardsNone() {
		Transaction transaction = Transaction.open(PLAYER, "minecraft:overworld", OperationType.BREAK_BLOCK,
				"SURVIVAL", 0);
		transaction.capturePrimary("block|a|1",
				new Snapshot.Block("minecraft:overworld", 1, "minecraft:stone", new byte[0]));
		transaction.degrade(Coverage.NONE);
		transaction.degrade(Coverage.PARTIAL);
		assertNull(transaction.seal(0, 1), "NONE must stay NONE");
	}

	@Test
	void snapshotKeysAreStablePerKind() {
		Snapshot block = new Snapshot.Block("minecraft:overworld", 7, "minecraft:stone", new byte[0]);
		Snapshot container = new Snapshot.Container("minecraft:overworld", 7, new byte[0], java.util.Map.of());
		Snapshot inventory = new Snapshot.Inventory(PLAYER, Snapshot.Scope.MAIN, java.util.Map.of());
		Snapshot playerState = new Snapshot.PlayerState("minecraft:overworld", 0, 0, 0, 0, 0, 0, 0, 0, 20, 20, 0,
				new byte[0], 0);

		assertEquals("block|minecraft:overworld|7", SnapshotKeys.of(block));
		assertEquals("container|minecraft:overworld|7", SnapshotKeys.of(container));
		assertEquals("inventory|" + PLAYER + "|MAIN", SnapshotKeys.of(inventory));
		assertEquals("player|minecraft:overworld", SnapshotKeys.of(playerState));
	}

	@Test
	void operationMemoryEstimateGrowsWithPayload() {
		Transaction small = Transaction.open(PLAYER, "minecraft:overworld", OperationType.BREAK_BLOCK, "SURVIVAL", 0);
		small.capturePrimary("block|a|1", new Snapshot.Block("minecraft:overworld", 1, "minecraft:stone", new byte[0]));
		Operation smallOperation = small.seal(0, 1);

		Transaction large = Transaction.open(PLAYER, "minecraft:overworld", OperationType.BREAK_BLOCK, "SURVIVAL", 0);
		large.capturePrimary("block|a|1",
				new Snapshot.Block("minecraft:overworld", 1, "minecraft:stone", new byte[4096]));
		Operation largeOperation = large.seal(0, 2);

		assertNotNull(smallOperation);
		assertNotNull(largeOperation);
		assertTrue(largeOperation.estimatedBytes() > smallOperation.estimatedBytes());
	}
}
