package com.ctrlz.client.gui;

import com.ctrlz.client.ClientNet;
import com.ctrlz.net.Messages;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Activity history browser.
 *
 * <p>One row per activity, with a rollback button per row. The rows are vanilla buttons, so the
 * screen needs no custom hit testing or drawing beyond the header text.
 */
public final class HistoryScreen extends Screen implements ClientNet.Refreshable {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
	private static final int ROW_HEIGHT = 22;
	private static final int ROWS_PER_PAGE = 10;

	private final List<Messages.ActivityDto> rows = new ArrayList<>();
	private int offset;
	private boolean requested;

	public HistoryScreen() {
		super(Component.translatable("ctrlz.gui.history"));
	}

	@Override
	protected void init() {
		rows.clear();
		// Always ask the server once per opened screen. The cached copy is only shown while the
		// fresh answer is in flight; trusting it unconditionally is what froze the list before.
		if (!requested) {
			requested = true;
			ClientNet.requestHistory(offset, null);
		}
		Messages.HistoryDto data = ClientNet.history();
		if (data != null) {
			rows.addAll(data.items());
		}

		int rowWidth = Math.max(180, this.width - 150);
		int top = 58;
		for (int i = 0; i < rows.size(); i++) {
			Messages.ActivityDto activity = rows.get(i);
			addRenderableWidget(Button.builder(Component.literal(rowLabel(activity)),
					button -> minecraft.setScreen(new DetailScreen(this, activity.id())))
					.bounds(16, top + i * ROW_HEIGHT, rowWidth, 20).build());
			addRenderableWidget(Button.builder(Component.literal("\u21ba"),
					button -> ClientNet.requestRollback(activity.id()))
					.bounds(this.width - 126, top + i * ROW_HEIGHT, 26, 20).build());
			addRenderableWidget(Button.builder(Component.translatable("ctrlz.gui.detail"),
					button -> minecraft.setScreen(new DetailScreen(this, activity.id())))
					.bounds(this.width - 96, top + i * ROW_HEIGHT, 80, 20).build());
		}

		int navY = this.height - 28;
		addRenderableWidget(Button.builder(Component.literal("<"), button -> page(-ROWS_PER_PAGE))
				.bounds(16, navY, 30, 20).build());
		addRenderableWidget(Button.builder(Component.literal(">"), button -> page(ROWS_PER_PAGE))
				.bounds(52, navY, 30, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("ctrlz.gui.refresh"), button -> refresh())
				.bounds(88, navY, 60, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("ctrlz.gui.rollback_last"),
				button -> ClientNet.rollbackLast()).bounds(this.width / 2 - 100, navY, 96, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("ctrlz.gui.config"),
				button -> minecraft.setScreen(new ConfigScreen())).bounds(this.width / 2 + 4, navY, 96, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
				.bounds(this.width - 76, navY, 60, 20).build());
	}

	private void page(int delta) {
		offset = Math.max(0, offset + delta);
		refresh();
	}

	private void refresh() {
		requested = true;
		ClientNet.requestHistory(offset, null);
	}

	@Override
	public void onDataChanged() {
		Messages.RollbackResultDto result = ClientNet.lastResult();
		if (result != null) {
			// ClientNet already re-fetched the list after a successful restore; this only surfaces the
			// outcome. Patching the rows here would show a history the server no longer has.
			lastResultKey = result.success() ? "" : result.message();
		}
		rebuildWidgets();
	}

	private String lastResultKey = "";

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
		Messages.HistoryDto data = ClientNet.history();
		String header = data == null ? Component.translatable("ctrlz.gui.loading").getString()
				: Component.translatable("ctrlz.gui.summary", data.total(), data.storedActivities(),
						data.backend()).getString();
		graphics.text(this.font, header, 16, 34, 0xFFB0B0B0);
		if (!lastResultKey.isEmpty()) {
			graphics.text(this.font, Component.translatable(lastResultKey).getString(), 16, 46, 0xFFFF6B6B);
		}
		if (data != null && data.items().isEmpty()) {
			graphics.text(this.font, Component.translatable("ctrlz.gui.empty").getString(), 16, 58, 0xFF909090);
		}
		graphics.text(this.font,
				Component.translatable("ctrlz.gui.page", offset / ROWS_PER_PAGE + 1).getString(),
				92, this.height - 22, 0xFF909090);
	}

	private String rowLabel(Messages.ActivityDto activity) {
		String time = TIME.format(Instant.ofEpochMilli(activity.startMillis()));
		String distance = String.format("%.1f", activity.distance());
		return time + "  " + activity.kind() + "  " + activity.x() + " " + activity.y() + " " + activity.z()
				+ "  " + distance + "m  x" + activity.consequences() + "  " + statusLabel(activity.status());
	}

	private String statusLabel(String status) {
		return Component.translatable("ctrlz.status." + status.toLowerCase()).getString();
	}
}
