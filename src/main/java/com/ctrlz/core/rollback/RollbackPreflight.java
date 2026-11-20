package com.ctrlz.core.rollback;

import com.ctrlz.core.consequence.ConsequenceKind;
import com.ctrlz.core.consequence.ConsequenceRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole chain dry run of a restore (AUDIT-004).
 *
 * <p>The old preflight validated every operation against the <em>live</em> world before writing
 * anything. That is wrong as soon as two operations touch the same block or slot: the older
 * operation's recorded "after" state has already been replaced by the newer one, so a perfectly
 * normal sequence was reported as a conflict and the restore was refused.
 *
 * <p>This class instead walks the chain newest first and keeps a virtual copy of every touched
 * state ({@link VirtualState}). A state is compared against the recorded "after" value of the
 * current step, then replaced by that step's "before" value, so the next (older) step is compared
 * against what the chain will actually look like at that moment. Nothing here writes to the world
 * and nothing here needs a game instance, which keeps the algorithm unit testable.
 */
public final class RollbackPreflight {

	/**
	 * Read-only access to the live world, implemented by the platform layer. Implementations must
	 * never mutate anything: they run inside the preflight.
	 */
	public interface StateReader {
		/** Serialized current state of the object behind {@code key}, or null when unreadable. */
		byte[] readState(String key, ConsequenceRecord change);

		/** Whether the object behind {@code key} exists at all (entities, players). */
		boolean stateExists(String key, ConsequenceRecord change);
	}

	/** One activity's consequences, already collapsed, in recording order. */
	public record Batch(long activityId, List<ConsequenceRecord> consequences) {
	}

	/** Where the simulation stopped and what it saw. */
	public record Failure(long activityId, ConsequenceRecord change, ConflictType type, byte[] actual) {
	}

	/**
	 * Result of a successful simulation: {@code expected} holds the serialized state every
	 * verifiable object must have once the plan was applied, keyed by {@link #keyOf}.
	 */
	public record Outcome(Map<String, byte[]> expected, Failure failure) {
		public boolean ok() {
			return failure == null;
		}

		public static Outcome failed(Failure failure) {
			return new Outcome(Map.of(), failure);
		}
	}

	private static final byte[] PRESENT = { 1 };
	private static final byte[] ABSENT = { 0 };

	private RollbackPreflight() {
	}

	/**
	 * Stable identity of the object a consequence changes.
	 *
	 * <p>Block state and block entity data are separate keys because they are separate objects that
	 * happen to share a position: the block state has to go back first, the payload is loaded into
	 * the restored block entity afterwards.
	 */
	public static String keyOf(ConsequenceRecord change) {
		if (change == null) {
			return null;
		}
		return switch (change.kind()) {
			case BLOCK_STATE -> "block|" + change.dimension() + "|" + change.packedPos();
			case BLOCK_ENTITY_DATA -> "be|" + change.dimension() + "|" + change.packedPos();
			case CONTAINER_SLOTS, INVENTORY_SLOTS ->
					"slot|" + change.dimension() + "|" + change.packedPos() + "|" + change.subject();
			case ITEM_ENTITY_SPAWN -> "entity|" + change.subject();
			case ENTITY_HEALTH -> "health|" + change.subject();
			case ENTITY_EFFECTS -> "effects|" + change.subject();
			case PLAYER_STATE -> "player|" + change.subject();
			default -> null;
		};
	}

	/** Kinds whose value can be compared byte for byte (and therefore verified after applying). */
	public static boolean strictlyComparable(ConsequenceKind kind) {
		return kind == ConsequenceKind.BLOCK_STATE || kind == ConsequenceKind.CONTAINER_SLOTS
				|| kind == ConsequenceKind.INVENTORY_SLOTS;
	}

	/**
	 * Kinds that are only checked for existence. Health, effects and player position change every
	 * tick on their own (regeneration, effect timers), so comparing their values would report
	 * conflicts that no player caused; their existence is still a hard requirement.
	 */
	public static boolean existenceOnly(ConsequenceKind kind) {
		return kind == ConsequenceKind.ENTITY_HEALTH || kind == ConsequenceKind.ENTITY_EFFECTS
				|| kind == ConsequenceKind.PLAYER_STATE;
	}

