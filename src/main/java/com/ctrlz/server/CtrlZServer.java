package com.ctrlz.server;

import com.ctrlz.config.CtrlZConfig;
import com.ctrlz.core.activity.ActivityKind;
import com.ctrlz.core.activity.ActivityRecord;
import com.ctrlz.core.activity.ActivitySession;
import com.ctrlz.core.activity.ActivityStat;
import com.ctrlz.core.activity.ActivityStatus;
import com.ctrlz.core.consequence.ConsequenceKind;
import com.ctrlz.core.consequence.ConsequenceOrigin;
import com.ctrlz.core.consequence.ConsequenceRecord;
import com.ctrlz.core.storage.ActivityStore;
import com.ctrlz.core.util.ByteIO;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side hub: owns the config, the store, the per player activity sessions and the causal
 * windows that tie world changes back to the activity that caused them.
 *
 * <p>Every write goes through {@link ActivityStore}, which is append only and checksummed, so the
 * recorder never blocks the tick thread on a full snapshot.
 */
public final class CtrlZServer {
	private static CtrlZServer instance;

	private final MinecraftServer server;
	private final Path root;
	private final long bootMillis = System.currentTimeMillis();
	private CtrlZConfig config;
	private ActivityStore store;
	private final Map<UUID, ActivitySession> sessions = new ConcurrentHashMap<>();
	private final Map<Long, MergeBucket> buckets = new HashMap<>();
	private final Map<Long, CausalWindow> windows = new HashMap<>();
	private final Map<UUID, List<net.minecraft.world.item.ItemStack>> lastInventories = new ConcurrentHashMap<>();
	private final Map<UUID, Vec3> lastPositions = new ConcurrentHashMap<>();

	private long lastAutosaveMillis;
	private int suppressionDepth;
	private final Map<UUID, Long> lastRollbackMillis = new ConcurrentHashMap<>();

	private CtrlZServer(MinecraftServer server) {
		this.server = server;
		this.root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("ctrlz");
	}

	public static CtrlZServer start(MinecraftServer server) {
		CtrlZServer hub = new CtrlZServer(server);
		hub.load();
		instance = hub;
		return hub;
	}

	public static CtrlZServer get() {
		return instance;
	}

	public static void stop() {
		if (instance != null) {
			instance.shutdown();
			instance = null;
		}
	}

	public MinecraftServer server() {
		return server;
	}

	public CtrlZConfig config() {
		return config;
	}

	public ActivityStore store() {
		return store;
	}

	private void load() {
		Path configPath = CtrlZConfig.defaultPath(root);
		config = CtrlZConfig.load(configPath);
		store = new ActivityStore(root.resolve("history"), new ActivityStore.Limits(config.maxActivities,
				config.maxStorageMegabytes * 1024L * 1024L, config.segmentMegabytes * 1024L * 1024L,
				config.checkpointIntervalTicks));
		try {
			store.open();
		} catch (IOException e) {
			store = null;
		}
		lastAutosaveMillis = System.currentTimeMillis();
	}

	public void saveConfig() {
		config.save(CtrlZConfig.defaultPath(root));
		if (store != null) {
			store.setLimits(new ActivityStore.Limits(config.maxActivities,
					config.maxStorageMegabytes * 1024L * 1024L, config.segmentMegabytes * 1024L * 1024L,
					config.checkpointIntervalTicks));
		}
	}

	/** Applies a configuration sent from the in game config screen and persists it. */
	public void applyConfig(CtrlZConfig updated) {
		updated.validate();
		this.config = updated;
		saveConfig();
	}

	private void shutdown() {
		for (ActivitySession session : sessions.values()) {
			session.flush(currentTick());
		}
		sessions.clear();
		if (store != null) {
			try {
				store.flush(true);
			} catch (IOException ignored) {
			}
			store.close();
		}
	}

	public int currentTick() {
		return (int) (server.overworld().getGameTime() & 0x7FFFFFFFL);
	}

	public ActivitySession session(ServerPlayer player) {
		return sessions.computeIfAbsent(player.getUUID(), id -> new ActivitySession(id,
				player.getGameProfile().name(), player.level().dimension().identifier().toString(),
				config.movementMergeGapTicks, config.checkpointIntervalTicks, this::allocateActivityId,
				new StoreSink()));
	}

