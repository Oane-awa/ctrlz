package com.ctrlz.core.activity;

import com.ctrlz.core.trace.TraceCompressor;
import com.ctrlz.core.trace.TracePoint;
import com.ctrlz.core.util.ByteIO;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per player activity segmentation state machine.
 *
 * <p>It owns the "one continuous action is one activity" rule. Movement keeps extending the same
 * activity while the player keeps moving (direction changes are just new key frames), and the
 * other kinds merge while they stay inside their merge window.
 */
public final class ActivitySession {
	public interface Sink {
		void opened(ActivityRecord record);

		void checkpointed(ActivityRecord record);

		void sealed(ActivityRecord record);
	}

	public record Sample(int tick, double x, double y, double z, boolean sprinting, boolean sneaking,
			boolean onGround) {
	}

	private final UUID playerId;
	private final String playerName;
	private final Sink sink;
	private final int mergeGapTicks;
	private final int checkpointTicks;
	private final String dimension;
	private final java.util.function.LongSupplier idSource;

	private ActivityRecord current;
	private final List<TracePoint> pending = new ArrayList<>();
	private TracePoint lastTrace;
	private int lastCheckpointTick;
	private double accumulatedDistance;

	public ActivitySession(UUID playerId, String playerName, String dimension, int mergeGapTicks,
			int checkpointTicks, java.util.function.LongSupplier idSource, Sink sink) {
		this.playerId = playerId;
		this.playerName = playerName;
		this.dimension = dimension;
		this.idSource = idSource;
		this.sink = sink;
		this.mergeGapTicks = Math.max(2, mergeGapTicks);
		this.checkpointTicks = Math.max(20, checkpointTicks);
	}

	public ActivityRecord current() {
		return current;
	}

	public String dimension() {
		return dimension;
	}

	/** Feeds one server tick of movement and returns the activity that owns it. */
	public ActivityRecord observeMovement(Sample sample) {
		int tick = sample.tick();
		if (current != null && current.kind() == ActivityKind.MOVE && tick - current.endTick() <= mergeGapTicks) {
			extendMovement(sample);
			return current;
		}
		closeCurrent(tick);
		open(ActivityKind.MOVE, sample);
		extendMovement(sample);
		return current;
	}

	/** Feeds a discrete action, merging it into the open activity when it is continuous. */
	public ActivityRecord observeAction(ActivityKind kind, Sample sample) {
		int tick = sample.tick();
		if (current != null && current.kind() == kind && current.kind().canMergeWith(kind)
				&& tick - current.endTick() <= kind.defaultMergeGapTicks()) {
			int previousEnd = current.endTick();
			current.setEnd(tick, System.currentTimeMillis(), pack(sample));
			current.increment(ActivityStat.TICKS, Math.max(0, tick - previousEnd));
			checkpointIfDue(tick);
			return current;
		}
		closeCurrent(tick);
		open(kind, sample);
		return current;
	}

	/** Seals the open activity once the player has been idle longer than the merge window. */
	public void tick(int tick) {
		if (current == null) {
			return;
		}
		ActivityKind kind = current.kind();
		int gap = kind == ActivityKind.MOVE ? mergeGapTicks : Math.max(mergeGapTicks, kind.defaultMergeGapTicks());
		if (tick - current.endTick() > gap) {
			closeCurrent(tick);
		} else if (tick - lastCheckpointTick >= checkpointTicks) {
			checkpoint();
		}
	}

	public void flush(int tick) {
		closeCurrent(tick);
	}

	private void open(ActivityKind kind, Sample sample) {
		long id = idSource.getAsLong();
		current = new ActivityRecord(id, playerId, playerName, kind, dimension, sample.tick(),
				System.currentTimeMillis(), pack(sample));
		pending.clear();
		lastTrace = null;
		accumulatedDistance = 0.0;
		lastCheckpointTick = sample.tick();
		sink.opened(current);
	}

	private void extendMovement(Sample sample) {
		TracePoint point = new TracePoint(sample.x(), sample.y(), sample.z(), sample.tick(), sample.sprinting(),
				sample.sneaking(), sample.onGround());
		if (lastTrace != null) {
			double step = lastTrace.distanceTo(point);
			if (step > 0.0 && step < 16.0) {
				accumulatedDistance += step;
			}
		}
		pending.add(point);
		lastTrace = point;
		current.setEnd(sample.tick(), System.currentTimeMillis(), pack(sample));
		current.stats()[ActivityStat.BLOCKS_WALKED_CM.ordinal()] = (int) Math.min(Integer.MAX_VALUE,
				Math.round(accumulatedDistance * 100.0));
		current.increment(ActivityStat.TICKS, 1);
		checkpointIfDue(sample.tick());
	}

	private void checkpointIfDue(int tick) {
		if (tick - lastCheckpointTick >= checkpointTicks) {
			checkpoint();
		}
	}

	private void checkpoint() {
		if (current == null) {
			return;
		}
		if (current.kind() == ActivityKind.MOVE) {
			List<TracePoint> compressed = TraceCompressor.compress(pending, 0.08, 0.02);
			current.trace().clear();
			current.trace().addAll(compressed);
		}
		lastCheckpointTick = current.endTick();
		sink.checkpointed(current);
	}

	private void closeCurrent(int tick) {
		if (current == null) {
			return;
		}
		if (current.kind() == ActivityKind.MOVE) {
			List<TracePoint> compressed = TraceCompressor.compress(pending, 0.08, 0.02);
			current.trace().clear();
			current.trace().addAll(compressed);
		}
		current.setEnd(Math.max(current.endTick(), tick), System.currentTimeMillis(), current.endPacked());
		sink.sealed(current);
		current = null;
		pending.clear();
		lastTrace = null;
		accumulatedDistance = 0.0;
	}

	private static long pack(Sample sample) {
		return ByteIO.packBlockPos((int) Math.floor(sample.x()), (int) Math.floor(sample.y()),
				(int) Math.floor(sample.z()));
	}
}
