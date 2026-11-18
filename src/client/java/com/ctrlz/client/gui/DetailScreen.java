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
import java.util.List;

/**
 * Read only detail view for one activity: its compressed trace summary and every recorded
 * consequence with its causal classification.
 */
public final class DetailScreen extends Screen implements ClientNet.Refreshable {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
			.withZone(ZoneId.systemDefault());
	private static final int PAGE = 12;

	private final Screen parent;
	private final long activityId;
	private int offset;
	private boolean requested;

	public DetailScreen(Screen parent, long activityId) {
		super(Component.translatable("ctrlz.gui.detail_title"));
		this.parent = parent;
		this.activityId = activityId;
	}

	@Override
	protected void init() {
		// init() runs again on every rebuildWidgets(), and each server answer triggers one, so the
		// request must be guarded or the screen would keep asking for the same detail forever.
		if (!requested) {
			requested = true;
			ClientNet.requestDetail(activityId);
		}
		int navY = this.height - 28;
		addRenderableWidget(Button.builder(Component.literal("<"), button -> page(-PAGE)).bounds(16, navY, 30, 20)
				.build());
		addRenderableWidget(Button.builder(Component.literal(">"), button -> page(PAGE)).bounds(52, navY, 30, 20)
				.build());
		addRenderableWidget(Button.builder(Component.translatable("ctrlz.gui.rollback"),
				button -> ClientNet.requestRollback(activityId)).bounds(this.width / 2 - 60, navY, 120, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), button -> minecraft.setScreen(parent))
				.bounds(this.width - 76, navY, 60, 20).build());
	}

	private void page(int delta) {
		offset = Math.max(0, offset + delta);
		rebuildWidgets();
	}

	@Override
	public void onDataChanged() {
		Messages.RollbackResultDto result = ClientNet.lastResult();
		if (result != null) {
			// The activity this screen shows may have been truncated by the restore, so ask again;
			// the server answers with a notice when the row no longer exists.
			ClientNet.requestDetail(activityId);
		}
		rebuildWidgets();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		Messages.DetailDto detail = ClientNet.detail();
		graphics.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
		if (ClientNet.detailMissing() || detail == null || detail.activity().id() != activityId) {
			String key = ClientNet.detailMissing() ? "ctrlz.gui.detail_missing" : "ctrlz.gui.loading";
			graphics.text(this.font, Component.translatable(key).getString(), 16, 40, 0xFFB0B0B0);
			return;
		}
		Messages.ActivityDto activity = detail.activity();
		graphics.text(this.font, TIME.format(Instant.ofEpochMilli(activity.startMillis())) + "  " + activity.kind()
				+ "  " + activity.player(), 16, 34, 0xFFE0E0E0);
		graphics.text(this.font, Component.translatable("ctrlz.gui.detail_line", activity.x(), activity.y(), activity.z(),
				String.format("%.1f", activity.distance()), activity.consequences()).getString(), 16, 46, 0xFFB0B0B0);
		if (!activity.reason().isEmpty()) {
			graphics.text(this.font, Component.translatable(activity.reason()).getString(), 16, 58, 0xFFFF6B6B);
		}
		graphics.text(this.font, Component.translatable("ctrlz.gui.trace_points", detail.trace().size()).getString(),
				16, 72, 0xFF909090);
		List<Messages.ConsequenceDto> consequences = detail.consequences();
		int top = 88;
		for (int i = offset; i < Math.min(consequences.size(), offset + PAGE); i++) {
			Messages.ConsequenceDto consequence = consequences.get(i);
			String line = consequence.origin() + "  " + consequence.kind() + "  " + consequence.detail();
			graphics.text(this.font, line, 16, top + (i - offset) * 11, 0xFFCFCFCF);
		}
		if (consequences.isEmpty()) {
			graphics.text(this.font, Component.translatable("ctrlz.gui.no_consequences").getString(), 16, top,
					0xFF909090);
		}
	}
}
