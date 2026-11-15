package com.ctrlz.v2.net;

import com.ctrlz.engine.EngineRouter;
import com.ctrlz.history.UndoResult;
import com.ctrlz.v2.V2Messages;
import com.ctrlz.v2.V2UndoService;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server side handler of the undo request. Registered only when the v2 engine is active, so no code
 * path pretends to handle a key it cannot serve.
 */
public final class V2ServerNet {
	private V2ServerNet() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(UndoRequestPayload.TYPE, (payload, context) ->
				context.server().execute(() -> handle(context)));
	}

	private static void handle(ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		UndoResult result = V2UndoService.undo(player);

		Component message = Component.translatable(V2Messages.messageKey(result.status()));
		if (V2UndoService.debugLogging()) {
			message = message.copy().append(" [" + EngineRouter.active().id() + "]");
		}
		player.sendOverlayMessage(message);

		context.responseSender().sendPacket(new UndoResultPayload(result.status().name(), result.restored(),
				result.detail(), EngineRouter.active().id()));
	}
}
