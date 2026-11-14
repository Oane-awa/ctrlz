package com.ctrlz.client.gui;

import com.ctrlz.client.ClientNet;
import com.ctrlz.config.CtrlZConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToIntBiFunction;

/**
 * Configuration screen. Values are edited on a local copy and only written to the server when Save
 * is pressed, so the server always receives a complete, validated configuration.
 */
public final class ConfigScreen extends Screen implements ClientNet.Refreshable {
	private record Toggle(String key, Function<CtrlZConfig, Boolean> get, BiConsumer<CtrlZConfig, Boolean> set) {
	}

	private record Number(String key, int min, int max, int step, ToIntBiFunction<CtrlZConfig, Integer> get,
			BiConsumer<CtrlZConfig, Integer> set) {
	}

	private static final List<Toggle> TOGGLES = new ArrayList<>();
	private static final List<Number> NUMBERS = new ArrayList<>();

	static {
		TOGGLES.add(new Toggle("ctrlz.config.enabled", c -> c.enabled, (c, v) -> c.enabled = v));
		TOGGLES.add(new Toggle("ctrlz.config.recordMovement", c -> c.recordMovement, (c, v) -> c.recordMovement = v));
		TOGGLES.add(new Toggle("ctrlz.config.recordBlockBreak", c -> c.recordBlockBreak,
				(c, v) -> c.recordBlockBreak = v));
		TOGGLES.add(new Toggle("ctrlz.config.recordBlockPlace", c -> c.recordBlockPlace,
				(c, v) -> c.recordBlockPlace = v));
		TOGGLES.add(new Toggle("ctrlz.config.recordAttack", c -> c.recordAttack, (c, v) -> c.recordAttack = v));
		TOGGLES.add(new Toggle("ctrlz.config.recordContainer", c -> c.recordContainer,
				(c, v) -> c.recordContainer = v));
		TOGGLES.add(new Toggle("ctrlz.config.recordInventory", c -> c.recordInventory,
				(c, v) -> c.recordInventory = v));
		TOGGLES.add(new Toggle("ctrlz.config.recordCrafting", c -> c.recordCrafting, (c, v) -> c.recordCrafting = v));
		TOGGLES.add(new Toggle("ctrlz.config.recordItemDrop", c -> c.recordItemDrop, (c, v) -> c.recordItemDrop = v));
		TOGGLES.add(new Toggle("ctrlz.config.recordItemPickup", c -> c.recordItemPickup,
				(c, v) -> c.recordItemPickup = v));
		TOGGLES.add(new Toggle("ctrlz.config.captureBlockEntityData", c -> c.captureBlockEntityData,
				(c, v) -> c.captureBlockEntityData = v));
		TOGGLES.add(new Toggle("ctrlz.config.captureContainerContents", c -> c.captureContainerContents,
				(c, v) -> c.captureContainerContents = v));
		TOGGLES.add(new Toggle("ctrlz.config.rollbackRestoreDurability", c -> c.rollbackRestoreDurability,
				(c, v) -> c.rollbackRestoreDurability = v));
		TOGGLES.add(new Toggle("ctrlz.config.announceRollback", c -> c.announceRollback,
				(c, v) -> c.announceRollback = v));
		TOGGLES.add(new Toggle("ctrlz.config.useMineTracerAudit", c -> c.useMineTracerAudit,
				(c, v) -> c.useMineTracerAudit = v));
		TOGGLES.add(new Toggle("ctrlz.config.useMineTracerFallback", c -> c.useMineTracerFallback,
				(c, v) -> c.useMineTracerFallback = v));
		TOGGLES.add(new Toggle("ctrlz.config.debugLogging", c -> c.debugLogging, (c, v) -> c.debugLogging = v));

		NUMBERS.add(new Number("ctrlz.config.movementMergeGapTicks", 10, 200, 5, (c, d) -> c.movementMergeGapTicks,
				(c, v) -> c.movementMergeGapTicks = v));
		NUMBERS.add(new Number("ctrlz.config.checkpointIntervalTicks", 20, 1200, 20,
				(c, d) -> c.checkpointIntervalTicks, (c, v) -> c.checkpointIntervalTicks = v));
		NUMBERS.add(new Number("ctrlz.config.autosaveSeconds", 5, 600, 5, (c, d) -> c.autosaveSeconds,
				(c, v) -> c.autosaveSeconds = v));
		NUMBERS.add(new Number("ctrlz.config.maxActivities", 100, 100000, 100, (c, d) -> c.maxActivities,
				(c, v) -> c.maxActivities = v));
		NUMBERS.add(new Number("ctrlz.config.maxStorageMegabytes", 8, 4096, 8, (c, d) -> c.maxStorageMegabytes,
				(c, v) -> c.maxStorageMegabytes = v));
		NUMBERS.add(new Number("ctrlz.config.retentionDays", 0, 365, 1, (c, d) -> c.retentionDays,
				(c, v) -> c.retentionDays = v));
		NUMBERS.add(new Number("ctrlz.config.rollbackPermissionLevel", 0, 4, 1,
				(c, d) -> c.rollbackPermissionLevel, (c, v) -> c.rollbackPermissionLevel = v));
		NUMBERS.add(new Number("ctrlz.config.historyPageSize", 5, 50, 1, (c, d) -> c.historyPageSize,
				(c, v) -> c.historyPageSize = v));
	}

