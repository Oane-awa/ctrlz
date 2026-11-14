package com.ctrlz.core.trace;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceCompressorTest {
	private static TracePoint point(double x, double z, int tick, boolean sprint) {
		return new TracePoint(x, 64.0, z, tick, sprint, false, true);
	}

	@Test
	void straightWalkCollapsesToTwoKeyFrames() {
		List<TracePoint> raw = new ArrayList<>();
		for (int i = 0; i < 600; i++) {
			raw.add(point(i * 0.28, 0.0, 100 + i, false));
		}
		List<TracePoint> compressed = TraceCompressor.compress(raw, 0.08, 0.02);
		assertEquals(2, compressed.size(), "a straight walk must not keep per tick samples");
		assertEquals(100, compressed.get(0).tick);
		assertEquals(699, compressed.get(1).tick);
	}

	@Test
	void directionChangesKeepTheirCorners() {
		List<TracePoint> raw = new ArrayList<>();
		int tick = 0;
		for (int i = 0; i < 40; i++) {
			raw.add(point(i, 0, tick++, false));
		}
		for (int i = 0; i < 40; i++) {
			raw.add(point(40, i, tick++, false));
		}
		List<TracePoint> compressed = TraceCompressor.compress(raw, 0.08, 0.02);
		assertTrue(compressed.size() >= 3, "the corner of an L shaped walk must survive compression");
		boolean corner = compressed.stream().anyMatch(p -> p.x == 40.0 && p.z == 0.0);
		assertTrue(corner, "expected the turning point to be retained");
	}

	@Test
	void sprintAndSneakTransitionsAreRetained() {
		List<TracePoint> raw = new ArrayList<>();
		for (int i = 0; i < 60; i++) {
			raw.add(point(i, 0, i, i >= 30));
		}
		List<TracePoint> compressed = TraceCompressor.compress(raw, 0.08, 0.02);
		long sprintingFrames = compressed.stream().filter(p -> p.sprinting).count();
		assertTrue(sprintingFrames > 0, "state changes must keep at least one key frame");
		assertTrue(compressed.size() < raw.size(), "compression must actually reduce the sample count");
	}

	@Test
	void stationaryStretchesKeepOneKeyFrame() {
		List<TracePoint> raw = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			raw.add(point(10, 10, i, false));
		}
		List<TracePoint> compressed = TraceCompressor.compress(raw, 0.08, 0.02);
		assertEquals(1, compressed.size());
		assertEquals(199, compressed.get(0).tick, "the newest timestamp must be preserved");
	}
}
