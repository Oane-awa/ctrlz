package com.ctrlz.core.activity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encodes the activity lifecycle the rollback logic depends on: one independent action ends the one
 * before it, a walk ends when the player really stopped, and a placement plus its item use stay one
 * activity so the block and the consumed item can never end up in different records.
 */
class ActivitySessionTest {
	private final List<ActivityRecord> opened = new ArrayList<>();
	private final List<ActivityRecord> sealed = new ArrayList<>();
	private ActivitySession session;

	@BeforeEach
	void setUp() {
		AtomicLong ids = new AtomicLong();
		session = new ActivitySession(UUID.randomUUID(), "Steve", "minecraft:overworld", 10, 200, ids::incrementAndGet,
				new ActivitySession.Sink() {
					@Override
					public void opened(ActivityRecord record) {
						opened.add(record);
					}

					@Override
					public void checkpointed(ActivityRecord record) {
					}

					@Override
					public void sealed(ActivityRecord record) {
						sealed.add(record);
					}
				});
	}

	private static ActivitySession.Sample at(int tick, double x) {
		return new ActivitySession.Sample(tick, x, 64.0, 0.0, false, false, true);
	}

	@Test
	void newActionEndsTheWalkBeforeIt() {
		ActivityRecord walk = session.observeMovement(at(100, 0));
		session.observeMovement(at(101, 0.3));
		ActivityRecord mining = session.observeAction(ActivityKind.BREAK_BLOCK, at(102, 0.6));
		assertTrue(sealed.contains(walk), "placing or mining after walking ends the walk");
		assertSame(mining, session.action());
		assertNull(session.movement());
	}

	@Test
	void newMovementEndsTheActionBeforeIt() {
		ActivityRecord placing = session.observeAction(ActivityKind.PLACE_BLOCK, at(100, 0));
		session.observeMovement(at(102, 0.4));
		assertTrue(sealed.contains(placing), "walking right after placing ends the placement record");
		assertNotNull(session.movement());
		assertNull(session.action());
	}

	@Test
	void differentActionKindsNeverMerge() {
		ActivityRecord mining = session.observeAction(ActivityKind.BREAK_BLOCK, at(100, 0));
		ActivityRecord placing = session.observeAction(ActivityKind.PLACE_BLOCK, at(101, 0));
		assertNotSame(mining, placing);
		assertTrue(sealed.contains(mining));
	}

	@Test
	void placementAndItsItemUseStayOneActivity() {
		ActivityRecord placing = session.observeAction(ActivityKind.PLACE_BLOCK, at(100, 0));
		ActivityRecord use = session.observeAction(ActivityKind.USE_ITEM, at(100, 0));
		assertSame(placing, use, "one right click must produce exactly one activity");
		assertEquals(1, opened.size());
		assertEquals(ActivityKind.PLACE_BLOCK, session.action().kind());
	}

	@Test
	void repeatedBreaksInsideTheWindowMerge() {
		ActivityRecord first = session.observeAction(ActivityKind.BREAK_BLOCK, at(100, 0));
		ActivityRecord second = session.observeAction(ActivityKind.BREAK_BLOCK, at(120, 0));
		assertSame(first, second);
		assertEquals(1, opened.size());
	}

	@Test
	void walkEndsWhenThePlayerReallyStopped() {
		session.observeMovement(at(100, 0));
		session.observeMovement(at(101, 0.3));
		session.tick(105, 0.0);
		assertNotNull(session.movement(), "a pause shorter than the stop window keeps the walk open");
		session.tick(120, 0.0);
		assertNull(session.movement());
		assertEquals(1, sealed.size());
	}

	@Test
	void slidingOrKnockBackKeepsTheWalkOpen() {
		session.observeMovement(at(100, 0));
		session.observeMovement(at(101, 0.3));
		// No new displacement, but the player is still moving fast: the walk has not ended yet.
		session.tick(120, 0.02);
		assertNotNull(session.movement());
	}

	@Test
	void teleportSplitsTheWalkAndIsNotCountedAsDistance() {
		ActivityRecord before = session.observeMovement(at(100, 0));
		session.observeMovement(at(101, 0.2));
		ActivityRecord after = session.observeMovement(at(102, 500.0));
		assertNotSame(before, after);
		assertTrue(before.distanceBlocks() < 1.0, "the jump must not be added to the walked distance");
		assertEquals(1, sealed.size());
	}

	@Test
	void actionKeepsOwningConsequencesRightAfterItWasSealed() {
		ActivityRecord placing = session.observeAction(ActivityKind.PLACE_BLOCK, at(100, 0));
		session.observeMovement(at(101, 0.3));
		assertNull(session.action());
		// The inventory change that pays for the placement is only visible on the next tick.
		assertSame(placing, session.actionForConsequences(102, 3));
		assertNull(session.actionForConsequences(120, 3), "the grace period must not last forever");
	}

	@Test
	void walkHasNoActionSoInventoryChangesCannotAttachToIt() {
		session.observeMovement(at(100, 0));
		session.observeMovement(at(101, 0.3));
		assertNull(session.actionForConsequences(102, 3));
	}

	@Test
	void flushSealsEverythingThatIsOpen() {
		session.observeAction(ActivityKind.USE_ITEM, at(100, 0));
		session.flush(101);
		assertNull(session.action());
		assertEquals(1, sealed.size());
	}
}
