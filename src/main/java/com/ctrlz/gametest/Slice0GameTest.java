package com.ctrlz.gametest;

import com.ctrlz.engine.EngineRouter;
import com.ctrlz.history.UndoResult;
import com.ctrlz.v2.V2Recorder;
import com.ctrlz.v2.V2UndoService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Slice 0 acceptance: break a block in survival, undo it, undo again.
 *
 * <p>The tests drive the same server side entry points the Z packet handler calls, because a
 * GameTest has no client to press a key. Two cases on purpose: stone (the documented exit scenario,
 * restores the block and is idempotent) and dirt (drops itself even without a tool, so the removal of
 * the dropped item — the part that would otherwise duplicate items — is actually exercised).
 */
public final class Slice0GameTest {
	@GameTest(structure = "minecraft:empty", maxTicks = 200)
	public void breakingStoneCanBeUndoneAndIsIdempotent(GameTestHelper helper) {
		ServerLevel level = prepare(helper);
		BlockPos relative = new BlockPos(1, 1, 1);
		BlockPos absolute = helper.absolutePos(relative);
		helper.setBlock(relative, Blocks.STONE);

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		breakBlock(player, level, absolute, helper);

		helper.assertBlockNotPresent(Blocks.STONE, relative);
		helper.assertTrue(V2UndoService.stack().count() == 1,
				"expected exactly one committed operation, got " + V2UndoService.stack().count());

		UndoResult first = V2UndoService.undoForTest(player);
		helper.assertTrue(first.success(),
				"first undo failed with status " + first.status() + " (" + first.detail() + ")");
		helper.assertBlockPresent(Blocks.STONE, relative);

		UndoResult second = V2UndoService.undoForTest(player);
		helper.assertTrue(second.status() == UndoResult.Status.NO_HISTORY,
				"second undo must be a no-op, was " + second.status());
		helper.assertBlockPresent(Blocks.STONE, relative);

		helper.succeed();
	}

	@GameTest(structure = "minecraft:empty", maxTicks = 200)
	public void undoingABreakRemovesTheDroppedItem(GameTestHelper helper) {
		ServerLevel level = prepare(helper);
		BlockPos relative = new BlockPos(2, 1, 1);
		BlockPos absolute = helper.absolutePos(relative);
		helper.setBlock(relative, Blocks.DIRT);

		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		breakBlock(player, level, absolute, helper);

		helper.assertItemEntityCountIs(Items.DIRT, relative, 2.0, 1);

		UndoResult result = V2UndoService.undoForTest(player);
		helper.assertTrue(result.success(), "undo failed with status " + result.status());
		helper.assertBlockPresent(Blocks.DIRT, relative);
		helper.assertItemEntityNotPresent(Items.DIRT, relative, 2.0);
		helper.assertItemEntityCountIs(Items.DIRT, relative, 2.0, 0);

		helper.succeed();
	}

	private static ServerLevel prepare(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		EngineRouter.resetForTest(EngineRouter.Engine.V2);
		V2UndoService.start(level.getServer());
		V2UndoService.purge();
		return level;
	}

	/** Exactly what a real break does: open transaction, remove the block with drops, close it. */
	private static void breakBlock(Player player, ServerLevel level, BlockPos absolute, GameTestHelper helper) {
		V2Recorder.beginBreak(player, level, absolute);
		boolean removed = level.destroyBlock(absolute, true);
		helper.assertTrue(removed, "the block could not be removed at " + absolute);
		V2Recorder.endBreak(player, level, absolute);
	}
}
