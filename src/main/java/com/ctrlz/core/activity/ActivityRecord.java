package com.ctrlz.core.activity;

import com.ctrlz.core.trace.TracePoint;
import com.ctrlz.core.util.ByteIO;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A player activity: one continuous thing the player did.
 *
 * <p>The trace is stored as compressed key frames with fixed point coordinates (1/128 of a
 * block), so a ten minute walk costs a few hundred bytes instead of tens of thousands of
 * per tick samples.
 */
public final class ActivityRecord {
	private static final int FLAG_SPRINT = 1;
	private static final int FLAG_SNEAK = 2;
	private static final int FLAG_GROUND = 4;

	private final long id;
	private final UUID playerId;
	private final String playerName;
	private final ActivityKind kind;
	private final String dimension;
	private final int startTick;
	private final long startMillis;
	private final long startPacked;
	private final int[] stats;
	private final List<TracePoint> trace;

	private int endTick;
	private long endMillis;
	private long endPacked;
	private int consequenceCount;
	private ActivityStatus status = ActivityStatus.RECORDING;
	private String statusReason = "";
	private boolean dirty;

	public ActivityRecord(long id, UUID playerId, String playerName, ActivityKind kind, String dimension, int startTick,
			long startMillis, long startPacked) {
		this.id = id;
		this.playerId = playerId;
		this.playerName = playerName == null ? "" : playerName;
		this.kind = kind;
		this.dimension = dimension;
		this.startTick = startTick;
		this.endTick = startTick;
		this.startMillis = startMillis;
		this.endMillis = startMillis;
		this.startPacked = startPacked;
		this.endPacked = startPacked;
		this.stats = new int[ActivityStat.COUNT];
		this.trace = new ArrayList<>();
	}

	public long id() {
		return id;
	}

	public UUID playerId() {
		return playerId;
	}

	public String playerName() {
		return playerName;
	}

	public ActivityKind kind() {
		return kind;
	}

	public String dimension() {
		return dimension;
	}

	public int startTick() {
		return startTick;
	}

	public int endTick() {
		return endTick;
	}

	public long startMillis() {
		return startMillis;
	}

	public long endMillis() {
		return endMillis;
	}

	public long startPacked() {
		return startPacked;
	}

	public long endPacked() {
		return endPacked;
	}

	public int[] stats() {
		return stats;
	}

	public List<TracePoint> trace() {
		return trace;
	}

	public int consequenceCount() {
		return consequenceCount;
	}

	public ActivityStatus status() {
		return status;
	}

	public String statusReason() {
		return statusReason;
	}

	public boolean dirty() {
		return dirty;
	}

	public void clearDirty() {
		dirty = false;
	}

	public void markDirty() {
		dirty = true;
	}

	public void setStatus(ActivityStatus status, String reason) {
		this.status = status;
		this.statusReason = reason == null ? "" : reason;
		this.dirty = true;
	}

	public void setEnd(int tick, long millis, long packedPos) {
		this.endTick = tick;
		this.endMillis = millis;
		this.endPacked = packedPos;
		this.dirty = true;
	}

	public void increment(ActivityStat stat, int amount) {
		stats[stat.ordinal()] += amount;
		dirty = true;
	}

	public int stat(ActivityStat stat) {
		return stats[stat.ordinal()];
	}

	public void setConsequenceCount(int count) {
		this.consequenceCount = count;
		dirty = true;
	}

	public void bumpConsequences() {
		consequenceCount++;
		dirty = true;
	}

	public int durationTicks() {
		return Math.max(0, endTick - startTick);
	}

	public double distanceBlocks() {
		return stats[ActivityStat.BLOCKS_WALKED_CM.ordinal()] / 100.0;
	}

