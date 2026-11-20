package com.ctrlz.core.rollback;

import com.ctrlz.core.activity.ActivityStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The truncation boundary of "restore to this node".
 *
 * <p>The examples use the spec's own chain: A → B → C → D → E → F.
 */
class RestorePlanTest {
	private static final UUID ALICE = UUID.randomUUID();
	private static final UUID BOB = UUID.randomUUID();

	private static List<RestorePlan.Node> chain(UUID player, int from, int to) {
		List<RestorePlan.Node> nodes = new ArrayList<>();
		for (int id = from; id <= to; id++) {
			nodes.add(new RestorePlan.Node(id, player, ActivityStatus.SEALED, false));
		}
		return nodes;
	}

	@Test
	void restoringToANodeKeepsItAndDropsEverythingAfterIt() {
		// A B C D E F, restore to C: keep A B C, undo D E F newest first.
		assertEquals(List.of(6L, 5L, 4L), RestorePlan.victims(chain(ALICE, 1, 6), 3L, ALICE, true));
		assertEquals(3L, RestorePlan.anchor(3L, true), "the target itself survives");
	}

	@Test
	void undoingTheNewestNodeRemovesItToo() {
		assertEquals(List.of(6L), RestorePlan.victims(chain(ALICE, 1, 6), 6L, ALICE, false));
		assertEquals(5L, RestorePlan.anchor(6L, false), "a hotkey undo has no surviving target");
	}

	@Test
	void orderingComesFromIdsNotFromTicks() {
		// Several actions inside one tick: the ids are the only order that exists, and they must never
		// be sorted by a timestamp that is identical for all of them.
		assertEquals(List.of(12L, 11L), RestorePlan.victims(chain(ALICE, 10, 12), 10L, ALICE, true));
	}

	@Test
	void anotherPlayersBranchIsNeverTouched() {
		List<RestorePlan.Node> nodes = new ArrayList<>(chain(ALICE, 1, 3));
		nodes.add(new RestorePlan.Node(4L, BOB, ActivityStatus.SEALED, false));
		nodes.add(new RestorePlan.Node(5L, ALICE, ActivityStatus.SEALED, false));
		assertEquals(List.of(5L), RestorePlan.victims(nodes, 3L, ALICE, true),
				"only the owner's newer records belong to that branch");
	}

	@Test
	void alreadyUndoneAndPrunedRecordsDoNotBlockAnything() {
		List<RestorePlan.Node> nodes = List.of(
				new RestorePlan.Node(1L, ALICE, ActivityStatus.SEALED, false),
				new RestorePlan.Node(2L, ALICE, ActivityStatus.ROLLED_BACK, false),
				new RestorePlan.Node(3L, ALICE, ActivityStatus.PRUNED, false),
				new RestorePlan.Node(4L, ALICE, ActivityStatus.SEALED, false));
		// Records whose effects are already gone need no state change and must not stop the branch.
		assertEquals(List.of(4L), RestorePlan.victims(nodes, 1L, ALICE, true));
	}

	@Test
	void anUnrestorableRecordIsABoundaryRatherThanBeingSkipped() {
		List<RestorePlan.Node> nodes = List.of(
				new RestorePlan.Node(1L, ALICE, ActivityStatus.SEALED, false),
				new RestorePlan.Node(2L, ALICE, ActivityStatus.SEALED, false),
				new RestorePlan.Node(3L, ALICE, ActivityStatus.NOT_ROLLBACKABLE, false));
		// Skipping the record would apply older snapshots on top of a change that stays in place.
		RestorePlan.Branch branch = RestorePlan.branch(nodes, 1L, ALICE, true, null);
		assertTrue(branch.blocked());
		assertEquals(3L, branch.boundary().id());
		assertTrue(branch.victims().isEmpty());
	}

	@Test
	void theHotkeyDoesNotRewindWalksButAnExplicitRestoreDoes() {
		List<RestorePlan.Node> nodes = List.of(
				new RestorePlan.Node(1L, ALICE, ActivityStatus.SEALED, false),
				new RestorePlan.Node(2L, ALICE, ActivityStatus.SEALED, true));
		assertTrue(RestorePlan.victims(nodes, 2L, ALICE, false).isEmpty(),
				"Z must never teleport the player instead of undoing the action");
		assertEquals(List.of(2L), RestorePlan.victims(nodes, 1L, ALICE, true),
				"an explicit restore reproduces the whole recorded state of the node");
	}

	@Test
	void anEmptyResultIsStable() {
		assertTrue(RestorePlan.victims(null, 1L, ALICE, true).isEmpty());
		assertTrue(RestorePlan.victims(List.of(), 1L, ALICE, true).isEmpty());
	}

	@Test
	void aBoundaryStopsTheBranchInsteadOfBeingSkipped() {
		List<RestorePlan.Node> nodes = new ArrayList<>(chain(ALICE, 1, 3));
		nodes.add(new RestorePlan.Node(4L, ALICE, ActivityStatus.NOT_ROLLBACKABLE, false));
		nodes.add(new RestorePlan.Node(5L, ALICE, ActivityStatus.SEALED, false));

		RestorePlan.Branch branch = RestorePlan.branch(nodes, 1L, ALICE, true, null);

		assertTrue(branch.blocked(), "a restore must not cross a record that can never be undone");
		assertEquals(4L, branch.boundary().id());
		assertTrue(branch.victims().isEmpty(), "nothing may be undone when the branch is blocked");
	}

	@Test
	void recordsNewerThanABoundaryCanStillBeUndone() {
		List<RestorePlan.Node> nodes = new ArrayList<>(chain(ALICE, 1, 3));
		nodes.add(new RestorePlan.Node(4L, ALICE, ActivityStatus.NOT_ROLLBACKABLE, false));
		nodes.add(new RestorePlan.Node(5L, ALICE, ActivityStatus.SEALED, false));

		RestorePlan.Branch branch = RestorePlan.branch(nodes, 4L, ALICE, true, null);

		assertFalse(branch.blocked(), "restoring to the state after the boundary is legal");
		assertEquals(List.of(5L), branch.victims());
	}

	@Test
	void anIncompleteRecordIsABoundaryToo() {
		List<RestorePlan.Node> nodes = new ArrayList<>(chain(ALICE, 1, 2));
		nodes.add(new RestorePlan.Node(3L, ALICE, ActivityStatus.INCOMPLETE, false));

		RestorePlan.Branch branch = RestorePlan.branch(nodes, 1L, ALICE, true, null);

		assertTrue(branch.blocked());
		assertEquals(3L, branch.boundary().id());
		assertEquals("ctrlz.reason.incomplete", branch.boundary().reasonKey());
	}

	@Test
	void unrecoverableContentIsABoundaryEvenWithoutTheStatus() {
		// History written by an older version has status SEALED but carries an unsupported change.
		List<RestorePlan.Node> nodes = new ArrayList<>(chain(ALICE, 1, 2));

		RestorePlan.Branch branch = RestorePlan.branch(nodes, 1L, ALICE, true, id -> id == 2L);

		assertTrue(branch.blocked());
		assertEquals(2L, branch.boundary().id());
		assertEquals("ctrlz.reason.unsupported", branch.boundary().reasonKey());
	}
}
