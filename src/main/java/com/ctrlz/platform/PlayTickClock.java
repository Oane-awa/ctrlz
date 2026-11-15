package com.ctrlz.platform;

/**
 * Monotonic play-time counter, persisted with the history so it survives a reload.
 *
 * <p>Deliberately not {@code level.getGameTime()}: a {@code /time set} would otherwise rewind the
 * retention window and resurrect expired records.
 */
public final class PlayTickClock {
	private long playTick;

	public long now() {
		return playTick;
	}

	public void tick() {
		playTick++;
	}

	public void restore(long value) {
		if (value > playTick) {
			playTick = value;
		}
	}

	public void reset() {
		playTick = 0L;
	}
}