	public byte[] encode(boolean includeTrace) {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
		try (DataOutputStream out = new DataOutputStream(buffer)) {
			ByteIO.writeVarLong(out, id);
			ByteIO.writeUuid(out, playerId);
			ByteIO.writeString(out, playerName);
			ByteIO.writeVarInt(out, kind.id());
			ByteIO.writeString(out, dimension);
			ByteIO.writeVarInt(out, startTick);
			ByteIO.writeVarInt(out, endTick);
			out.writeLong(startMillis);
			out.writeLong(endMillis);
			out.writeLong(startPacked);
			out.writeLong(endPacked);
			ByteIO.writeVarInt(out, stats.length);
			for (int stat : stats) {
				ByteIO.writeVarInt(out, Math.max(0, stat));
			}
			ByteIO.writeVarInt(out, consequenceCount);
			ByteIO.writeVarInt(out, status.id());
			ByteIO.writeString(out, statusReason);
			if (includeTrace) {
				writeTrace(out, trace);
			} else {
				ByteIO.writeVarInt(out, 0);
			}
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return buffer.toByteArray();
	}

	public static ActivityRecord decode(byte[] payload, boolean expectTrace) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
			long id = ByteIO.readVarLong(in);
			UUID playerId = ByteIO.readUuid(in);
			String playerName = ByteIO.readString(in);
			ActivityKind kind = ActivityKind.byId(ByteIO.readVarInt(in));
			String dimension = ByteIO.readString(in);
			int startTick = ByteIO.readVarInt(in);
			int endTick = ByteIO.readVarInt(in);
			long startMillis = in.readLong();
			long endMillis = in.readLong();
			long startPacked = in.readLong();
			long endPacked = in.readLong();
			int statCount = ByteIO.readVarInt(in);
			int[] stats = new int[ActivityStat.COUNT];
			for (int i = 0; i < statCount; i++) {
				int value = ByteIO.readVarInt(in);
				if (i < stats.length) {
					stats[i] = value;
				}
			}
			int consequenceCount = ByteIO.readVarInt(in);
			ActivityStatus status = ActivityStatus.byId(ByteIO.readVarInt(in));
			String statusReason = ByteIO.readString(in);
			ActivityRecord record = new ActivityRecord(id, playerId, playerName, kind, dimension, startTick,
					startMillis, startPacked);
			record.endTick = endTick;
			record.endMillis = endMillis;
			record.endPacked = endPacked;
			System.arraycopy(stats, 0, record.stats, 0, Math.min(stats.length, record.stats.length));
			record.consequenceCount = consequenceCount;
			record.status = status;
			record.statusReason = statusReason;
			List<TracePoint> points = readTrace(in);
			if (expectTrace) {
				record.trace.addAll(points);
				record.endTick = endTick;
				record.endPacked = endPacked;
			}
			record.dirty = false;
			return record;
		} catch (IOException e) {
			return null;
		}
	}

	private static void writeTrace(DataOutputStream out, List<TracePoint> points) throws IOException {
		ByteIO.writeVarInt(out, points.size());
		TracePoint previous = null;
		for (TracePoint point : points) {
			int flags = (point.sprinting ? FLAG_SPRINT : 0) | (point.sneaking ? FLAG_SNEAK : 0)
					| (point.onGround ? FLAG_GROUND : 0);
			if (previous == null) {
				ByteIO.writeZigZag(out, fixed(point.x));
				ByteIO.writeZigZag(out, fixed(point.y));
				ByteIO.writeZigZag(out, fixed(point.z));
				ByteIO.writeVarInt(out, point.tick);
			} else {
				ByteIO.writeZigZag(out, fixed(point.x) - fixed(previous.x));
				ByteIO.writeZigZag(out, fixed(point.y) - fixed(previous.y));
				ByteIO.writeZigZag(out, fixed(point.z) - fixed(previous.z));
				ByteIO.writeVarInt(out, Math.max(0, point.tick - previous.tick));
			}
			out.writeByte(flags);
			previous = point;
		}
	}

	private static List<TracePoint> readTrace(DataInputStream in) throws IOException {
		int count = ByteIO.readVarInt(in);
		List<TracePoint> points = new ArrayList<>(Math.min(Math.max(count, 0), 8192));
		int x = 0;
		int y = 0;
		int z = 0;
		int tick = 0;
		for (int i = 0; i < count; i++) {
			if (i == 0) {
				x = ByteIO.readZigZag(in);
				y = ByteIO.readZigZag(in);
				z = ByteIO.readZigZag(in);
				tick = ByteIO.readVarInt(in);
			} else {
				x += ByteIO.readZigZag(in);
				y += ByteIO.readZigZag(in);
				z += ByteIO.readZigZag(in);
				tick += ByteIO.readVarInt(in);
			}
			int flags = in.readUnsignedByte();
			points.add(new TracePoint(x / 128.0, y / 128.0, z / 128.0, tick, (flags & FLAG_SPRINT) != 0,
					(flags & FLAG_SNEAK) != 0, (flags & FLAG_GROUND) != 0));
		}
		return points;
	}

	private static int fixed(double value) {
		return (int) Math.round(value * 128.0);
	}
}
