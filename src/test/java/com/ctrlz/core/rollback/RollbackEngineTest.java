package com.ctrlz.core.rollback;

import com.ctrlz.core.consequence.ConsequenceKind;
import com.ctrlz.core.consequence.ConsequenceOrigin;
import com.ctrlz.core.consequence.ConsequenceRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackEngineTest {
	private final List<String> applied = new ArrayList<>();
	private final List<String> reverted = new ArrayList<>();
	private final List<String> skipped = new ArrayList<>();

	private ConsequenceRecord consequence(long id, int tick, ConsequenceOrigin origin) {
		return new ConsequenceRecord(id, 1L, ConsequenceKind.BLOCK_STATE, origin, tick, "minecraft:overworld", 0L,
				"stone", new byte[0], new byte[0], 0, 0L, 0L, "");
	}

	private RollbackListener listener() {
		return new RollbackListener() {
			@Override
			public void onConflict(ConsequenceRecord c, ConflictType conflict) {
			}

			@Override
			public void onRestored(ConsequenceRecord c) {
			}

			@Override
			public void onSkipped(ConsequenceRecord c, String reason) {
				skipped.add(reason);
			}

			@Override
			public void onRevertedTransaction(ConsequenceRecord c) {
				reverted.add("revert-" + c.id());
			}
		};
	}

	@Test
	void restoresInReverseCausalOrder() {
		List<ConsequenceRecord> consequences = List.of(consequence(1, 10, ConsequenceOrigin.DIRECT),
				consequence(2, 30, ConsequenceOrigin.DELAYED), consequence(3, 20, ConsequenceOrigin.INDIRECT));
		RollbackEngine.Outcome outcome = RollbackEngine.rollback(consequences, new ConsequenceRestorer() {
			@Override
			public ConflictType check(ConsequenceRecord c) {
				return ConflictType.NONE;
			}

			@Override
			public boolean restore(ConsequenceRecord c) {
				applied.add("restore-" + c.id());
				return true;
			}

			@Override
			public boolean reapply(ConsequenceRecord c) {
				return true;
			}
		}, listener());
		assertTrue(outcome.success());
		assertEquals(List.of("restore-2", "restore-3", "restore-1"), applied);
	}

	@Test
	void conflictAbortsBeforeAnyWrite() {
		List<ConsequenceRecord> consequences = List.of(consequence(1, 10, ConsequenceOrigin.DIRECT),
				consequence(2, 20, ConsequenceOrigin.DIRECT));
		RollbackEngine.Outcome outcome = RollbackEngine.rollback(consequences, new ConsequenceRestorer() {
			@Override
			public ConflictType check(ConsequenceRecord c) {
				return c.id() == 2 ? ConflictType.STATE_MISMATCH : ConflictType.NONE;
			}

			@Override
			public boolean restore(ConsequenceRecord c) {
				applied.add("restore-" + c.id());
				return true;
			}

			@Override
			public boolean reapply(ConsequenceRecord c) {
				return true;
			}
		}, listener());
		assertFalse(outcome.success());
		assertEquals(ConflictType.STATE_MISMATCH, outcome.conflict());
		assertTrue(applied.isEmpty(), "a conflicting activity must not be partially restored");
	}

	@Test
	void failureMidwayRevertsTheAppliedSteps() {
		List<ConsequenceRecord> consequences = List.of(consequence(1, 10, ConsequenceOrigin.DIRECT),
				consequence(2, 20, ConsequenceOrigin.DIRECT), consequence(3, 30, ConsequenceOrigin.DIRECT));
		RollbackEngine.Outcome outcome = RollbackEngine.rollback(consequences, new ConsequenceRestorer() {
			@Override
			public ConflictType check(ConsequenceRecord c) {
				return ConflictType.NONE;
			}

			@Override
			public boolean restore(ConsequenceRecord c) {
				if (c.id() == 2) {
					return false;
				}
				applied.add("restore-" + c.id());
				return true;
			}

			@Override
			public boolean reapply(ConsequenceRecord c) {
				return true;
			}
		}, listener());
		assertFalse(outcome.success());
		assertEquals(List.of("restore-3"), applied);
		assertEquals(List.of("revert-3"), reverted);
	}

	@Test
	void unattributedChangesAreNeverReverted() {
		List<ConsequenceRecord> consequences = List.of(consequence(1, 10, ConsequenceOrigin.DIRECT),
				consequence(2, 20, ConsequenceOrigin.UNATTRIBUTED));
		RollbackEngine.Outcome outcome = RollbackEngine.rollback(consequences, new ConsequenceRestorer() {
			@Override
			public ConflictType check(ConsequenceRecord c) {
				return ConflictType.NONE;
			}

			@Override
			public boolean restore(ConsequenceRecord c) {
				applied.add("restore-" + c.id());
				return true;
			}

			@Override
			public boolean reapply(ConsequenceRecord c) {
				return true;
			}
		}, listener());
		assertTrue(outcome.success());
		assertEquals(List.of("restore-1"), applied);
		assertEquals(1, outcome.skipped());
	}
}
