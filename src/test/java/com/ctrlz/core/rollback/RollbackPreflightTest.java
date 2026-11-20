package com.ctrlz.core.rollback;

import com.ctrlz.core.consequence.ConsequenceKind;
import com.ctrlz.core.consequence.ConsequenceOrigin;
import com.ctrlz.core.consequence.ConsequenceRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The virtual preflight of a whole undo chain (AUDIT-004).
 *
 * <p>Every case here is one that the old "check each operation against the live world" preflight
 * reported as a conflict even though nothing was wrong, or one where it would have missed a real
 * conflict because a later step had already overwritten the state.
 */
class RollbackPreflightTest {
	private static final String DIM = "minecraft:overworld";

	private static ConsequenceRecord block(long id, long activity, int tick, long pos, String before, String after) {
		return new ConsequenceRecord(id, activity, ConsequenceKind.BLOCK_STATE, ConsequenceOrigin.DIRECT, tick, DIM,
				pos, "minecraft:stone", before.getBytes(StandardCharsets.UTF_8),
				after.getBytes(StandardCharsets.UTF_8), 0, 0L, 0L, "");
	}

	private static ConsequenceRecord slot(long id, long activity, int tick, String subject, String before,
			String after) {
		return new ConsequenceRecord(id, activity, ConsequenceKind.INVENTORY_SLOTS, ConsequenceOrigin.DIRECT, tick,
				DIM, ConsequenceRecord.NO_POSITION, subject, before.getBytes(StandardCharsets.UTF_8),
				after.getBytes(StandardCharsets.UTF_8), 0, 0L, 0L, "");
	}

	private static ConsequenceRecord spawn(long id, long activity, int tick, String uuid, String before,
			String after) {
		return new ConsequenceRecord(id, activity, ConsequenceKind.ITEM_ENTITY_SPAWN, ConsequenceOrigin.DIRECT, tick,
				DIM, 4L, uuid, before.getBytes(StandardCharsets.UTF_8),
				after.getBytes(StandardCharsets.UTF_8), 0, 0L, 0L, "");
	}

	/** Serves the "live world" from a map, so a test can state exactly what the world looks like. */
	private static final class FakeWorld implements RollbackPreflight.StateReader {
		private final Map<String, String> states = new HashMap<>();
		private final Map<String, Boolean> existing = new HashMap<>();

		FakeWorld state(String key, String value) {
			states.put(key, value);
			return this;
		}

		FakeWorld missing(String key) {
			existing.put(key, false);
			return this;
		}

		@Override
		public byte[] readState(String key, ConsequenceRecord change) {
			String value = states.get(key);
			return value == null ? null : value.getBytes(StandardCharsets.UTF_8);
		}

		@Override
		public boolean stateExists(String key, ConsequenceRecord change) {
			return existing.getOrDefault(key, true);
		}
	}

	private static List<RollbackPreflight.Batch> batches(RollbackPreflight.Batch... entries) {
		List<RollbackPreflight.Batch> list = new ArrayList<>();
		for (RollbackPreflight.Batch entry : entries) {
			list.add(entry);
		}
		return list;
	}

	private static String text(byte[] value) {
		return value == null ? null : new String(value, StandardCharsets.UTF_8);
	}

	@Test
	void threeConsecutiveChangesOfOneBlockSimulateCleanly() {
		String key = RollbackPreflight.keyOf(block(1, 1, 0, 5L, "a", "b"));
		assertEquals("block|" + DIM + "|5", key);
		FakeWorld world = new FakeWorld().state(key, "gold_block");

		RollbackPreflight.Outcome outcome = RollbackPreflight.simulate(batches(
				new RollbackPreflight.Batch(3, List.of(block(3, 3, 30, 5L, "air", "gold_block"))),
				new RollbackPreflight.Batch(2, List.of(block(2, 2, 20, 5L, "stone", "air"))),
				new RollbackPreflight.Batch(1, List.of(block(1, 1, 10, 5L, "dirt", "stone")))), world);

		assertTrue(outcome.ok(), "a normal sequence of changes must not be reported as a conflict");
		assertEquals("dirt", text(outcome.expected().get(key)), "the chain must end at the oldest before state");
	}

	@Test
	void aChangeBySomebodyElseStopsTheSimulation() {
		String key = "block|" + DIM + "|5";
		FakeWorld world = new FakeWorld().state(key, "diamond_block");

		RollbackPreflight.Outcome outcome = RollbackPreflight.simulate(batches(
				new RollbackPreflight.Batch(3, List.of(block(3, 3, 30, 5L, "air", "gold_block")))), world);

		assertFalse(outcome.ok());
		assertNotNull(outcome.failure());
		assertEquals(ConflictType.STATE_MISMATCH, outcome.failure().type());
		assertEquals(3L, outcome.failure().activityId());
		assertEquals("diamond_block", text(outcome.failure().actual()));
	}

