package com.ctrlz;

import com.ctrlz.command.UndoHistoryCommand;
import com.ctrlz.config.CtrlZConfig;
import com.ctrlz.engine.EngineRouter;
import com.ctrlz.net.CtrlZPayload;
import com.ctrlz.server.CtrlZServer;
import com.ctrlz.server.NetHandler;
import com.ctrlz.server.RecorderEvents;
import com.ctrlz.v2.V2Events;
import com.ctrlz.v2.V2UndoService;
import com.ctrlz.v2.net.UndoRequestPayload;
import com.ctrlz.v2.net.UndoResultPayload;
import com.ctrlz.v2.net.V2ServerNet;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Entry point. The engine is resolved exactly once, before anything is registered, and only that
 * engine's hooks, packet types and handlers are wired up.
 */
public final class CtrlZMod implements ModInitializer {
	public static final String MOD_ID = "ctrlz";
	public static final Logger LOGGER = LoggerFactory.getLogger("CtrlZ");

	private static Path configPath;

	@Override
	public void onInitialize() {
		configPath = FabricLoader.getInstance().getConfigDir().resolve("ctrlz.json");
		CtrlZConfig startupConfig = CtrlZConfig.load(configPath);
		EngineRouter.resolve(EngineRouter.Engine.parse(startupConfig.engine));
		LOGGER.info("CtrlZ engine source: {} (engine={})", configPath.toAbsolutePath(),
				EngineRouter.active().id());

		UndoHistoryCommand.register();

		if (!startupConfig.enabled) {
			LOGGER.info("CtrlZ is disabled in config/ctrlz.json; no recording hook is registered");
			return;
		}

		if (EngineRouter.isV2()) {
			registerV2(startupConfig);
		} else {
			registerLegacy(startupConfig);
		}

		LOGGER.info("CtrlZ ready (engine={})", EngineRouter.active().id());
	}

	/**
	 * The engine is read once, at startup. If the file changed afterwards we only say so: switching
	 * mid session would leave half the hooks on the old engine, which is exactly what the mutually
	 * exclusive switch exists to prevent.
	 */
	private static void checkEngineUnchanged() {
		EngineRouter.Engine configured = EngineRouter.Engine.parse(CtrlZConfig.load(configPath).engine);
		if (configured != EngineRouter.active()) {
			LOGGER.warn("CtrlZ engine in {} is now '{}' but '{}' is running; restart the game to switch",
					configPath.toAbsolutePath(), configured.id(), EngineRouter.active().id());
		}
	}

	private static void registerV2(CtrlZConfig config) {
		PayloadTypeRegistry.serverboundPlay().register(UndoRequestPayload.TYPE, UndoRequestPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(UndoResultPayload.TYPE, UndoResultPayload.CODEC);
		V2Events.register();
		V2ServerNet.register();
		V2UndoService.configure(config);

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			verifyMixinTargets();
			checkEngineUnchanged();
			V2UndoService.start(server);
			V2UndoService.logActiveEngine();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> V2UndoService.stop());
		ServerTickEvents.END_SERVER_TICK.register(server -> V2UndoService.tick());
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				V2UndoService.onPlayerLeft(handler.player.getUUID()));
	}

	private static void registerLegacy(CtrlZConfig config) {
		if (CtrlZConfig.mineTracerAuditForcedOff()) {
			LOGGER.warn("CtrlZ: the config file asked for useMineTracerAudit, but the MineTracer cross audit is "
					+ "deprecated and force-disabled until the legacy engine is deleted. It compares a whole "
					+ "activity against one 24 block radius and a two second window, so it reports false "
					+ "conflicts and freezes the undo chain.");
		}
		PayloadTypeRegistry.serverboundPlay().register(CtrlZPayload.TYPE, CtrlZPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CtrlZPayload.TYPE, CtrlZPayload.CODEC);

		RecorderEvents.register();
		NetHandler.register();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			verifyMixinTargets();
			checkEngineUnchanged();
			CtrlZServer.start(server);
			V2UndoService.logActiveEngine();
		});
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
	}

	/**
	 * Mixins are applied when their target class is first defined. A server without players never
	 * loads {@code ServerPlayer}, so a wrong injection point would stay hidden until somebody joins.
	 * Defining every target now (without running static initialisers) makes it fail at startup.
	 */
	private static void verifyMixinTargets() {
		String[] targets = {
				"net.minecraft.world.level.Level",
				"net.minecraft.server.level.ServerLevel",
				"net.minecraft.server.level.ServerPlayer",
				"net.minecraft.world.entity.player.Player",
				"net.minecraft.world.entity.LivingEntity",
				"net.minecraft.world.entity.item.ItemEntity",
				"net.minecraft.world.inventory.AbstractContainerMenu"
		};
		int verified = 0;
		for (String target : targets) {
			try {
				Class.forName(target, false, CtrlZMod.class.getClassLoader());
				verified++;
			} catch (ClassNotFoundException | LinkageError e) {
				LOGGER.error("CtrlZ mixin target {} could not be loaded", target, e);
			}
		}
		LOGGER.info("CtrlZ verified {}/{} mixin targets", verified, targets.length);
	}
}
