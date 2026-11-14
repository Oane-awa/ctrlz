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

class ActivitySessionTest {
	private final List<ActivityRecord> opened = new ArrayList<>();
	private final List<ActivityRecord> sealed = new ArrayList<>();
	private ActivitySession session;

	@BeforeEach
	void setUp() {
		AtomicLong ids = new AtomicLong();
		session = new ActivitySession(UUID.randomUUID(), "Steve", "minecraft:overworld", 40, 200, ids::incrementAndGet,
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
	void movementNeverSealsAnOpenAction() {
		ActivityRecord mining = session.observeAction(ActivityKind.BREAK_BLOCK, at(100, 0));
		// The player keeps walking while the break is still being recorded.
		for (int tick = 101; tick <= 110; tick++) {
			session.observeMovement(at(tick, tick * 0.2));
		}
		assertSame(mining, session.action(), "walking must not close the mining activity");
		assertTrue(sealed.stream().noneMatch(record -> record.kind() == ActivityKind.BREAK_BLOCK));
		assertNotNull(session.movement());
	}

	@Test
	void movementAndActionAreTwoSeparateActivities() {
		session.observeMovement(at(100, 0));
		ActivityRecord action = session.observeAction(ActivityKind.PLACE_BLOCK, at(101, 0));
		assertNotSame(session.movement(), action);
		assertEquals(ActivityKind.MOVE, session.movement().kind());
		assertEquals(ActivityKind.PLACE_BLOCK, action.kind());
		assertSame(action, session.current(), "new consequences belong to the action, not to the walk");
	}

	@Test
	void changingDirectionKeepsOneMovementActivity() {
		ActivityRecord first = session.observeMovement(at(100, 0));
		for (int tick = 101; tick < 140; tick++) {
			session.observeMovement(new ActivitySession.Sample(tick, tick < 120 ? (tick - 100) * 0.2 : 4.0, 64.0,
					tick < 120 ? 0.0 : (tick - 120) * 0.2, true, false, true));
		}
		assertSame(first, session.movement());
		assertEquals(1, opened.size());
		assertTrue(first.distanceBlocks() > 7.0, "distance must include both legs of the walk");
	}

	@Test
	void idleMovementIsSealedAfterTheMergeGap() {
		session.observeMovement(at(100, 0));
		session.observeMovement(at(101, 0.2));
		session.tick(120);
		assertNotNull(session.movement(), "a short pause is still the same walk");
		session.tick(150);
		assertNull(session.movement());
		assertEquals(1, sealed.size());
		assertEquals(ActivityKind.MOVE, sealed.get(0).kind());
	}

	@Test
	void teleportStartsANewActivityAndIsNotCountedAsWalking() {
		ActivityRecord before = session.observeMovement(at(100, 0));
		session.observeMovement(at(101, 0.2));
		ActivityRecord after = session.observeMovement(at(102, 500.0));
		assertNotSame(before, after);
		assertTrue(before.distanceBlocks() < 1.0, "the jump must not be added to the walked distance");
		assertEquals(1, sealed.size());
	}

	@Test
	void sameActionWithinWindowMerges() {
		ActivityRecord first = session.observeAction(ActivityKind.BREAK_BLOCK, at(100, 0));
		ActivityRecord second = session.observeAction(ActivityKind.BREAK_BLOCK, at(130, 0));
		assertSame(first, second);
		assertEquals(1, opened.size());
	}

	@Test
	void anotherKindOfActionSealsThePreviousOne() {
		ActivityRecord mining = session.observeAction(ActivityKind.BREAK_BLOCK, at(100, 0));
		session.observeAction(ActivityKind.PLACE_BLOCK, at(110, 0));
		assertTrue(sealed.contains(mining));
	}

	@Test
	void flushSealsEverythingThatIsOpen() {
		session.observeMovement(at(100, 0));
		session.observeAction(ActivityKind.USE_ITEM, at(101, 0));
		session.flush(102);
		assertNull(session.movement());
		assertNull(session.action());
		assertEquals(2, sealed.size());
	}

	@Test
	void useItemClicksInQuickSuccessionMerge() {
		ActivityRecord first = session.observeAction(ActivityKind.USE_ITEM, at(100, 0));
		ActivityRecord second = session.observeAction(ActivityKind.USE_ITEM, at(102, 0));
		assertSame(first, second, "block use and item use of one click must be one activity");
	}
}
