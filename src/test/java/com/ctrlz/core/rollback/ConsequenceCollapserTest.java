package com.ctrlz.core.rollback;

import com.ctrlz.core.consequence.ConsequenceKind;
import com.ctrlz.core.consequence.ConsequenceOrigin;
import com.ctrlz.core.consequence.ConsequenceRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsequenceCollapserTest {
	private static ConsequenceRecord block(long id, int tick, long pos, String subject, String before, String after) {
		return new ConsequenceRecord(id, 1L, ConsequenceKind.BLOCK_STATE, ConsequenceOrigin.DIRECT, tick,
				"minecraft:overworld", pos, subject, before.getBytes(), after.getBytes(), 0, 0L, 0L, "");
	}

	@Test
	void repeatedChangesOfOneBlockBecomeOriginalToFinal() {
		List<ConsequenceRecord> result = ConsequenceCollapser.collapse(List.of(
				block(1, 10, 5L, "minecraft:stone", "minecraft:stone", "minecraft:air"),
				block(2, 40, 5L, "minecraft:air", "minecraft:air", "minecraft:dirt")));
		assertEquals(1, result.size());
		assertArrayEquals("minecraft:stone".getBytes(), result.get(0).before());
		assertArrayEquals("minecraft:dirt".getBytes(), result.get(0).after());
	}

	@Test
	void differentBlocksStaySeparate() {
		List<ConsequenceRecord> result = ConsequenceCollapser.collapse(List.of(
				block(1, 10, 5L, "a", "a", "b"), block(2, 11, 6L, "a", "a", "b")));
		assertEquals(2, result.size());
	}

	@Test
	void slotsOfTheSameInventoryStayDistinctPerSlot() {
		ConsequenceRecord slot1 = new ConsequenceRecord(1, 1L, ConsequenceKind.INVENTORY_SLOTS,
				ConsequenceOrigin.DIRECT, 10, "d", ConsequenceRecord.NO_POSITION, "inv#x#1", new byte[] {1},
				new byte[] {2}, 0, 0L, 0L, "");
		ConsequenceRecord slot2 = new ConsequenceRecord(2, 1L, ConsequenceKind.INVENTORY_SLOTS,
				ConsequenceOrigin.DIRECT, 10, "d", ConsequenceRecord.NO_POSITION, "inv#x#2", new byte[] {1},
				new byte[] {2}, 0, 0L, 0L, "");
		assertEquals(2, ConsequenceCollapser.collapse(List.of(slot1, slot2)).size());
	}
}
