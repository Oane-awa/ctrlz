package com.ctrlz.core.rollback;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simulated state of every object an undo chain touches.
 *
 * <p>Keys are opaque strings (see {@link RollbackPreflight#keyOf}); values are the serialized state
 * exactly as it was stored in a consequence record. The first value of a key is read from the live
 * world, every later value comes from the simulation itself, which is what lets the preflight
 * validate an arbitrarily long chain without writing a single block.
 */
public final class VirtualState {
	private final Map<String, byte[]> states = new LinkedHashMap<>();
	private final Map<String, byte[]> originals = new LinkedHashMap<>();

	public boolean contains(String key) {
		return states.containsKey(key);
	}

	public byte[] get(String key) {
		return states.get(key);
	}

	/** The value this key had when the simulation started, i.e. the real world before the undo. */
	public byte[] original(String key) {
		return originals.get(key);
	}

	public void put(String key, byte[] value) {
		byte[] copy = value == null ? new byte[0] : value.clone();
		originals.putIfAbsent(key, copy);
		states.put(key, copy);
	}

	public Map<String, byte[]> states() {
		return Collections.unmodifiableMap(states);
	}
}