	/**
	 * Simulates the whole chain newest first.
	 *
	 * @param newestFirst the activities to undo, newest activity first
	 */
	public static Outcome simulate(List<Batch> newestFirst, StateReader reader) {
		VirtualState virtual = new VirtualState();
		Map<String, byte[]> expected = new LinkedHashMap<>();
		for (Batch batch : newestFirst) {
			// Within one activity the consequences are inverted in reverse recording order, exactly
			// like RollbackEngine does when it applies them.
			for (ConsequenceRecord change : RollbackEngine.orderForRestore(batch.consequences())) {
				if (!change.origin().isAttributable()) {
					continue;
				}
				String key = keyOf(change);
				if (key == null) {
					continue;
				}
				Failure failure = step(batch.activityId(), change, key, virtual, reader);
				if (failure != null) {
					return Outcome.failed(failure);
				}
				if (strictlyComparable(change.kind())) {
					expected.put(key, virtual.get(key));
				} else if (change.kind() == ConsequenceKind.ITEM_ENTITY_SPAWN) {
					expected.put(key, change.before().length == 0 ? ABSENT : PRESENT);
				}
			}
		}
		return new Outcome(Collections.unmodifiableMap(expected), null);
	}

	/** One reverse step: compare, then move the virtual state back to the recorded before value. */
	private static Failure step(long activityId, ConsequenceRecord change, String key, VirtualState virtual,
			StateReader reader) {
		ConsequenceKind kind = change.kind();
		if (existenceOnly(kind)) {
			if (!reader.stateExists(key, change)) {
				return new Failure(activityId, change, ConflictType.TARGET_MISSING, null);
			}
			return null;
		}
		if (kind == ConsequenceKind.ITEM_ENTITY_SPAWN) {
			boolean tracked = virtual.contains(key);
			boolean present;
			byte[] actual = null;
			if (tracked) {
				present = Arrays.equals(virtual.get(key), PRESENT);
			} else {
				present = reader.stateExists(key, change);
				if (present) {
					actual = reader.readState(key, change);
				}
			}
			if (change.after().length == 0) {
				// A pickup: undoing it re-creates the drop, so there is no live value to compare.
				virtual.put(key, change.before().length == 0 ? ABSENT : PRESENT);
				return null;
			}
			if (!present) {
				return new Failure(activityId, change, ConflictType.TARGET_MISSING, null);
			}
			if (!tracked) {
				// The live drop must still be the one this activity produced; a stack that was merged
				// or partly picked up is not the same object any more.
				if (actual == null) {
					return new Failure(activityId, change, ConflictType.TARGET_MISSING, null);
				}
				if (!Arrays.equals(actual, change.after())) {
					return new Failure(activityId, change, ConflictType.ITEM_MISMATCH, actual);
				}
			}
			virtual.put(key, PRESENT);
			return null;
		}
		if (!strictlyComparable(kind)) {
			// Block entity payload: its recorded "after" is empty by design, so only the block state
			// is comparable. Still keep the payload so the key is known to be touched.
			virtual.put(key, change.before());
			return null;
		}
		byte[] current = virtual.contains(key) ? virtual.get(key) : reader.readState(key, change);
		if (current == null) {
			return new Failure(activityId, change, ConflictType.TARGET_MISSING, null);
		}
		if (!Arrays.equals(current, change.after())) {
			ConflictType type = kind == ConsequenceKind.BLOCK_STATE ? ConflictType.STATE_MISMATCH
					: ConflictType.ITEM_MISMATCH;
			return new Failure(activityId, change, type, current);
		}
		virtual.put(key, change.before());
		return null;
	}

	/** Builds the batch list from activity ids, keeping the caller free of the batch record. */
	public static List<Batch> batches(List<Long> activityIds,
			java.util.function.LongFunction<List<ConsequenceRecord>> source) {
		List<Batch> batches = new ArrayList<>(activityIds.size());
		for (Long id : activityIds) {
			batches.add(new Batch(id, source.apply(id)));
		}
		return batches;
	}
}
