package com.ctrlz.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server authoritative configuration, persisted as JSON and synchronised to the client so the
 * in game configuration screen always edits the values the server actually uses.
 */
public final class CtrlZConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * Which recording engine is active: {@code "legacy"} or {@code "v2"}.
	 *
	 * <p>Read once at startup, never hot switched. Both engines live in the jar, but only the
	 * selected one registers its hooks, so the world never sees double recording.
	 */
	public String engine = "legacy";

	public boolean enabled = true;

	public boolean recordMovement = true;
	public boolean recordBlockBreak = true;
	public boolean recordBlockPlace = true;
	public boolean recordAttack = true;
	public boolean recordContainer = true;
	public boolean recordInventory = true;
	public boolean recordCrafting = true;
	public boolean recordItemDrop = true;
	public boolean recordItemPickup = true;
	public boolean recordUseItem = true;

	public boolean captureBlockState = true;
	public boolean captureBlockEntityData = true;
	public boolean captureContainerContents = true;
	public boolean captureItemEntities = true;
	public boolean captureEntityState = true;
	public boolean capturePlayerState = true;
	public boolean captureScheduledTicks = true;

	public int movementMergeGapTicks = 40;
	/** How long a standstill must last before the walk is considered finished. */
	public int movementStopTicks = 10;
	public int checkpointIntervalTicks = 200;
	public int autosaveSeconds = 30;

	public int historyPageSize = 12;
	public int maxActivities = 5000;
	public int maxStorageMegabytes = 128;
	public int segmentMegabytes = 8;
	public int retentionDays = 7;

	public int rollbackPermissionLevel = 2;
	public int rollbackCooldownTicks = 20;
	public boolean rollbackRemoveSpawnedItems = true;
	public boolean rollbackRestoreDurability = true;
	public boolean rollbackRestoreExperience = true;
	public boolean announceRollback = true;

	public boolean useMineTracerAudit = true;
	public boolean useMineTracerFallback = false;

	public boolean debugLogging = false;

	public CtrlZConfig copy() {
		return fromJson(toJson());
	}

	public String toJson() {
		return GSON.toJson(this);
	}

	public static CtrlZConfig fromJson(String json) {
		CtrlZConfig config = GSON.fromJson(json, CtrlZConfig.class);
		if (config == null) {
			config = new CtrlZConfig();
		}
		config.validate();
		return config;
	}

	public void validate() {
		if (!"legacy".equalsIgnoreCase(engine) && !"v2".equalsIgnoreCase(engine)) {
			engine = "legacy";
		} else {
			engine = engine.toLowerCase(java.util.Locale.ROOT);
		}
		movementMergeGapTicks = clamp(movementMergeGapTicks, 10, 200);
		movementStopTicks = clamp(movementStopTicks, 2, 100);
		checkpointIntervalTicks = clamp(checkpointIntervalTicks, 20, 6000);
		autosaveSeconds = clamp(autosaveSeconds, 5, 600);
		historyPageSize = clamp(historyPageSize, 5, 50);
		maxActivities = clamp(maxActivities, 1, 1_000_000);
		maxStorageMegabytes = clamp(maxStorageMegabytes, 1, 8192);
		segmentMegabytes = clamp(segmentMegabytes, 1, 1024);
		retentionDays = clamp(retentionDays, 0, 3650);
		rollbackPermissionLevel = clamp(rollbackPermissionLevel, 0, 4);
		rollbackCooldownTicks = clamp(rollbackCooldownTicks, 0, 1200);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	public static Path defaultPath(Path gameDirectory) {
		return gameDirectory.resolve("config").resolve("ctrlz.json");
	}

	public static CtrlZConfig load(Path path) {
		try {
			if (Files.exists(path)) {
				return fromJson(Files.readString(path, StandardCharsets.UTF_8));
			}
		} catch (IOException | RuntimeException ignored) {
		}
		CtrlZConfig config = new CtrlZConfig();
		config.save(path);
		return config;
	}

	public void save(Path path) {
		try {
			Files.createDirectories(path.getParent());
			Path temp = path.resolveSibling(path.getFileName() + ".tmp");
			Files.writeString(temp, toJson(), StandardCharsets.UTF_8);
			Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ignored) {
		}
	}
}
