package com.ctrlz.v2;

import com.ctrlz.engine.EngineRouter;
import com.ctrlz.history.ConservationAudit;
import com.ctrlz.history.HistoryStack;
import com.ctrlz.history.Operation;
import com.ctrlz.history.Transaction;
import com.ctrlz.history.UndoResult;
import com.ctrlz.persistence.HistoryState;
import com.ctrlz.platform.PlayTickClock;
import com.ctrlz.platform.TransactionContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates commit and undo for the v2 engine.
 *
 * <p>Everything here runs on the server thread. The undo is two phase: a dry run that can only say
 * "no", then an apply that reverts itself if it throws, followed by a conservation audit that still
 * refuses the result if the item totals differ from what the plan promised.
 *
 * <p>Any refusal freezes the record ({@link Operation#block}). The stack is strictly LIFO, so a
 * frozen record stops the chain: later key presses replay the cached answer and never undo an older
 * record behind its back. Because recovery from {@code blocked} is not offered in v1, the only way
 * out is {@code /undohistory purge confirm}.
 */
public final class V2UndoService {
	public static final long RATE_LIMIT_MILLIS = 200L;

	private static final Logger LOGGER = LoggerFactory.getLogger("CtrlZ");
	private static final HistoryStack STACK = new HistoryStack();
	private static final PlayTickClock CLOCK = new PlayTickClock();
	private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();

	private static MinecraftServer server;
	private static long seq;
	private static boolean undoing;
	private static boolean debugLogging;

	private V2UndoService() {
	}

	public static void start(MinecraftServer minecraftServer) {
		server = minecraftServer;
		undoing = false;
		LAST_REQUEST.clear();
		logLegacyNamespace(minecraftServer);
	}

	/**
	 * A.3.3: the two engines never read each other's data. The legacy engine keeps a journal under
	 * {@code <world>/ctrlz/}; the v2 engine keeps {@code ctrlz:history} inside {@code SavedData}.
	 * Saying so at world load makes the isolation observable instead of a claim.
	 */
	private static void logLegacyNamespace(MinecraftServer minecraftServer) {
		try {
			Path legacy = minecraftServer.getWorldPath(LevelResource.ROOT).resolve("ctrlz");
			if (Files.isDirectory(legacy)) {
				LOGGER.info("CtrlZ v2 uses SavedData key 'ctrlz:history'; the legacy journal at {} is "
						+ "ignored and never read", legacy);
			} else {
				LOGGER.info("CtrlZ v2 uses SavedData key 'ctrlz:history'; no legacy journal present");
			}
		} catch (RuntimeException e) {
			LOGGER.debug("CtrlZ could not inspect the legacy journal directory", e);
		}
	}

	/** Remembers the user's debug preference so feedback can carry the engine tag. */
	public static void configure(com.ctrlz.config.CtrlZConfig config) {
		debugLogging = config != null && config.debugLogging;
	}

	public static boolean debugLogging() {
		return debugLogging;
	}

	public static void stop() {
		server = null;
		undoing = false;
		LAST_REQUEST.clear();
		V2Recorder.reset();
	}

	public static MinecraftServer server() {
		return server;
	}

	public static long playTick() {
		return CLOCK.now();
	}

	public static HistoryStack stack() {
		return STACK;
	}

	public static long nextSeq() {
		return ++seq;
	}

	/** Advances play time and seals transactions that never closed. */
	public static void tick() {
		if (server == null) {
			return;
		}
		CLOCK.tick();
		V2Recorder.tick();
	}

	public static void commit(Transaction transaction) {
		if (server == null) {
			return;
		}
		Operation operation = transaction.seal(CLOCK.now(), nextSeq());
		if (operation == null) {
			LOGGER.debug("CtrlZ dropped an operation with no restorable snapshot ({} captures)",
					transaction.snapshotCount());
			return;
		}
		STACK.add(operation, CLOCK.now());
		LOGGER.info("CtrlZ commit op={} type={} coverage={} blocks={} entities={} bytes={} history={}",
				operation.operationId(), operation.type(), operation.coverage(), operation.blockSnapshotCount(),
				operation.entitySnapshotCount(), operation.estimatedBytes(), STACK.count());
	}

	/** The one entry point for "player pressed Z". */
	public static UndoResult undo(Player player) {
		return undo(player, false);
	}

	private static UndoResult undo(Player player, boolean skipRateLimit) {
		MinecraftServer minecraftServer = server;
		if (!EngineRouter.isV2() || minecraftServer == null) {
			return UndoResult.of(UndoResult.Status.DISABLED);
		}
		if (!minecraftServer.isSameThread()) {
			LOGGER.warn("CtrlZ undo requested off the server thread; refusing");
			return UndoResult.of(UndoResult.Status.NOT_SERVER_THREAD);
		}
		long nowMillis = System.currentTimeMillis();
		if (!skipRateLimit) {
			Long last = LAST_REQUEST.get(player.getUUID());
			if (last != null && nowMillis - last < RATE_LIMIT_MILLIS) {
				return UndoResult.of(UndoResult.Status.BUSY, "rate limit");
			}
			LAST_REQUEST.put(player.getUUID(), nowMillis);
		}
		if (undoing) {
			return UndoResult.of(UndoResult.Status.BUSY, "already undoing");
		}

		Operation operation = STACK.undoTarget(CLOCK.now());
		if (operation == null) {
			return UndoResult.of(UndoResult.Status.NO_HISTORY);
		}
		if (operation.blocked()) {
			// C.2.2: replay the cached answer. Re-running the dry run would not change the outcome
			// and could even report a different reason for the same frozen record.
			LOGGER.info("CtrlZ undo replay op={} blocked={} reason={}", operation.operationId(),
					operation.blockedStatus(), operation.blockedReason());
			return operation.blockedResult();
		}

		ConservationAuditImpl audit = new ConservationAuditImpl(minecraftServer);
		UndoPlannerImpl planner = new UndoPlannerImpl(minecraftServer, audit);
		undoing = true;
		TransactionContext.beginRestore();
		try {
			UndoResult.Plan plan = planner.plan(operation);
			int restored;
			try {
				restored = RestoreApplier.apply(plan.steps(), minecraftServer);
			} catch (RuntimeException e) {
				LOGGER.error("CtrlZ undo failed while applying op={}", operation.operationId(), e);
				String detail = String.valueOf(e.getMessage());
				operation.block(UndoResult.Status.APPLY_REVERTED, detail);
				return UndoResult.blocked(UndoResult.Status.APPLY_REVERTED, detail);
			}

			Map<String, Integer> expected = audit.expectedAfter(plan.auditBefore(), plan.steps());
			Map<String, Integer> after = audit.counts(operation);
			if (!ConservationAudit.same(expected, after)) {
				String difference = ConservationAudit.describeDifference(expected, after);
				LOGGER.error("CtrlZ conservation audit failed op={} difference=[{}]", operation.operationId(),
						difference);
				RestoreApplier.revert(plan.steps(), restored, minecraftServer);
				operation.block(UndoResult.Status.AUDIT_FAILED, difference);
				return UndoResult.blocked(UndoResult.Status.AUDIT_FAILED, difference);
			}

			operation.markUndone();
			LOGGER.info("CtrlZ undo ok op={} type={} coverage={} steps={} appliedSeq={}",
					operation.operationId(), operation.type(), operation.coverage(), restored,
					operation.appliedSeq());
			return new UndoResult(UndoResult.Status.OK, restored, "");
		} catch (UndoResult.PlanException e) {
			// C.2.1: a dry run refusal freezes the record; the chain stops at this point.
			LOGGER.warn("CtrlZ undo refused op={} status={} reason={} (record blocked, chain stops)",
					operation.operationId(), e.status(), e.getMessage());
			operation.block(e.status(), e.getMessage());
			return UndoResult.blocked(e.status(), e.getMessage());
		} catch (RuntimeException e) {
			LOGGER.error("CtrlZ undo crashed op={}", operation.operationId(), e);
			String detail = String.valueOf(e.getMessage());
			operation.block(UndoResult.Status.APPLY_FROZEN, detail);
			return UndoResult.blocked(UndoResult.Status.APPLY_FROZEN, detail);
		} finally {
			TransactionContext.endRestore();
			undoing = false;
		}
	}

	/** Same code path as the key press, minus the 200 ms debounce a human cannot beat anyway. */
	public static UndoResult undoForTest(Player player) {
		return undo(player, true);
	}

	public static void onPlayerLeft(UUID playerId) {
		LAST_REQUEST.remove(playerId);
		V2Recorder.onPlayerLeft(playerId);
	}

	/** Prints the engine that is actually handling keys right now. */
	public static void logActiveEngine() {
		if (EngineRouter.isV2()) {
			LOGGER.info("[ctrlz] Active undo engine: v2 (schemaVersion={}, history={} entries)",
					HistoryState.SCHEMA_VERSION, STACK.count());
		} else {
			LOGGER.info("[ctrlz] Active undo engine: legacy (journal based, v2 history disabled)");
		}
	}

	public static void purge() {
		STACK.purge();
	}
}
