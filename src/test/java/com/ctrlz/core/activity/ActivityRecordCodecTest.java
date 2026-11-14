package com.ctrlz.core.activity;

import com.ctrlz.core.trace.TracePoint;
import com.ctrlz.core.util.ByteIO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityRecordCodecTest {
	@Test
	void roundTripKeepsTraceAndStats() {
		UUID player = UUID.randomUUID();
		ActivityRecord record = new ActivityRecord(42L, player, "Steve", ActivityKind.MOVE, "minecraft:overworld",
				100, 1_700_000_000_000L, ByteIO.packBlockPos(10, 64, -20));
		record.setEnd(160, 1_700_000_003_000L, ByteIO.packBlockPos(14, 64, -18));
		record.increment(ActivityStat.BLOCKS_WALKED_CM, 1234);
		record.increment(ActivityStat.TICKS, 60);
		record.setConsequenceCount(3);
		record.trace().addAll(List.of(
				new TracePoint(10.0, 64.0, -20.0, 100, false, false, true),
				new TracePoint(12.5, 64.0, -19.0, 130, true, false, true),
				new TracePoint(14.0, 64.0, -18.0, 160, true, true, false)));

		byte[] encoded = record.encode(true);
		ActivityRecord decoded = ActivityRecord.decode(encoded, true);

		assertNotNull(decoded);
		assertEquals(42L, decoded.id());
		assertEquals(player, decoded.playerId());
		assertEquals("Steve", decoded.playerName());
		assertEquals(ActivityKind.MOVE, decoded.kind());
		assertEquals(100, decoded.startTick());
		assertEquals(160, decoded.endTick());
		assertEquals(3, decoded.consequenceCount());
		assertEquals(3, decoded.trace().size());
		assertEquals(12.5, decoded.trace().get(1).x, 0.001);
		assertTrue(decoded.trace().get(1).sprinting);
		assertTrue(decoded.trace().get(2).sneaking);
		assertEquals(1234, decoded.stats()[ActivityStat.BLOCKS_WALKED_CM.ordinal()]);
	}

	@Test
	void summaryEncodingDropsTrace() {
		ActivityRecord record = new ActivityRecord(7L, UUID.randomUUID(), "Alex", ActivityKind.ATTACK,
				"minecraft:overworld", 0, 0L, 0L);
		record.trace().add(new TracePoint(1, 2, 3, 0, false, false, true));
		byte[] encoded = record.encode(false);
		ActivityRecord decoded = ActivityRecord.decode(encoded, false);
		assertNotNull(decoded);
		assertTrue(decoded.trace().isEmpty());
	}

	@Test
	void packedPositionsRoundTrip() {
		long packed = ByteIO.packBlockPos(-2_000_000, -63, 2_000_000);
		assertEquals(-2_000_000, ByteIO.unpackX(packed));
		assertEquals(-63, ByteIO.unpackY(packed));
		assertEquals(2_000_000, ByteIO.unpackZ(packed));
	}
}