	@Test
	void severalChangesInsideOneActivityAreInvertedInReverseOrder() {
		String key = "slot|" + DIM + "|" + ConsequenceRecord.NO_POSITION + "|inv#p#0";
		FakeWorld world = new FakeWorld().state(key, "diamond");

		RollbackPreflight.Outcome outcome = RollbackPreflight.simulate(batches(new RollbackPreflight.Batch(1,
				List.of(slot(1, 1, 10, "inv#p#0", "iron", "gold"), slot(2, 1, 20, "inv#p#0", "gold", "diamond")))),
				world);

		assertTrue(outcome.ok(), "the group itself must be undone newest first");
		assertEquals("iron", text(outcome.expected().get(key)));
	}

	@Test
	void theSlotOfAnOlderActivityIsComparedAgainstTheVirtualState() {
		String key = "slot|" + DIM + "|" + ConsequenceRecord.NO_POSITION + "|inv#p#3";
		FakeWorld world = new FakeWorld().state(key, "emerald");

		RollbackPreflight.Outcome outcome = RollbackPreflight.simulate(batches(
				new RollbackPreflight.Batch(2, List.of(slot(2, 2, 20, "inv#p#3", "gold", "emerald"))),
				new RollbackPreflight.Batch(1, List.of(slot(1, 1, 10, "inv#p#3", "iron", "gold")))), world);

		assertTrue(outcome.ok(), "two activities editing one slot must be simulated, not rejected");
		assertEquals("iron", text(outcome.expected().get(key)));
	}

	@Test
	void aPickupMakesTheOlderDropRecordSeeTheEntity() {
		String key = "entity|u1";
		FakeWorld world = new FakeWorld().missing(key);

		RollbackPreflight.Outcome outcome = RollbackPreflight.simulate(batches(
				new RollbackPreflight.Batch(2, List.of(spawn(2, 2, 20, "u1", "dirt", ""))),
				new RollbackPreflight.Batch(1, List.of(spawn(1, 1, 10, "u1", "", "dirt")))), world);

		assertTrue(outcome.ok(), "undoing the pickup restores the drop the older record expects");
		assertEquals(0, outcome.expected().get(key)[0],
				"undoing both steps must end with no drop at all, like before the break");
	}

	@Test
	void aDropThatBecameSomethingElseIsAConflict() {
		String key = "entity|u1";
		FakeWorld world = new FakeWorld().state(key, "stone");

		RollbackPreflight.Outcome outcome = RollbackPreflight.simulate(batches(
				new RollbackPreflight.Batch(1, List.of(spawn(1, 1, 10, "u1", "", "dirt")))), world);

		assertFalse(outcome.ok());
		assertEquals(ConflictType.ITEM_MISMATCH, outcome.failure().type());
	}

	@Test
	void aMissingEntityBlocksAnExistenceOnlyStep() {
		ConsequenceRecord health = new ConsequenceRecord(1L, 1L, ConsequenceKind.ENTITY_HEALTH,
				ConsequenceOrigin.DIRECT, 10, DIM, 4L, "entity#gone", "20.0".getBytes(StandardCharsets.UTF_8),
				"10.0".getBytes(StandardCharsets.UTF_8), 0, 0L, 0L, "");
		FakeWorld world = new FakeWorld().missing("health|entity#gone");

		RollbackPreflight.Outcome outcome = RollbackPreflight.simulate(
				batches(new RollbackPreflight.Batch(1, List.of(health))), world);

		assertFalse(outcome.ok());
		assertEquals(ConflictType.TARGET_MISSING, outcome.failure().type());
	}

	@Test
	void unattributedChangesAreNeverSimulated() {
		ConsequenceRecord unattributed = new ConsequenceRecord(1L, 1L, ConsequenceKind.BLOCK_STATE,
				ConsequenceOrigin.UNATTRIBUTED, 10, DIM, 5L, "minecraft:stone",
				"a".getBytes(StandardCharsets.UTF_8), "b".getBytes(StandardCharsets.UTF_8), 0, 0L, 0L, "");
		FakeWorld world = new FakeWorld().state("block|" + DIM + "|5", "something_else");

		RollbackPreflight.Outcome outcome = RollbackPreflight.simulate(
				batches(new RollbackPreflight.Batch(1, List.of(unattributed))), world);

		assertTrue(outcome.ok());
		assertTrue(outcome.expected().isEmpty());
	}
}
