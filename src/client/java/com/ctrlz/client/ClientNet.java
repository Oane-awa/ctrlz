package com.ctrlz.client;

import com.ctrlz.config.CtrlZConfig;
import com.ctrlz.net.CtrlZPayload;
import com.ctrlz.net.Messages;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Client side state cache for the history and config screens.
 */
public final class ClientNet {
	private static Messages.HistoryDto history;
	private static Messages.DetailDto detail;
	private static CtrlZConfig config;
	private static Messages.RollbackResultDto lastResult;

	private ClientNet() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(CtrlZPayload.TYPE, (payload, context) -> context.client()
				.execute(() -> handle(payload)));
	}

	private static void handle(CtrlZPayload payload) {
		switch (payload.opcode()) {
			case Messages.S2C_HISTORY -> {
				history = Messages.GSON.fromJson(payload.data(), Messages.HistoryDto.class);
				refreshScreen();
			}
			case Messages.S2C_DETAIL -> {
				detail = Messages.GSON.fromJson(payload.data(), Messages.DetailDto.class);
				refreshScreen();
			}
			case Messages.S2C_CONFIG -> {
				Messages.ConfigDto dto = Messages.GSON.fromJson(payload.data(), Messages.ConfigDto.class);
				if (dto != null && dto.json() != null) {
					config = CtrlZConfig.fromJson(dto.json());
				}
				refreshScreen();
			}
			case Messages.S2C_ROLLBACK_RESULT -> {
				lastResult = Messages.GSON.fromJson(payload.data(), Messages.RollbackResultDto.class);
				refreshScreen();
			}
			case Messages.S2C_NOTICE -> {
				Messages.NoticeDto notice = Messages.GSON.fromJson(payload.data(), Messages.NoticeDto.class);
				if (notice != null) {
					lastResult = new Messages.RollbackResultDto(false, 0, "notice", notice.key());
				}
				refreshScreen();
			}
			default -> {
			}
		}
	}

	private static void refreshScreen() {
		Minecraft client = Minecraft.getInstance();
		if (client.screen instanceof Refreshable refreshable) {
			refreshable.onDataChanged();
		}
	}

	public static Messages.HistoryDto history() {
		return history;
	}

	public static Messages.DetailDto detail() {
		return detail;
	}

	public static CtrlZConfig config() {
		return config;
	}

	public static Messages.RollbackResultDto lastResult() {
		if (lastResult == null) {
			return null;
		}
		Messages.RollbackResultDto result = lastResult;
		lastResult = null;
		return result;
	}

	public static void requestHistory(int offset, Messages.QueryDto query) {
		Messages.QueryDto actual = query == null ? Messages.QueryDto.defaultQuery(offset) : query;
		send(Messages.C2S_REQUEST_HISTORY, Messages.GSON.toJson(actual));
	}

	public static void requestDetail(long activityId) {
		send(Messages.C2S_REQUEST_DETAIL, Messages.GSON.toJson(new Messages.DetailRequestDto(activityId)));
	}

	public static void requestRollback(long activityId) {
		send(Messages.C2S_REQUEST_ROLLBACK,
				Messages.GSON.toJson(new Messages.RollbackRequestDto(activityId, false)));
	}

	public static void rollbackLast() {
		send(Messages.C2S_ROLLBACK_LAST, Messages.GSON.toJson(new Messages.RollbackRequestDto(0L, true)));
	}

	public static void requestConfig() {
		send(Messages.C2S_REQUEST_CONFIG, "{}");
	}

	public static void saveConfig(CtrlZConfig value) {
		send(Messages.C2S_UPDATE_CONFIG, Messages.GSON.toJson(new Messages.ConfigDto(value.toJson())));
	}

	public static void send(int opcode, String json) {
		if (ClientPlayNetworking.canSend(CtrlZPayload.TYPE)) {
			ClientPlayNetworking.send(new CtrlZPayload(opcode, json == null ? "{}" : json));
		}
	}

	public interface Refreshable {
		void onDataChanged();
	}
}
