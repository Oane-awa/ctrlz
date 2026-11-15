package com.ctrlz.v2;

import com.ctrlz.history.Snapshot;
import com.ctrlz.history.UndoResult;
import com.ctrlz.platform.SnapshotCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;

import java.util.List;

/**
 * Applies a planned undo.
 *
 * <p>Order matters: blocks are restored first because that step is reversible (the previous state is
 * part of the plan), and irreversible entity removals come last. If a block restore throws, the
 * applied prefix is written back, so the world ends up unchanged instead of half undone.
 */
public final class RestoreApplier {
	private RestoreApplier() {
	}

	public static int apply(List<UndoResult.Plan.Step> steps, MinecraftServer server) {
		int applied = 0;
		try {
			for (UndoResult.Plan.Step step : steps) {
				applyStep(step, server);
				applied++;
			}
		} catch (RuntimeException e) {
			revert(steps, applied, server);
			throw e;
		}
		return applied;
	}

	/** Writes the pre-undo state back for the steps that were already applied. */
	public static void revert(List<UndoResult.Plan.Step> steps, int applied, MinecraftServer server) {
		for (int index = applied - 1; index >= 0; index--) {
			UndoResult.Plan.Step step = steps.get(index);
			try {
				if (step.snapshot() instanceof Snapshot.Block block
						&& step.previousValue() instanceof BlockState previous) {
					ServerLevel level = UndoPlannerImpl.levelOf(server, block.dimension());
					if (level != null) {
						level.setBlock(SnapshotCodec.unpack(block.packedPos()), previous, Block.UPDATE_ALL);
					}
				}
				// Entity removals are planned last and never reached by a failing block restore, so
				// there is nothing to write back for them within slice 0.
			} catch (RuntimeException ignored) {
				// Best effort: the caller logs the frozen state.
			}
		}
	}

	private static void applyStep(UndoResult.Plan.Step step, MinecraftServer server) {
		if (step.snapshot() instanceof Snapshot.Block block) {
			ServerLevel level = UndoPlannerImpl.levelOf(server, block.dimension());
			if (level == null) {
				throw new IllegalStateException("dimension " + block.dimension() + " is gone");
			}
			BlockPos pos = SnapshotCodec.unpack(block.packedPos());
			BlockState target = SnapshotCodec.decodeBlockState(block.blockState());
			if (target == null) {
				throw new IllegalStateException("unknown block state " + block.blockState());
			}
			level.setBlock(pos, target, Block.UPDATE_ALL);
			restoreBlockEntity(level, pos, block.blockEntityNbt());
		} else if (step.snapshot() instanceof Snapshot.Entity entity && !entity.existedBefore()) {
			if (step.previousValue() instanceof Entity live && !live.isRemoved()) {
				live.discard();
			}
		}
	}

	private static void restoreBlockEntity(ServerLevel level, BlockPos pos, byte[] data) {
		if (data == null || data.length == 0) {
			return;
		}
		CompoundTag tag = SnapshotCodec.readTag(data);
		if (tag == null) {
			return;
		}
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (blockEntity == null) {
			return;
		}
		blockEntity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
		blockEntity.setChanged();
	}
}
