package com.ctrlz.core.storage;

import com.ctrlz.core.activity.ActivityKind;
import com.ctrlz.core.activity.ActivityRecord;
import com.ctrlz.core.activity.ActivityStatus;
import com.ctrlz.core.consequence.ConsequenceKind;
import com.ctrlz.core.consequence.ConsequenceOrigin;
import com.ctrlz.core.consequence.ConsequenceRecord;
import com.ctrlz.core.util.ByteIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityStoreTest {
	@TempDir
	Path tempDir;

	private ActivityRecord newActivity(long id, UUID player) {
		return new ActivityRecord(id, player, "Steve", ActivityKind.BREAK_BLOCK, "minecraft:overworld", 10, 1000L,
				ByteIO.packBlockPos(0, 64, 0));
	}

	@Test
	void historySurvivesRestart() throws IOException {
		UUID player = UUID.randomUUID();
		Path root = tempDir.resolve("store");
		try (ActivityStore store = new ActivityStore(root, ActivityStore.Limits.defaults())) {
			store.open();
			ActivityRecord activity = newActivity(store.allocateActivityId(), player);
			store.begin(activity);
			ConsequenceRecord consequence = new ConsequenceRecord(store.allocateConsequenceId(), activity.id(),
					ConsequenceKind.BLOCK_STATE, ConsequenceOrigin.DIRECT, 10, "minecraft:overworld",
					ByteIO.packBlockPos(1, 64, 0), "minecraft:stone", new byte[] {1, 2, 3}, new byte[] {4, 5}, 0, 0L,
					1000L, "minecraft:stone -> minecraft:air");
			store.appendConsequence(consequence);
			activity.setConsequenceCount(1);
			activity.setEnd(12, 2000L, ByteIO.packBlockPos(1, 64, 0));
			store.seal(activity);
			store.flush(true);
		}

		try (ActivityStore reopened = new ActivityStore(root, ActivityStore.Limits.defaults())) {
			reopened.open();
			assertEquals(1, reopened.activityCount());
			ActivityRecord loaded = reopened.recentActivities(player, 5).get(0);
			assertEquals(ActivityStatus.SEALED, loaded.status());
			assertEquals(1, loaded.consequenceCount());
			List<ConsequenceRecord> consequences = reopened.consequences(loaded.id());
			assertEquals(1, consequences.size());
			assertEquals(ConsequenceKind.BLOCK_STATE, consequences.get(0).kind());
			assertEquals("minecraft:stone", consequences.get(0).subject());
			assertEquals(3, consequences.get(0).before().length);
		}
	}

	@Test
	void unfinishedActivityIsMarkedIncomplete() throws IOException {
		UUID player = UUID.randomUUID();
		Path root = tempDir.resolve("crash");
		try (ActivityStore store = new ActivityStore(root, ActivityStore.Limits.defaults())) {
			store.open();
			ActivityRecord activity = newActivity(store.allocateActivityId(), player);
			store.begin(activity);
			store.flush(true);
		}
		try (ActivityStore reopened = new ActivityStore(root, ActivityStore.Limits.defaults())) {
			reopened.open();
			ActivityRecord loaded = reopened.recentActivities(player, 5).get(0);
			assertEquals(ActivityStatus.INCOMPLETE, loaded.status());
			assertEquals("ctrlz.reason.crash", loaded.statusReason());
		}
	}

	@Test
	void truncatedTailIsDroppedAndKeepsEarlierRecords() throws IOException {
		UUID player = UUID.randomUUID();
		Path root = tempDir.resolve("tail");
		try (ActivityStore store = new ActivityStore(root, ActivityStore.Limits.defaults())) {
			store.open();
			ActivityRecord activity = newActivity(store.allocateActivityId(), player);
			store.begin(activity);
			store.seal(activity);
			store.flush(true);
		}
		Path segment = root.resolve(ActivityStore.ACTIVITY_DIR).resolve("seg-000001.ctz");
		assertTrue(Files.exists(segment));
		Files.writeString(segment, "garbage-tail", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
		long sizeWithGarbage = Files.size(segment);

		try (ActivityStore reopened = new ActivityStore(root, ActivityStore.Limits.defaults())) {
			reopened.open();
			assertEquals(1, reopened.activityCount());
			assertEquals(ActivityStatus.SEALED, reopened.recentActivities(player, 5).get(0).status());
			assertTrue(Files.size(segment) < sizeWithGarbage, "the corrupted tail must be truncated away");
		}
	}

	@Test
	void unknownActivityReturnsNull() throws IOException {
		try (ActivityStore store = new ActivityStore(tempDir.resolve("empty"), ActivityStore.Limits.defaults())) {
			store.open();
			assertNull(store.byId(999L));
			assertTrue(store.consequences(999L).isEmpty());
		}
	}

	@Test
	void retentionKeepsHistoryInsideLimits() throws IOException {
		Path root = tempDir.resolve("retention");
		ActivityStore.Limits small = new ActivityStore.Limits(2, 0L, 512L, 200);
		UUID player = UUID.randomUUID();
		try (ActivityStore store = new ActivityStore(root, small)) {
			store.open();
			for (int i = 0; i < 8; i++) {
				ActivityRecord activity = newActivity(store.allocateActivityId(), player);
				store.begin(activity);
				store.seal(activity);
				store.flush(true);
			}
			store.close();
			ActivityStore reopened = new ActivityStore(root, small);
			reopened.open();
			assertTrue(reopened.activityCount() <= 2, "retention limit must be enforced after restart");
			assertNotNull(reopened.recentActivities(player, 1).get(0));
			reopened.close();
		}
	}
}
