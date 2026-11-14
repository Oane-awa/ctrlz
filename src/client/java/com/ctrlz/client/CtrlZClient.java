package com.ctrlz.client;

import com.ctrlz.client.gui.ConfigScreen;
import com.ctrlz.client.gui.HistoryScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Client entry point: key bindings and the screens.
 *
 * <p>Keys: G opens the activity history, Z rolls back the newest rollbackable activity, K opens the
 * configuration screen. All three only send requests; the server decides what actually happens.
 */
public final class CtrlZClient implements ClientModInitializer {
	public static KeyMapping openHistory;
	public static KeyMapping rollbackLast;
	public static KeyMapping openConfig;

	@Override
	public void onInitializeClient() {
		openHistory = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.ctrlz.history",
				com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, KeyMapping.Category.MISC));
		rollbackLast = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.ctrlz.rollback",
				com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, KeyMapping.Category.MISC));
		openConfig = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.ctrlz.config",
				com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, KeyMapping.Category.MISC));

		ClientNet.register();

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null || client.screen != null) {
				return;
			}
			while (openHistory.consumeClick()) {
				client.setScreen(new HistoryScreen());
			}
			while (rollbackLast.consumeClick()) {
				ClientNet.rollbackLast();
			}
			while (openConfig.consumeClick()) {
				client.setScreen(new ConfigScreen());
			}
		});
	}
}

