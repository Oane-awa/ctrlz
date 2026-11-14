package com.ctrlz.core.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a per tick position stream into a small set of key frames.
 *
 * <p>A player that walks in a straight line for 600 ticks produces exactly two key frames
 * instead of 600 samples. Direction changes keep their corner points, and every state change
 * (sprint/sneak toggle, ground contact) is preserved because the rollback engine needs the
 * original movement intent, not just the geometric path.
 */
public final class TraceCompressor {
	private TraceCompressor() {
	}

	public static List<TracePoint> compress(List<TracePoint> input, double epsilon, double minStep) {
		if (input.isEmpty()) {
			return List.of();
		}
		List<TracePoint> filtered = new ArrayList<>(input.size());
		filtered.add(input.get(0));
		for (int i = 1; i < input.size(); i++) {
			TracePoint current = input.get(i);
			TracePoint last = filtered.get(filtered.size() - 1);
			boolean stateChange = !current.sameFlags(last) || (current.onGround != last.onGround);
			boolean moved = current.distanceTo(last) >= minStep;
			if (stateChange || moved) {
				filtered.add(current);
			} else {
				// Keep the newest timestamp for stationary stretches without growing the list.
				last.tick = current.tick;
			}
		}
		if (filtered.size() <= 2) {
			return filtered;
		}
		List<TracePoint> keep = new ArrayList<>(filtered.size());
		boolean[] retained = new boolean[filtered.size()];
		retained[0] = true;
		retained[filtered.size() - 1] = true;
		simplifyRange(filtered, 0, filtered.size() - 1, epsilon, retained);
		for (int i = 0; i < filtered.size(); i++) {
			TracePoint point = filtered.get(i);
			boolean isStateChange = i > 0 && !point.sameFlags(filtered.get(i - 1));
			if (retained[i] || isStateChange) {
				keep.add(point);
			}
		}
		return keep;
	}

	private static void simplifyRange(List<TracePoint> points, int first, int last, double epsilon, boolean[] retained) {
		if (last <= first + 1) {
			return;
		}
		TracePoint a = points.get(first);
		TracePoint b = points.get(last);
		double maxDistance = -1.0;
		int maxIndex = -1;
		for (int i = first + 1; i < last; i++) {
			double distance = perpendicularDistance(points.get(i), a, b);
			if (distance > maxDistance) {
				maxDistance = distance;
				maxIndex = i;
			}
		}
		if (maxDistance > epsilon) {
			retained[maxIndex] = true;
			simplifyRange(points, first, maxIndex, epsilon, retained);
			simplifyRange(points, maxIndex, last, epsilon, retained);
		}
	}

	private static double perpendicularDistance(TracePoint point, TracePoint a, TracePoint b) {
		double abx = b.x - a.x;
		double aby = b.y - a.y;
		double abz = b.z - a.z;
		double lengthSquared = abx * abx + aby * aby + abz * abz;
		if (lengthSquared < 1.0E-9) {
			return point.distanceTo(a);
		}
		double apx = point.x - a.x;
		double apy = point.y - a.y;
		double apz = point.z - a.z;
		double t = (apx * abx + apy * aby + apz * abz) / lengthSquared;
		if (t < 0.0) {
			t = 0.0;
		} else if (t > 1.0) {
			t = 1.0;
		}
		double dx = apx - abx * t;
		double dy = apy - aby * t;
		double dz = apz - abz * t;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	public static double pathLength(List<TracePoint> points) {
		double total = 0.0;
		for (int i = 1; i < points.size(); i++) {
			total += points.get(i - 1).distanceTo(points.get(i));
		}
		return total;
	}
}
