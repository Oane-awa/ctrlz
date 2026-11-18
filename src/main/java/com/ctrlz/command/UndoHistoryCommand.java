package com.ctrlz.command;

import com.ctrlz.engine.EngineRouter;
import com.ctrlz.history.Operation;
import com.ctrlz.history.Snapshot;
import com.ctrlz.v2.V2UndoService;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * {@code /undohistory} — the only history browser the v2 engine needs.
 *
 * <p>The first line always states which engine is answering. When the legacy engine is active the
 * command says so explicitly instead of silently reporting an empty v2 history, because "no records"
 * and "the other engine is recording" look identical otherwise.
 */
public final class UndoHistoryCommand {
	private UndoHistoryCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
				Commands.literal("undohistory")
						.executes(context -> list(context.getSource()))
						.then(Commands.literal("purge")
								.executes(context -> purgeHint(context.getSource()))
								.then(Commands.literal("confirm")
										.executes(context -> purge(context.getSource()))))
						.then(Commands.literal("dump")
								.then(Commands.argument("operationId", StringArgumentType.string())
										.executes(context -> dump(context.getSource(),
												StringArgumentType.getString(context, "operationId")))))));
	}

	private static int list(CommandSourceStack source) {
		String engine = EngineRouter.active().id();
		StringBuilder header = new StringBuilder("[ctrlz] engine=").append(engine);
		if (EngineRouter.isLegacy()) {
			header.append(" — v2 engine is NOT enabled; the counters below belong to the v2 store and stay empty");
		}
		header.append(" | entries=").append(V2UndoService.stack().count())
				.append(" bytes=").append(V2UndoService.stack().estimatedBytes())
				.append(" oldestPlayTick=").append(V2UndoService.stack().oldestPlayTick())
				.append(" playTick=").append(V2UndoService.playTick());
		Operation target = V2UndoService.stack().undoTarget(V2UndoService.playTick());
		if (target != null && target.blocked()) {
			header.append(" | undo chain BLOCKED at #").append(target.seq()).append(" (")
					.append(target.blockedStatus()).append(": ").append(target.blockedReason())
					.append(") — Z replays that failure; /undohistory purge confirm clears the stack");
		}
		source.sendSuccess(() -> Component.literal(header.toString()), false);

		List<Operation> operations = V2UndoService.stack().newestFirst();
		for (int index = 0; index < Math.min(operations.size(), 10); index++) {
			Operation operation = operations.get(index);
			String line = String.format("#%d %s %s %s blocks=%d entities=%d bytes=%d undone=%s blocked=%s",
					operation.seq(), operation.operationId().toString().substring(0, 8), operation.type(),
					operation.coverage(), operation.blockSnapshotCount(), operation.entitySnapshotCount(),
					operation.estimatedBytes(), operation.undone(), blockedLabel(operation));
			source.sendSuccess(() -> Component.literal(line), false);
		}
		if (operations.size() > 10) {
			source.sendSuccess(() -> Component.literal("... " + (operations.size() - 10) + " more"), false);
		}
		return operations.size();
	}

	private static int dump(CommandSourceStack source, String rawId) {
		UUID id;
		try {
			id = UUID.fromString(rawId);
		} catch (IllegalArgumentException e) {
			source.sendFailure(Component.literal("[ctrlz] not a uuid: " + rawId));
			return 0;
		}
		Operation operation = findById(id);
		if (operation == null) {
			source.sendFailure(Component.literal("[ctrlz] no operation " + rawId));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(String.format(
				"[ctrlz] op=%s type=%s coverage=%s world=%s gameMode=%s createdPlayTick=%d committedPlayTick=%d appliedSeq=%d",
				operation.operationId(), operation.type(), operation.coverage(), operation.worldKey(),
				operation.gameMode(), operation.createdPlayTick(), operation.committedPlayTick(),
				operation.appliedSeq())), false);
		for (Snapshot snapshot : operation.snapshots()) {
			String line = "  - " + snapshot.describe();
			source.sendSuccess(() -> Component.literal(line), false);
		}
		return 1;
	}

	private static Operation findById(UUID id) {
		for (Operation operation : V2UndoService.stack().newestFirst()) {
			if (operation.operationId().equals(id)) {
				return operation;
			}
		}
		return null;
	}

	private static String blockedLabel(Operation operation) {
		return operation.blocked() ? operation.blockedStatus() + "(" + operation.blockedReason() + ")" : "false";
	}

	/** C.4: clearing the history is the only way out of a blocked chain, so it needs a confirmation. */
	private static int purgeHint(CommandSourceStack source) {
		source.sendFailure(Component.literal("[ctrlz] purge clears the whole undo history and cannot be undone; "
				+ "run /undohistory purge confirm to proceed"));
		return 0;
	}

	private static int purge(CommandSourceStack source) {
		int before = V2UndoService.stack().count();
		V2UndoService.purge();
		source.sendSuccess(() -> Component.literal("[ctrlz] purged " + before + " operations"), true);
		return before;
	}
}
