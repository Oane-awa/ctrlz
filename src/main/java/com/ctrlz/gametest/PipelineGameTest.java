package com.ctrlz.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/**
 * Stage 1, step 0: prove the GameTest pipeline works before any feature relies on it.
 *
 * <p>Lives in the mod's main source set on purpose: Fabric's test locator only picks up annotated
 * methods from classes that belong to a mod, so tests placed in {@code src/test} are silently skipped.
 */
public final class PipelineGameTest {
	@GameTest(structure = "minecraft:empty", maxTicks = 60)
	public void pipelineBootsAndCanPlaceBlocks(GameTestHelper helper) {
		BlockPos relative = new BlockPos(1, 1, 1);
		helper.setBlock(relative, Blocks.STONE);
		helper.assertBlockPresent(Blocks.STONE, relative);
		helper.succeed();
	}
}
