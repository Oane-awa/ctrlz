package com.ctrlz.gametest;

import com.ctrlz.core.activity.ActivityKind;
import com.ctrlz.core.activity.ActivityRecord;
import com.ctrlz.core.activity.ActivityStatus;
import com.ctrlz.core.consequence.ConsequenceKind;
import com.ctrlz.core.consequence.ConsequenceOrigin;
import com.ctrlz.core.consequence.ConsequenceRecord;
import com.ctrlz.core.storage.ActivityStore;
import com.ctrlz.core.util.ByteIO;
import com.ctrlz.server.CtrlZServer;
import com.ctrlz.server.RollbackService;
import com.ctrlz.server.Snapshots;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;

/**
 * End to end games tests of the history boundary and the multi operation preflight (AUDIT-003/004).
 *
 * <p>The tests drive the real {@link RollbackService} against the real world and the real store, with
 * history written directly into the store (the recording hooks are already covered elsewhere). That
 * is exactly the layer where the two defects lived: which records a restore may cross, and how a
 * chain of records that touch the same object is validated before anything is written.
 */
public final class RollbackBoundaryGameTest {
	@GameTest(structure = "minecraft:empty", maxTicks = 200)
	public void theHotkeyStopsAtABoundaryAndNeverPoisonsOlderRecords(GameTestHelper helper) {
		ServerPlayer player = mockPlayer(helper);
		BlockPos older = helper.absolutePos(new BlockPos(1, 1, 1));
		BlockPos boundary = helper.absolutePos(new BlockPos(2, 1, 1));
		BlockPos newer = helper.absolutePos(new BlockPos(3, 1, 1));

		long olderId = record(helper, player, ActivityKind.BREAK_BLOCK, older, Blocks.DIRT, Blocks.AIR,
				ActivityStatus.SEALED, "");
		long boundaryId = record(helper, player, ActivityKind.USE_ITEM, boundary, Blocks.STONE, Blocks.AIR,
				ActivityStatus.NOT_ROLLBACKABLE, "ctrlz.reason.unsupported");
		long newerId = record(helper, player, ActivityKind.BREAK_BLOCK, newer, Blocks.DIRT, Blocks.AIR,
				ActivityStatus.SEALED, "");

		// 1st Z: the newest restorable record is undone.
		RollbackService.Result first = restoreLast(player);
		helper.assertTrue(first.success(), "the newest record must be undone, was " + first.conflict());
		helper.assertBlockPresent(Blocks.DIRT, new BlockPos(3, 1, 1));

		// 2nd Z: the boundary stops the chain, nothing else is touched.
		RollbackService.Result second = restoreLast(player);
		helper.assertTrue(!second.success(), "the undo must stop at the boundary");
		helper.assertTrue("BOUNDARY".equals(second.conflict()), "expected BOUNDARY, was " + second.conflict());
		helper.assertTrue(second.boundaryId() == boundaryId,
				"the reported boundary must be the blocked record");
		helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(2, 1, 1));
		helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(1, 1, 1));
		assertStatus(helper, olderId, ActivityStatus.SEALED, "an older record must not be poisoned");
		assertStatus(helper, boundaryId, ActivityStatus.NOT_ROLLBACKABLE, "the boundary must keep its status");

		// 3rd Z: still parked at the boundary, still nothing changed.
		RollbackService.Result third = restoreLast(player);
		helper.assertTrue("BOUNDARY".equals(third.conflict()), "the chain must stay at the boundary");
		helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(1, 1, 1));
		helper.assertTrue(CtrlZServer.get().store().byId(olderId) != null,
				"the blocked restore must not delete any history");
		helper.assertTrue(CtrlZServer.get().store().byId(newerId) == null,
				"the record that was undone is gone, as before");
		helper.succeed();
	}

	@GameTest(structure = "minecraft:empty", maxTicks = 200)
	public void crossingABoundaryIsRefusedButRestoringAfterItIsAllowed(GameTestHelper helper) {
		ServerPlayer player = mockPlayer(helper);
		BlockPos older = helper.absolutePos(new BlockPos(1, 1, 1));
		BlockPos boundary = helper.absolutePos(new BlockPos(2, 1, 1));
		BlockPos newer = helper.absolutePos(new BlockPos(3, 1, 1));

		long olderId = record(helper, player, ActivityKind.BREAK_BLOCK, older, Blocks.DIRT, Blocks.AIR,
				ActivityStatus.SEALED, "");
		long boundaryId = record(helper, player, ActivityKind.USE_ITEM, boundary, Blocks.STONE, Blocks.AIR,
				ActivityStatus.NOT_ROLLBACKABLE, "ctrlz.reason.unsupported");
		record(helper, player, ActivityKind.BREAK_BLOCK, newer, Blocks.DIRT, Blocks.AIR, ActivityStatus.SEALED, "");

		// Restoring behind the boundary must be refused as a whole.
		RollbackService.Result refused = restoreTo(player, olderId);
		helper.assertTrue(!refused.success(), "crossing a boundary must fail");
		helper.assertTrue("BOUNDARY".equals(refused.conflict()), "expected BOUNDARY, was " + refused.conflict());
		helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(3, 1, 1));
		helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(1, 1, 1));

		// Restoring to the state right after the boundary is fine.
		RollbackService.Result allowed = restoreTo(player, boundaryId);
		helper.assertTrue(allowed.success(), "restoring after the boundary must work, was " + allowed.conflict());
		helper.assertBlockPresent(Blocks.DIRT, new BlockPos(3, 1, 1));
		helper.succeed();
	}

	@GameTest(structure = "minecraft:empty", maxTicks = 200)
	public void threeConsecutiveChangesOfOneBlockRestoreCompletely(GameTestHelper helper) {
		ServerPlayer player = mockPlayer(helper);
		BlockPos pos = helper.absolutePos(new BlockPos(5, 1, 1));

		long first = record(helper, player, ActivityKind.PLACE_BLOCK, pos, Blocks.DIRT, Blocks.STONE,
				ActivityStatus.SEALED, "");
		record(helper, player, ActivityKind.PLACE_BLOCK, pos, Blocks.STONE, Blocks.GOLD_BLOCK, ActivityStatus.SEALED,
				"");
		record(helper, player, ActivityKind.PLACE_BLOCK, pos, Blocks.GOLD_BLOCK, Blocks.AIR, ActivityStatus.SEALED,
				"");
		helper.assertBlockNotPresent(Blocks.GOLD_BLOCK, new BlockPos(5, 1, 1));

		// Restore to the state after the first record: stone, not a conflict about "air".
		RollbackService.Result restored = restoreTo(player, first);
		helper.assertTrue(restored.success(),
				"a normal sequence on one block must not be reported as a conflict, was " + restored.conflict()
						+ " (" + restored.detail() + ")");
		helper.assertBlockPresent(Blocks.STONE, new BlockPos(5, 1, 1));

		// And the hotkey can then undo the oldest one as well.
		RollbackService.Result undone = restoreLast(player);
		helper.assertTrue(undone.success(), "the remaining record must be undoable, was " + undone.conflict());
		helper.assertBlockPresent(Blocks.DIRT, new BlockPos(5, 1, 1));
		helper.succeed();
	}

	@GameTest(structure = "minecraft:empty", maxTicks = 200)
	public void aForeignChangeRefusesTheRestoreAndKeepsTheHistory(GameTestHelper helper) {
		ServerPlayer player = mockPlayer(helper);
		BlockPos pos = helper.absolutePos(new BlockPos(7, 1, 1));

		long olderId = record(helper, player, ActivityKind.BREAK_BLOCK, pos, Blocks.DIRT, Blocks.AIR,
				ActivityStatus.SEALED, "");
		long newerId = record(helper, player, ActivityKind.PLACE_BLOCK, pos, Blocks.AIR, Blocks.STONE,
				ActivityStatus.SEALED, "");
		// Somebody else rebuilt that spot after the recording.
		helper.getLevel().setBlock(pos, Blocks.BRICKS.defaultBlockState(), 3);

		RollbackService.Result refused = restoreLast(player);
		helper.assertTrue(!refused.success(), "a foreign change must refuse the restore");
		helper.assertTrue("STATE_MISMATCH".equals(refused.conflict()),
				"expected STATE_MISMATCH, was " + refused.conflict());
		helper.assertBlockPresent(Blocks.BRICKS, new BlockPos(7, 1, 1));

		RollbackService.Result again = restoreLast(player);
		helper.assertTrue("STATE_MISMATCH".equals(again.conflict()), "a retry must report the same conflict");
		assertStatus(helper, olderId, ActivityStatus.SEALED, "a failed restore must not change an older record");
		assertStatus(helper, newerId, ActivityStatus.SEALED, "a failed restore must not change the record");
		helper.succeed();
	}

	// ------------------------------------------------------------------ fixtures

	/**
	 * Writes one recorded block change straight into the store and applies its "after" state, so a
	 * test can describe a history without going through the recording hooks.
	 */
	private static long record(GameTestHelper helper, ServerPlayer player, ActivityKind kind, BlockPos pos,
			Block before, Block after, ActivityStatus status, String reason) {
		CtrlZServer hub = hub(helper);
		ActivityStore store = hub.store();
		String dimension = helper.getLevel().dimension().identifier().toString();
		long packed = ByteIO.packBlockPos(pos.getX(), pos.getY(), pos.getZ());
		int tick = hub.currentTick();
		BlockState beforeState = before.defaultBlockState();
		BlockState afterState = after.defaultBlockState();
		String beforeText = Snapshots.encodeBlockState(beforeState);
		String afterText = Snapshots.encodeBlockState(afterState);
		ActivityRecord activity = new ActivityRecord(store.allocateActivityId(), player.getUUID(), "Tester", kind,
				dimension, tick, System.currentTimeMillis(), packed);
		if (status != ActivityStatus.SEALED) {
			activity.setStatus(status, reason);
		}
		ConsequenceRecord consequence = new ConsequenceRecord(store.allocateConsequenceId(), activity.id(),
				ConsequenceKind.BLOCK_STATE, ConsequenceOrigin.DIRECT, tick, dimension, packed, beforeText,
				beforeText.getBytes(StandardCharsets.UTF_8), afterText.getBytes(StandardCharsets.UTF_8), 0, 0L,
				System.currentTimeMillis(), beforeText + " -> " + afterText);
		activity.setConsequenceCount(1);
		try {
			store.begin(activity);
			store.appendConsequence(consequence);
			store.seal(activity);
			store.flush(false);
		} catch (java.io.IOException e) {
			throw new IllegalStateException("the test store could not be written", e);
		}
		helper.getLevel().setBlock(pos, afterState, 3);
		return activity.id();
	}

	/**
	 * The restore entry points need the server side player; the game test helper can hand one out
	 * directly, which is also what keeps this test free of any client side plumbing.
	 */
	private static ServerPlayer mockPlayer(GameTestHelper helper) {
		return helper.makeMockServerPlayerInLevel();
	}

	private static CtrlZServer hub(GameTestHelper helper) {
		CtrlZServer existing = CtrlZServer.get();
		return existing != null ? existing : CtrlZServer.start(helper.getLevel().getServer());
	}

	private static RollbackService.Result restoreLast(ServerPlayer player) {
		CtrlZServer hub = CtrlZServer.get();
		if (hub != null) {
			// A test presses Z faster than the one second cooldown a player can not beat anyway.
			hub.lastRollbackMillis().remove(player.getUUID());
		}
		return RollbackService.restoreLast(player);
	}

	private static RollbackService.Result restoreTo(ServerPlayer player, long activityId) {
		CtrlZServer hub = CtrlZServer.get();
		if (hub != null) {
			hub.lastRollbackMillis().remove(player.getUUID());
		}
		return RollbackService.restoreToActivity(player, activityId);
	}

	private static void assertStatus(GameTestHelper helper, long id, ActivityStatus expected, String message) {
		ActivityRecord record = CtrlZServer.get().store().byId(id);
		helper.assertTrue(record != null, message + " (record " + id + " is gone)");
		helper.assertTrue(record.status() == expected,
				message + " (expected " + expected + ", was " + record.status() + ")");
	}
}
