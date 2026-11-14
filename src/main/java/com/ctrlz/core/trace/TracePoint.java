package com.ctrlz.core.trace;

/**
 * One sampled player position inside a movement activity.
 *
 * <p>Instances are mutable on purpose: the recorder reuses the last element while the
 * player keeps moving in the same direction so a straight walk allocates nothing.
 */
public final class TracePoint {
	public double x;
	public double y;
	public double z;
	public int tick;
	public boolean sprinting;
	public boolean sneaking;
	public boolean onGround;

	public TracePoint(double x, double y, double z, int tick, boolean sprinting, boolean sneaking, boolean onGround) {
		this.x = x;
		this.y = y;
		this.z = z;
		this.tick = tick;
		this.sprinting = sprinting;
		this.sneaking = sneaking;
		this.onGround = onGround;
	}

	public TracePoint copy() {
		return new TracePoint(x, y, z, tick, sprinting, sneaking, onGround);
	}

	public boolean sameFlags(TracePoint other) {
		return sprinting == other.sprinting && sneaking == other.sneaking;
	}

	public double distanceTo(TracePoint other) {
		double dx = x - other.x;
		double dy = y - other.y;
		double dz = z - other.z;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	@Override
	public String toString() {
		return String.format("(%.2f, %.2f, %.2f)@%d", x, y, z, tick);
	}
}
