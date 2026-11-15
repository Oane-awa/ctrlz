package com.ctrlz.v2;

import com.ctrlz.engine.EngineRouter;
import com.ctrlz.history.Coverage;
import com.ctrlz.history.OperationType;
import com.ctrlz.history.Snapshot;
import com.ctrlz.history.SnapshotKeys;
import com.ctrlz.history.Transaction;
import com.ctrlz.platform.SnapshotCapture;
import com.ctrlz.platform.SnapshotCodec;
import com.ctrlz.platform.TransactionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Capture entry points of the v2 engine.
 *
 * <p>Slice 0 handles exactly one scenario: breaking a plain block in survival. A transaction opens
 * before the block is removed, collects the block snapshot plus any item entity the break spawns,
 * and closes as soon as the vanilla break finished.
 */
public final class V2Recorder {
	/** One open transaction per player; the server thread owns this map. */
	private static final Map<UUID, Transaction> OPEN = new LinkedHashMap<>();

	private V2Recorder() {
	}

	public static boolean hasOpenTransaction() {
		return !OPEN.isEmpty();
	}

	public static void beginBreak(Player player, ServerLevel level, BlockPos pos) {
		if (!EngineRouter.isV2() || TransactionContext.isRestoring()) {
			return;
		}
		if (OPEN.containsKey(player.getUUID())) {
			return;
		}
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return;
		}
		Transaction transaction = Transaction.open(player.getUUID(), SnapshotCodec.dimensionId(level),
				OperationType.BREAK_BLOCK, gameMode(player), V2UndoService.playTick());
		Snapshot.Block snapshot = SnapshotCapture.block(level, pos);
		transaction.capturePrimary(SnapshotKeys.of(snapshot), snapshot);
		if (state.hasBlockEntity()) {
			// Slice 0 only restores the block itself; block entity contents arrive with slice 1.
			transaction.degrade(Coverage.PARTIAL);
		}
		OPEN.put(player.getUUID(), transaction);
		TransactionContext.open(transaction);
	}

	public static void endBreak(Player player, ServerLevel level, BlockPos pos) {
		Transaction transaction = OPEN.remove(player.getUUID());
		if (transaction == null) {
			return;
		}
		if (TransactionContext.current() == transaction) {
			TransactionContext.close();
		}
		for (Map.Entry<String, Snapshot> entry : transaction.primary().entrySet()) {
			if (!transaction.after().containsKey(entry.getKey())
					&& entry.getValue() instanceof Snapshot.Block block) {
				BlockPos captured = SnapshotCodec.unpack(block.packedPos());
				if (level.isLoaded(captured)) {
					transaction.recordAfter(entry.getKey(), SnapshotCapture.blockFingerprint(level, captured));
				}
			}
		}
		V2UndoService.commit(transaction);
	}

	/** Hooked from {@code Level#setBlock}: only records the post state of already captured targets. */
	public static void beforeSetBlock(Level level, BlockPos pos) {
		// Nothing to do: the break hook is the one that opens the transaction.
	}

	public static void afterSetBlock(Level level, BlockPos pos) {
		Transaction transaction = TransactionContext.current();
		if (transaction == null || !(level instanceof ServerLevel serverLevel)) {
			return;
		}
		String key = "block|" + SnapshotCodec.dimensionId(serverLevel) + "|" + SnapshotCodec.pack(pos);
		if (!transaction.primary().containsKey(key)) {
			return;
		}
		transaction.recordAfter(key, SnapshotCapture.blockFingerprint(serverLevel, pos));
	}

	/** Records the drops an operation produced, so undo can remove exactly those entities. */
	public static void afterAddEntity(ServerLevel level, Entity entity, boolean added) {
		Transaction transaction = TransactionContext.current();
		if (transaction == null || !added || TransactionContext.isRestoring()) {
			return;
		}
		if (!SnapshotCapture.isItemDrop(entity)) {
			return;
		}
		transaction.captureChained(SnapshotCapture.spawnedEntity(entity));
	}

	/** Seals transactions that never saw their closing event (cancelled break, timeout, logout). */
	public static void tick() {
		if (OPEN.isEmpty()) {
			return;
		}
		long now = V2UndoService.playTick();
		List<UUID> expired = new ArrayList<>();
		for (Map.Entry<UUID, Transaction> entry : OPEN.entrySet()) {
			if (entry.getValue().expired(now)) {
				expired.add(entry.getKey());
			}
		}
		for (UUID playerId : expired) {
			Transaction transaction = OPEN.remove(playerId);
			if (transaction == null) {
				continue;
			}
			if (TransactionContext.current() == transaction) {
				TransactionContext.close();
			}
			V2UndoService.commit(transaction);
		}
	}

	public static void onPlayerLeft(UUID playerId) {
		Transaction transaction = OPEN.remove(playerId);
		if (transaction == null) {
			return;
		}
		if (TransactionContext.current() == transaction) {
			TransactionContext.close();
		}
		V2UndoService.commit(transaction);
	}

	/** Drops every open transaction without committing it; used when the engine is disabled. */
	public static void reset() {
		OPEN.clear();
		TransactionContext.close();
	}

	public static String gameMode(Player player) {
		return player.gameMode().isCreative() ? "CREATIVE" : "SURVIVAL";
	}
}
