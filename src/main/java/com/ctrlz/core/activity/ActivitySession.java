package com.ctrlz.core.activity;

import com.ctrlz.core.trace.TraceCompressor;
import com.ctrlz.core.trace.TracePoint;
import com.ctrlz.core.util.ByteIO;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Per player activity segmentation state machine.
 *
 * <p>A player can walk and mine at the same time, so two activities may be open at once: one
 * movement slot and one action slot. They never close each other. Movement only advances when the
 * player really changed position, which is what keeps a standing player from sealing every action
 * one tick after it started.
 */
public final class ActivitySession {
	/** A single tick displacement at or above this is a teleport, not walking. */
	public static final double TELEPORT_DISTANCE = 16.0;

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
	private final LongSupplier idSource;

	private ActivityRecord movement;
	private final List<TracePoint> pending = new ArrayList<>();
	private TracePoint lastTrace;
	private double accumulatedDistance;
	private int movementCheckpointTick;

	private ActivityRecord action;
	private int actionCheckpointTick;
	/** Kept briefly after sealing so a consequence of the same tick still lands on the action. */
	private ActivityRecord lastSealedAction;
	private int lastSealedActionTick = Integer.MIN_VALUE;

	public ActivitySession(UUID playerId, String playerName, String dimension, int stopTicks, int checkpointTicks,
			LongSupplier idSource, Sink sink) {
		this.playerId = playerId;
		this.playerName = playerName;
		this.dimension = dimension;
		this.idSource = idSource;
		this.sink = sink;
		this.mergeGapTicks = Math.max(2, stopTicks);
		this.checkpointTicks = Math.max(20, checkpointTicks);
	}

	/** The activity new consequences should be attributed to: the action if one is open. */
	public ActivityRecord current() {
		return action != null ? action : movement;
	}

	/**
	 * The action that owns consequences arriving now, including one that was sealed a moment ago.
	 *
	 * <p>A block placement is sealed as soon as the player moves on, but the inventory change that
	 * pays for it is only visible on the next tick. Without this grace period that change would be
	 * attached to the walk instead of to the placement, which is how a rollback could hand the item
	 * back while leaving the block standing.
	 */
	public ActivityRecord actionForConsequences(int tick, int graceTicks) {
		if (action != null) {
			return action;
		}
		return tick - lastSealedActionTick <= graceTicks ? lastSealedAction : null;
	}

	public ActivityRecord movement() {
		return movement;
	}

	public ActivityRecord action() {
		return action;
	}

	public boolean owns(ActivityRecord record) {
		return record != null && (record == movement || record == action);
	}

	public String dimension() {
		return dimension;
	}

	/**
	 * Feeds one tick of real movement.
	 *
	 * <p>Starting to move again ends the open action (a walk that follows a placement is a new
	 * independent action), but movement that is already being recorded never interrupts a running
	 * action, so mining while walking stays one activity of each kind.
	 */
	public ActivityRecord observeMovement(Sample sample) {
		int tick = sample.tick();
		if (movement != null && (tick - movement.endTick() > mergeGapTicks
				|| (lastTrace != null && distance(lastTrace, sample) >= TELEPORT_DISTANCE))) {
			closeMovement(tick);
		}
		if (movement == null) {
			closeAction(tick);
			openMovement(sample);
		}
		extendMovement(sample);
		return movement;
	}

	/**
	 * Feeds a discrete action. A new independent action ends the previous action and the walk that
	 * led to it; repeated actions of the same kind inside the merge window stay one activity.
	 */
	public ActivityRecord observeAction(ActivityKind kind, Sample sample) {
		int tick = sample.tick();
		if (action != null && action.kind().samePlayerActionAs(kind)
				&& tick - action.endTick() <= Math.max(10, kind.defaultMergeGapTicks())) {
			int previousEnd = action.endTick();
			action.setEnd(tick, System.currentTimeMillis(), pack(sample));
			action.increment(ActivityStat.TICKS, Math.max(0, tick - previousEnd));
			if (tick - actionCheckpointTick >= checkpointTicks) {
				actionCheckpointTick = tick;
				sink.checkpointed(action);
			}
			return action;
		}
		closeAction(tick);
		closeMovement(tick);
		long id = idSource.getAsLong();
		action = new ActivityRecord(id, playerId, playerName, kind, dimension, tick, System.currentTimeMillis(),
				pack(sample));
		actionCheckpointTick = tick;
		sink.opened(action);
		return action;
	}

