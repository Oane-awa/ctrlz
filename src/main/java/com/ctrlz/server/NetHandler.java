package com.ctrlz.server;

import com.ctrlz.net.CtrlZPayload;
import com.ctrlz.net.Messages;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server side packet handling. Every request is re-validated here; the client is never trusted for
 * permissions, activity ownership or conflict state.
 */
public final class NetHandler {
	private NetHandler() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(CtrlZPayload.TYPE, (payload, context) -> context.server()
				.execute(() -> handle(payload, context)));
	}

	private static void handle(CtrlZPayload payload, ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		try {
			switch (payload.opcode()) {
				case Messages.C2S_REQUEST_HISTORY -> {
					Messages.QueryDto query = Messages.GSON.fromJson(payload.data(), Messages.QueryDto.class);
					if (query == null) {
						query = Messages.QueryDto.defaultQuery(0);
					}
					send(context, Messages.S2C_HISTORY, HistoryService.history(player, query));
				}
				case Messages.C2S_REQUEST_DETAIL -> {
					Messages.DetailRequestDto request = Messages.GSON.fromJson(payload.data(),
							Messages.DetailRequestDto.class);
					Messages.DetailDto detail = request == null ? null : HistoryService.detail(request.activityId());
					if (detail != null) {
						send(context, Messages.S2C_DETAIL, detail);
					} else if (request != null) {
						// The row was truncated by a restore: say so instead of leaving a stale detail
						// on screen that no longer exists in the history.
						send(context, Messages.S2C_NOTICE,
								new Messages.NoticeDto("ctrlz.gui.detail_missing", Long.toString(request.activityId())));
					}
				}
				case Messages.C2S_REQUEST_ROLLBACK -> {
					Messages.RollbackRequestDto request = Messages.GSON.fromJson(payload.data(),
							Messages.RollbackRequestDto.class);
					reply(context, request == null
							? new RollbackService.Result(false, 0L, 0, 0, 0, false, "bad_request",
									"ctrlz.result.bad_request", "")
							: RollbackService.restoreToActivity(player, request.activityId()));
				}
				case Messages.C2S_ROLLBACK_LAST -> reply(context, RollbackService.restoreLast(player));
				case Messages.C2S_REQUEST_CONFIG -> send(context, Messages.S2C_CONFIG,
						new Messages.ConfigDto(configJson()));
				case Messages.C2S_UPDATE_CONFIG -> {
					CtrlZServer hub = CtrlZServer.get();
					if (hub == null) {
						return;
					}
					if (!Permissions.hasLevel(player, Math.max(3, hub.config().rollbackPermissionLevel))) {
						player.sendSystemMessage(Component.translatable("ctrlz.result.no_permission"));
						return;
					}
					Messages.ConfigDto dto = Messages.GSON.fromJson(payload.data(), Messages.ConfigDto.class);
					if (dto != null && dto.json() != null) {
						com.ctrlz.config.CtrlZConfig updated = com.ctrlz.config.CtrlZConfig.fromJson(dto.json());
						hub.applyConfig(updated);
						send(context, Messages.S2C_CONFIG, new Messages.ConfigDto(configJson()));
						player.sendSystemMessage(Component.translatable("ctrlz.notice.config_saved"));
					}
				}
				default -> {
				}
			}
		} catch (RuntimeException e) {
			send(context, Messages.S2C_NOTICE, new Messages.NoticeDto("ctrlz.notice.error", e.getMessage()));
		}
	}

	/**
	 * Sends the same result object for both entry points and shows it on the action bar.
	 *
	 * <p>The hotkey is pressed without any screen open, so the outcome has to be visible there; a GUI
	 * user sees the same text and additionally gets a refreshed list because the client re-requests
	 * history whenever a restore succeeded.
	 */
	private static void reply(ServerPlayNetworking.Context context, RollbackService.Result result) {
		send(context, Messages.S2C_ROLLBACK_RESULT, new Messages.RollbackResultDto(result.success(),
				result.targetActivityId(), result.activities(), result.restored(), result.skipped(),
				result.truncated(), result.conflict(), result.messageKey(), result.detail(), result.boundaryId(),
				result.compensationFailed()));
		ServerPlayer player = context.player();
		if (result.success()) {
			player.sendOverlayMessage(Component.translatable("ctrlz.notice.restored_to", result.activities(),
					result.restored()));
			return;
		}
		// A refusal must say what happened, not just "it failed": the key carries the category, the
		// detail names the record and the state that did not match.
		Component message = Component.translatable(result.messageKey());
		if (!result.detail().isEmpty()) {
			message = message.copy().append(Component.literal(" — " + shorten(result.detail(), 160)));
		}
		player.sendOverlayMessage(message);
	}

	private static String shorten(String text, int limit) {
		return text.length() <= limit ? text : text.substring(0, Math.max(0, limit - 1)) + "\u2026";
	}

	private static String configJson() {
		CtrlZServer hub = CtrlZServer.get();
		return hub == null ? "{}" : hub.config().toJson();
	}

	private static void send(ServerPlayNetworking.Context context, int opcode, Object body) {
		context.responseSender().sendPacket(new CtrlZPayload(opcode, Messages.GSON.toJson(body)));
	}
}