	private CtrlZConfig draft;
	private boolean showNumbers;
	private boolean requested;

	public ConfigScreen() {
		super(Component.translatable("ctrlz.gui.config"));
	}

	@Override
	protected void init() {
		if (!requested) {
			requested = true;
			ClientNet.requestConfig();
		}
		if (draft == null) {
			draft = ClientNet.config() == null ? new CtrlZConfig() : ClientNet.config().copy();
		}
		int columnWidth = Math.min(150, (this.width - 60) / 2);
		int left = this.width / 2 - columnWidth - 4;
		int right = this.width / 2 + 4;
		int top = 44;
		if (!showNumbers) {
			int perColumn = (TOGGLES.size() + 1) / 2;
			for (int i = 0; i < TOGGLES.size(); i++) {
				Toggle toggle = TOGGLES.get(i);
				int x = i < perColumn ? left : right;
				int y = top + (i % perColumn) * 22;
				addRenderableWidget(Button.builder(label(toggle.key(), toggle.get().apply(draft)), button -> {
					toggle.set().accept(draft, !toggle.get().apply(draft));
					rebuildWidgets();
				}).bounds(x, y, columnWidth, 20).build());
			}
		} else {
			int perColumn = (NUMBERS.size() + 1) / 2;
			for (int i = 0; i < NUMBERS.size(); i++) {
				Number number = NUMBERS.get(i);
				int x = i < perColumn ? left : right;
				int y = top + (i % perColumn) * 22;
				addRenderableWidget(Button.builder(Component.literal("-"), button -> {
					int value = Math.max(number.min(), number.get().applyAsInt(draft, 0) - number.step());
					number.set().accept(draft, value);
					rebuildWidgets();
				}).bounds(x, y, 20, 20).build());
				addRenderableWidget(Button.builder(Component.literal("+"), button -> {
					int value = Math.min(number.max(), number.get().applyAsInt(draft, 0) + number.step());
					number.set().accept(draft, value);
					rebuildWidgets();
				}).bounds(x + 22, y, 20, 20).build());
				addRenderableWidget(Button.builder(
						Component.literal(Component.translatable(number.key()).getString() + ": "
								+ number.get().applyAsInt(draft, 0)),
						button -> {
						}).bounds(x + 44, y, columnWidth - 44, 20).build());
			}
		}
		int navY = this.height - 28;
		addRenderableWidget(Button.builder(
				Component.translatable(showNumbers ? "ctrlz.gui.flags" : "ctrlz.gui.numbers"),
				button -> {
					showNumbers = !showNumbers;
					rebuildWidgets();
				}).bounds(16, navY, 80, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("ctrlz.gui.save"),
				button -> ClientNet.saveConfig(draft)).bounds(this.width / 2 - 100, navY, 96, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
				.bounds(this.width / 2 + 4, navY, 96, 20).build());
	}

	private Component label(String key, boolean value) {
		return Component.literal(Component.translatable(key).getString() + ": "
				+ Component.translatable(value ? "ctrlz.gui.on" : "ctrlz.gui.off").getString());
	}

	@Override
	public void onDataChanged() {
		if (ClientNet.config() != null) {
			draft = ClientNet.config().copy();
		}
		rebuildWidgets();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
		graphics.text(this.font, Component.translatable("ctrlz.gui.config_hint").getString(), 16, 30, 0xFFB0B0B0);
	}
}
