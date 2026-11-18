package com.ctrlz.client.v2;

import com.ctrlz.history.UndoResult;
import com.ctrlz.v2.V2Messages;
import com.ctrlz.v2.net.UndoRequestPayload;
import com.ctrlz.v2.net.UndoResultPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Client side of the v2 key chain: debounce, send, show the result.
 *
 * <p>The client never predicts anything. It does not know whether an operation exists or whether it
 * will be refused; it only forwards the key press and renders the server's answer.
 */
public final class V2ClientNet {
	/** Mirrors {@code V2UndoService.RATE_LIMIT_MILLIS}; the server re-checks it anyway. */
	private static final long RATE_LIMIT_MILLIS = 200L;

	private static long lastRequestMillis;

	private V2ClientNet() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(UndoResultPayload.TYPE, (payload, context) ->
				context.client().execute(() -> show(payload)));
	}

	private static void show(UndoResultPayload payload) {
		Minecraft client = Minecraft.getInstance();
		if (client.gui == null) {
			return;
		}
		UndoResult.Status status;
		try {
			status = UndoResult.Status.valueOf(payload.status());
		} catch (IllegalArgumentException e) {
			status = UndoResult.Status.APPLY_FROZEN;
		}
		Component message = V2Messages.message(status, payload.blocked());
		// Always tag the engine: the whole point is that you can tell which engine answered.
		message = message.copy().append(Component.literal(" [" + payload.engine() + "]"));
		client.gui.setOverlayMessage(message, false);
	}

	/** Called from the Z key binding. */
	public static void requestUndo() {
		long now = System.currentTimeMillis();
		if (now - lastRequestMillis < RATE_LIMIT_MILLIS) {
			return;
		}
		lastRequestMillis = now;
		if (ClientPlayNetworking.canSend(UndoRequestPayload.TYPE)) {
			ClientPlayNetworking.send(new UndoRequestPayload());
		}
	}
}