	/**
	 * Seals activities that ended.
	 *
	 * <p>Movement ends when the player really stopped: no displacement for the stop window and a
	 * negligible horizontal speed, so sliding or knock back does not keep a walk artificially alive,
	 * and a long stand-still is never folded into the previous walk.
	 */
	public void tick(int tick, double horizontalSpeedSqr) {
		if (movement != null && tick - movement.endTick() > mergeGapTicks && horizontalSpeedSqr < 4.0E-3) {
			closeMovement(tick);
		}
		if (action != null) {
			int gap = Math.max(5, action.kind().defaultMergeGapTicks());
			if (tick - action.endTick() > gap) {
				closeAction(tick);
			}
		}
	}

	/** Seals every open activity right now. */
	public void flush(int tick) {
		closeMovement(tick);
		closeAction(tick);
	}

	private void openMovement(Sample sample) {
		long id = idSource.getAsLong();
		movement = new ActivityRecord(id, playerId, playerName, ActivityKind.MOVE, dimension, sample.tick(),
				System.currentTimeMillis(), pack(sample));
		pending.clear();
		lastTrace = null;
		accumulatedDistance = 0.0;
		movementCheckpointTick = sample.tick();
		sink.opened(movement);
	}

	private void extendMovement(Sample sample) {
		TracePoint point = new TracePoint(sample.x(), sample.y(), sample.z(), sample.tick(), sample.sprinting(),
				sample.sneaking(), sample.onGround());
		if (lastTrace != null) {
			double step = lastTrace.distanceTo(point);
			if (step > 0.0 && step < TELEPORT_DISTANCE) {
				accumulatedDistance += step;
			}
		}
		pending.add(point);
		lastTrace = point;
		movement.setEnd(sample.tick(), System.currentTimeMillis(), pack(sample));
		movement.stats()[ActivityStat.BLOCKS_WALKED_CM.ordinal()] = (int) Math.min(Integer.MAX_VALUE,
				Math.round(accumulatedDistance * 100.0));
		movement.increment(ActivityStat.TICKS, 1);
		if (sample.tick() - movementCheckpointTick >= checkpointTicks) {
			checkpointMovement();
		}
	}

	private void checkpointMovement() {
		List<TracePoint> compressed = TraceCompressor.compress(pending, 0.08, 0.02);
		movement.trace().clear();
		movement.trace().addAll(compressed);
		// Keep only the key frames so a long walk does not grow memory with every tick.
		pending.clear();
		pending.addAll(compressed);
		lastTrace = pending.isEmpty() ? null : pending.get(pending.size() - 1);
		movementCheckpointTick = movement.endTick();
		sink.checkpointed(movement);
	}

	private void closeMovement(int tick) {
		if (movement == null) {
			return;
		}
		List<TracePoint> compressed = TraceCompressor.compress(pending, 0.08, 0.02);
		movement.trace().clear();
		movement.trace().addAll(compressed);
		movement.setEnd(Math.max(movement.endTick(), tick), System.currentTimeMillis(), movement.endPacked());
		ActivityRecord closing = movement;
		movement = null;
		pending.clear();
		lastTrace = null;
		accumulatedDistance = 0.0;
		sink.sealed(closing);
	}

	private void closeAction(int tick) {
		if (action == null) {
			return;
		}
		action.setEnd(Math.max(action.endTick(), tick), System.currentTimeMillis(), action.endPacked());
		ActivityRecord closing = action;
		action = null;
		lastSealedAction = closing;
		lastSealedActionTick = tick;
		sink.sealed(closing);
	}

	private static double distance(TracePoint a, Sample b) {
		double dx = a.x - b.x();
		double dy = a.y - b.y();
		double dz = a.z - b.z();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static long pack(Sample sample) {
		return ByteIO.packBlockPos((int) Math.floor(sample.x()), (int) Math.floor(sample.y()),
				(int) Math.floor(sample.z()));
	}
}