	private long allocateActivityId() {
		return store == null ? 0L : store.allocateActivityId();
	}

	/**
	 * Slot level inventory diff for the player's own inventory.
	 *
	 * <p>Durability loss, consumed crafting ingredients, dropped stacks and picked up items all show
	 * up here, which is why the individual handlers do not need to snapshot the inventory
	 * themselves: one comparison per tick is both cheaper and impossible to forget.
	 */
	private void tickInventory(ServerPlayer player) {
		if (!config.captureContainerContents) {
			return;
		}
		List<net.minecraft.world.item.ItemStack> current = Recorder.snapshotInventory(player);
		List<net.minecraft.world.item.ItemStack> previous = lastInventories.put(player.getUUID(), current);
		if (previous == null || previous.size() != current.size()) {
			return;
		}
		ActivityRecord activity = currentActivity(player);
		if (activity == null) {
			return;
		}
		ServerLevelContext context = new ServerLevelContext(player.level(), player.level().dimension().identifier()
				.toString());
		for (int slot = 0; slot < current.size(); slot++) {
			net.minecraft.world.item.ItemStack before = previous.get(slot);
			net.minecraft.world.item.ItemStack after = current.get(slot);
			if (net.minecraft.world.item.ItemStack.matches(before, after)) {
				continue;
			}
			consequence(activity, ConsequenceKind.INVENTORY_SLOTS, ConsequenceOrigin.DIRECT, context.dimension(),
					ConsequenceRecord.NO_POSITION, "inv#" + player.getUUID() + "#" + slot,
					Snapshots.encodeSingleItem(before, context.level().registryAccess()),
					Snapshots.encodeSingleItem(after, context.level().registryAccess()),
					"slot " + slot + ": " + Snapshots.describe(before) + " -> " + Snapshots.describe(after), 0L);
		}
	}

	private record ServerLevelContext(net.minecraft.server.level.ServerLevel level, String dimension) {
	}

