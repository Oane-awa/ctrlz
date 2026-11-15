package com.ctrlz.client;

import com.ctrlz.client.gui.ConfigScreen;
import com.ctrlz.client.gui.HistoryScreen;
import com.ctrlz.client.v2.V2ClientNet;
import com.ctrlz.config.CtrlZConfig;
import com.ctrlz.engine.EngineRouter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

/**
 * Client entry point: key bindings, the screens and the single place where the Z key is dispatched.
 *
 * <p>Keys: G opens the activity history, Z undoes the newest valid operation, K opens the
 * configuration screen. The Z key asks {@link EngineRouter} which engine is active and forwards to
 * exactly one of them; it never tries both.
 */
public final class CtrlZClient implements ClientModInitializer {
	public static KeyMapping openHistory;
	public static KeyMapping rollbackLast;
	public static KeyMapping openConfig;

	@Override
	public void onInitializeClient() {
		Path configPath = FabricLoader.getInstance().getConfigDir().resolve("ctrlz.json");
		CtrlZConfig config = CtrlZConfig.load(configPath);
		EngineRouter.resolve(EngineRouter.Engine.parse(config.engine));

		openHistory = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.ctrlz.history",
				com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, KeyMapping.Category.MISC));
		rollbackLast = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.ctrlz.rollback",
				com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, KeyMapping.Category.MISC));
		openConfig = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.ctrlz.config",
				com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, KeyMapping.Category.MISC));

		if (EngineRouter.isV2()) {
			V2ClientNet.register();
		} else {
			ClientNet.register();
		}

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null || client.screen != null) {
				return;
			}
			while (rollbackLast.consumeClick()) {
				if (EngineRouter.isV2()) {
					V2ClientNet.requestUndo();
				} else {
					ClientNet.rollbackLast();
				}
			}
			if (!EngineRouter.isLegacy()) {
				// The history and config screens still speak the legacy protocol. They are migrated
				// with slice 4 instead of showing stale data from a system that is not recording.
				return;
			}
			while (openHistory.consumeClick()) {
				client.setScreen(new HistoryScreen());
			}
			while (openConfig.consumeClick()) {
				client.setScreen(new ConfigScreen());
			}
		});
	}
}
