package com.ctrlz;

import com.ctrlz.net.CtrlZPayload;
import com.ctrlz.server.CtrlZServer;
import com.ctrlz.server.NetHandler;
import com.ctrlz.server.RecorderEvents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point: registers the packet types, the server lifecycle hooks, the recording events and the
 * authoritative server core.
 */
public final class CtrlZMod implements ModInitializer {
	public static final String MOD_ID = "ctrlz";
	public static final Logger LOGGER = LoggerFactory.getLogger("CtrlZ");

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(CtrlZPayload.TYPE, CtrlZPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CtrlZPayload.TYPE, CtrlZPayload.CODEC);

		RecorderEvents.register();
		NetHandler.register();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> CtrlZServer.start(server));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> CtrlZServer.stop());
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			CtrlZServer hub = CtrlZServer.get();
			if (hub != null) {
				hub.tick();
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			CtrlZServer hub = CtrlZServer.get();
			if (hub != null) {
				hub.onPlayerLeft(handler.player.getUUID());
			}
		});

		LOGGER.info("CtrlZ activity recorder ready");
	}
}

