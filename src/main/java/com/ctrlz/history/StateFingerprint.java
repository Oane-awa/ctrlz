package com.ctrlz.history;

/**
 * A comparable digest of a piece of world state.
 *
 * <p>Snapshots store the state <em>before</em> an operation; the fingerprint stored alongside them
 * is the state the operation left behind. Undo compares the fingerprint with the live world to
 * detect that somebody else changed the area in the meantime.
 */
public record StateFingerprint(String value) {
	public static final StateFingerprint EMPTY = new StateFingerprint("");

	public static StateFingerprint of(String... parts) {
		StringBuilder builder = new StringBuilder();
		for (String part : parts) {
			if (builder.length() > 0) {
				builder.append('|');
			}
			builder.append(part == null ? "-" : part);
		}
		return new StateFingerprint(builder.toString());
	}

	public boolean isEmpty() {
		return value.isEmpty();
	}

	public boolean matches(StateFingerprint other) {
		return other != null && value.equals(other.value);
	}

	@Override
	public String toString() {
		return value;
	}
}