	/** Called once per server tick for every online player. */
	public void tick() {
		if (!config.enabled || store == null) {
			return;
		}
		int tick = currentTick();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			String dimension = player.level().dimension().identifier().toString();
			ActivitySession session = session(player);
			if (!session.dimension().equals(dimension)) {
				session.flush(tick);
				sessions.remove(player.getUUID());
				lastPositions.remove(player.getUUID());
				session = session(player);
			}
			Vec3 position = player.position();
			Vec3 previous = lastPositions.put(player.getUUID(), position);
			// Only a real displacement is movement. Feeding every tick used to keep a MOVE open
			// forever and seal every other action one tick after it started.
			if (config.recordMovement && previous != null && previous.distanceToSqr(position) > 1.0E-4) {
				if (session.movement() == null) {
					// Start the trace where the player stood before this step, so a rollback
					// returns to the real starting point and not to one step after it.
					session.observeMovement(new ActivitySession.Sample(tick - 1, previous.x, previous.y, previous.z,
							player.isSprinting(), player.isShiftKeyDown(), player.onGround()));
				}
				session.observeMovement(new ActivitySession.Sample(tick, position.x, position.y, position.z,
						player.isSprinting(), player.isShiftKeyDown(), player.onGround()));
			}
			session.tick(tick);
			tickInventory(player);
		}
		long now = System.currentTimeMillis();
		if (now - lastAutosaveMillis >= config.autosaveSeconds * 1000L) {
			lastAutosaveMillis = now;
			try {
				store.flush(true);
			} catch (IOException ignored) {
			}
		}
		expireWindows(tick);
		Recorder.tickPending();
	}

	/** Resolves an entity by uuid across every dimension. */
	public net.minecraft.world.entity.Entity findEntity(UUID uuid) {
		for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
			net.minecraft.world.entity.Entity entity = level.getEntity(uuid);
			if (entity != null) {
				return entity;
			}
		}
		return null;
	}

	public void onPlayerLeft(UUID playerId) {
		ActivitySession session = sessions.remove(playerId);
		if (session != null) {
			session.flush(currentTick());
		}
	}

	private final class StoreSink implements ActivitySession.Sink {
		@Override
		public void opened(ActivityRecord record) {
			if (store == null) {
				return;
			}
			try {
				store.begin(record);
			} catch (IOException ignored) {
			}
		}

		@Override
		public void checkpointed(ActivityRecord record) {
			if (store == null) {
				return;
			}
			try {
				store.checkpoint(record);
			} catch (IOException ignored) {
			}
		}

		@Override
		public void sealed(ActivityRecord record) {
			if (store == null) {
				return;
			}
			if (record.kind() == ActivityKind.MOVE) {
				recordMoveConsequence(record);
			}
			List<ConsequenceRecord> pendingConsequences = drainBucket(record.id());
			try {
				for (ConsequenceRecord consequence : pendingConsequences) {
					store.appendConsequence(consequence);
				}
				record.setConsequenceCount(pendingConsequences.size());
				store.seal(record);
				store.flush(false);
			} catch (IOException ignored) {
			}
			// The causal window is deliberately kept: redstone, pistons and fluids keep reacting
			// after the click that started them, and those late changes still belong to it.
		}
	}

	/**
	 * A walk is undone by teleporting the player back to where it started, so the movement itself
	 * needs a restorable state change. Walks shorter than one block have nothing worth undoing.
	 */
	private void recordMoveConsequence(ActivityRecord record) {
		List<com.ctrlz.core.trace.TracePoint> trace = record.trace();
		if (trace.size() < 2) {
			return;
		}
		com.ctrlz.core.trace.TracePoint first = trace.get(0);
		com.ctrlz.core.trace.TracePoint last = trace.get(trace.size() - 1);
		if (first.distanceTo(last) < 1.0) {
			return;
		}
		consequence(record, ConsequenceKind.PLAYER_STATE, ConsequenceOrigin.DIRECT, record.dimension(),
				record.startPacked(), "player#" + record.playerId(), encodePosition(first), encodePosition(last),
				String.format("move %.1f %.1f %.1f -> %.1f %.1f %.1f", first.x, first.y, first.z, last.x, last.y,
						last.z),
				0L);
	}

	public static byte[] encodePosition(com.ctrlz.core.trace.TracePoint point) {
		return (point.x + ";" + point.y + ";" + point.z).getBytes(java.nio.charset.StandardCharsets.UTF_8);
	}

	public static byte[] encodePosition(double x, double y, double z) {
		return (x + ";" + y + ";" + z).getBytes(java.nio.charset.StandardCharsets.UTF_8);
	}

	/**
	 * Seals the open activities of the player that owns {@code activity}. An activity that is still
	 * recording cannot be rolled back, and the one the player just did is exactly that one.
	 */
	public void sealIfOpen(ActivityRecord activity) {
		ActivitySession session = sessions.get(activity.playerId());
		if (session != null && session.owns(activity)) {
			session.flush(currentTick());
		}
	}

	/** Seals everything the player is currently doing, so the newest activity becomes rollbackable. */
	public void flushSession(UUID playerId) {
		ActivitySession session = sessions.get(playerId);
		if (session != null) {
			session.flush(currentTick());
		}
	}

	/** Forgets the inventory/position baselines so a rollback is never recorded as a new activity. */
	public void resyncBaselines() {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			lastInventories.put(player.getUUID(), Recorder.snapshotInventory(player));
			lastPositions.put(player.getUUID(), player.position());
		}
	}

	private final java.util.Set<String> pickedUp = java.util.Collections.newSetFromMap(
			new java.util.LinkedHashMap<String, Boolean>() {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
					return size() > 8192;
				}
			});

	public synchronized void markPickedUp(String entityUuid) {
		pickedUp.add(entityUuid);
	}

	public synchronized void unmarkPickedUp(String entityUuid) {
		pickedUp.remove(entityUuid);
	}

	public synchronized boolean wasPickedUp(String entityUuid) {
		return pickedUp.contains(entityUuid);
	}

	/** Marks a position as touched by an activity so changes around it can be attributed to it. */
	public void touch(ActivityRecord activity, String dimension, long packedPos) {
		if (activity != null && packedPos != ConsequenceRecord.NO_POSITION) {
			touchWindow(activity, dimension, packedPos, 0L, currentTick());
		}
	}

	/** Opens (or merges into) the activity that owns the action the player just performed. */
	public ActivityRecord openAction(ServerPlayer player, ActivityKind kind) {
		if (!config.enabled || store == null) {
			return null;
		}
		Vec3 position = player.position();
		return session(player).observeAction(kind, new ActivitySession.Sample(currentTick(), position.x, position.y,
				position.z, player.isSprinting(), player.isShiftKeyDown(), player.onGround()));
	}

	/** Counts a jump on the player's movement activity, opening one when the player was standing. */
	public void recordJump(ServerPlayer player) {
		if (!config.enabled || store == null) {
			return;
		}
		ActivitySession session = session(player);
		ActivityRecord movement = session.movement();
		if (movement == null) {
			Vec3 position = player.position();
			movement = session.observeMovement(new ActivitySession.Sample(currentTick(), position.x, position.y,
					position.z, player.isSprinting(), player.isShiftKeyDown(), player.onGround()));
		}
		movement.increment(ActivityStat.JUMPS, 1);
	}

	public ActivityRecord currentActivity(ServerPlayer player) {
		ActivitySession session = sessions.get(player.getUUID());
		return session == null ? null : session.current();
	}

	/**
	 * Records a world mutation. Repeated changes to the same target inside one activity are folded
	 * together, which keeps a chest that was clicked many times down to a single before/after pair.
	 */
	public ConsequenceRecord consequence(ActivityRecord activity, ConsequenceKind kind, ConsequenceOrigin origin,
			String dimension, long packedPos, String subject, byte[] before, byte[] after, String detail,
			long parentId) {
		if (activity == null || store == null) {
			return null;
		}
		int tick = currentTick();
		if (activity.status() != ActivityStatus.RECORDING) {
			// A late consequence (redstone, pistons, fluids) of an activity that is already sealed
			// goes straight to disk; waiting for a seal that already happened would lose it.
			ConsequenceRecord late = new ConsequenceRecord(store.allocateConsequenceId(), activity.id(), kind, origin,
					tick, dimension, packedPos, subject, before, after, parentId == 0L ? 0 : 1, parentId,
					System.currentTimeMillis(), detail);
			try {
				store.appendConsequence(late);
				activity.setConsequenceCount(store.consequenceCount(activity.id()));
				store.updateStatus(activity);
			} catch (IOException e) {
				return null;
			}
			touchForKind(activity, kind, dimension, packedPos, late.id(), tick);
			return late;
		}
		// A block is one target no matter what it turned into, so its changes fold into a single
		// original -> final pair. Slots stay distinct per slot.
		boolean blockKind = kind == ConsequenceKind.BLOCK_STATE || kind == ConsequenceKind.BLOCK_ENTITY_DATA;
		String key = kind.id() + "|" + packedPos + "|" + (blockKind ? "" : subject);
		MergeBucket bucket = buckets.computeIfAbsent(activity.id(), id -> new MergeBucket());
		ConsequenceRecord existing = bucket.entries.get(key);
		ConsequenceRecord record;
		if (existing != null) {
			existing.merge(after, tick, detail);
			record = existing;
		} else {
			record = new ConsequenceRecord(store.allocateConsequenceId(), activity.id(), kind, origin, tick, dimension,
					packedPos, subject, before, after, parentId == 0L ? 0 : 1, parentId, System.currentTimeMillis(),
					detail);
			bucket.entries.put(key, record);
			bucket.order.add(record);
		}
		touchForKind(activity, kind, dimension, packedPos, record.id(), tick);
		return record;
	}

	/**
	 * Only world blocks and containers extend the causal window. Player state, inventory slots and
	 * item entities carry no meaningful position to attribute other changes from.
	 */
	private void touchForKind(ActivityRecord activity, ConsequenceKind kind, String dimension, long packedPos,
			long consequenceId, int tick) {
		boolean spatial = kind == ConsequenceKind.BLOCK_STATE || kind == ConsequenceKind.BLOCK_ENTITY_DATA
				|| kind == ConsequenceKind.CONTAINER_SLOTS;
		if (spatial && packedPos != ConsequenceRecord.NO_POSITION) {
			touchWindow(activity, dimension, packedPos, consequenceId, tick);
		}
	}

	public void addStat(ActivityRecord activity, ActivityStat stat, int amount) {
		if (activity != null) {
			activity.increment(stat, amount);
		}
	}

	public List<ConsequenceRecord> drainBucket(long activityId) {
		MergeBucket bucket = buckets.remove(activityId);
		return bucket == null ? List.of() : new ArrayList<>(bucket.order);
	}

	public List<ConsequenceRecord> peekBucket(long activityId) {
		MergeBucket bucket = buckets.get(activityId);
		return bucket == null ? List.of() : new ArrayList<>(bucket.order);
	}

	private void touchWindow(ActivityRecord activity, String dimension, long packedPos, long consequenceId,
			int tick) {
		CausalWindow window = windows.computeIfAbsent(activity.id(), id -> new CausalWindow());
		window.dimension = dimension;
		window.expiresTick = tick + 60;
		window.owner = activity.id();
		if (window.touched.size() < 96) {
			window.touched.put(packedPos, consequenceId);
		}
	}

	private void expireWindows(int tick) {
		windows.entrySet().removeIf(entry -> entry.getValue().expiresTick < tick - 100);
	}

	/**
	 * Finds the activity that most plausibly caused a block change at {@code packedPos}.
	 *
	 * <p>Only positions touched by an activity, inside that activity's short causal window, are
	 * eligible. Anything else stays unattributed instead of being blamed on the nearest player.
	 */
	public CausalLink findCausal(String dimension, long packedPos, int tick) {
		int x = ByteIO.unpackX(packedPos);
		int y = ByteIO.unpackY(packedPos);
		int z = ByteIO.unpackZ(packedPos);
		CausalLink best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (CausalWindow window : windows.values()) {
			if (!dimension.equals(window.dimension) || tick > window.expiresTick) {
				continue;
			}
			for (Map.Entry<Long, Long> entry : window.touched.entrySet()) {
				int dx = Math.abs(ByteIO.unpackX(entry.getKey()) - x);
				int dy = Math.abs(ByteIO.unpackY(entry.getKey()) - y);
				int dz = Math.abs(ByteIO.unpackZ(entry.getKey()) - z);
				if (dx > 2 || dy > 2 || dz > 2) {
					continue;
				}
				int distance = dx + dy + dz;
				boolean closer = distance < bestDistance;
				boolean newer = distance == bestDistance && best != null && entry.getValue() > best.parentConsequenceId();
				if (best == null || closer || newer) {
					bestDistance = distance;
					best = new CausalLink(window.owner, entry.getValue(),
							distance == 0 ? ConsequenceOrigin.DIRECT : ConsequenceOrigin.INDIRECT);
				}
			}
		}
		return best;
	}

	public ActivityRecord activityForCausal(CausalLink link) {
		return link == null || store == null ? null : store.byId(link.activityId());
	}

	public record CausalLink(long activityId, long parentConsequenceId, ConsequenceOrigin origin) {
	}

	private static final class MergeBucket {
		private final LinkedHashMap<String, ConsequenceRecord> entries = new LinkedHashMap<>();
		private final List<ConsequenceRecord> order = new ArrayList<>();
	}

	private static final class CausalWindow {
		private long owner;
		private String dimension = "";
		private int expiresTick;
		private final LinkedHashMap<Long, Long> touched = new LinkedHashMap<>();
	}

	public Map<UUID, ActivitySession> sessions() {
		return sessions;
	}

	public long bootMillis() {
		return bootMillis;
	}

	public Map<UUID, Long> lastRollbackMillis() {
		return lastRollbackMillis;
	}

	public void beginSuppression() {
		suppressionDepth++;
	}

	public void endSuppression() {
		suppressionDepth = Math.max(0, suppressionDepth - 1);
	}

	public boolean suppressed() {
		return suppressionDepth > 0;
	}
}
