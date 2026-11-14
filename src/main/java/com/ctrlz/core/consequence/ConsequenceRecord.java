package com.ctrlz.core.consequence;

import com.ctrlz.core.util.ByteIO;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * A single recorded world mutation caused by an activity.
 *
 * <p>The record keeps both the original value and the value it was changed to. The original is
 * what rollback restores; the resulting value is what conflict detection checks against, which is
 * how the engine refuses to overwrite changes that somebody else made in the meantime.
 */
public final class ConsequenceRecord {
	public static final long NO_POSITION = Long.MIN_VALUE;

	private final long id;
	private final long activityId;
	private final ConsequenceKind kind;
	private final ConsequenceOrigin origin;
	private int tick;
	private final String dimension;
	private final long packedPos;
	private final String subject;
	private final byte[] before;
	private byte[] after;
	private final int depth;
	private final long parentId;
	private final long createdMillis;
	private String detail;

	private ConsequenceStatus status;

	public ConsequenceRecord(long id, long activityId, ConsequenceKind kind, ConsequenceOrigin origin, int tick,
			String dimension, long packedPos, String subject, byte[] before, byte[] after, int depth, long parentId,
			long createdMillis, String detail) {
		this.id = id;
		this.activityId = activityId;
		this.kind = kind;
		this.origin = origin;
		this.tick = tick;
		this.dimension = dimension;
		this.packedPos = packedPos;
		this.subject = subject == null ? "" : subject;
		this.before = before == null ? new byte[0] : before;
		this.after = after == null ? new byte[0] : after;
		this.depth = depth;
		this.parentId = parentId;
		this.createdMillis = createdMillis;
		this.detail = detail == null ? "" : detail;
		this.status = ConsequenceStatus.PENDING;
	}

	public long id() {
		return id;
	}

	public long activityId() {
		return activityId;
	}

	public ConsequenceKind kind() {
		return kind;
	}

	public ConsequenceOrigin origin() {
		return origin;
	}

	public int tick() {
		return tick;
	}

	public String dimension() {
		return dimension;
	}

	public long packedPos() {
		return packedPos;
	}

	public boolean hasPosition() {
		return packedPos != NO_POSITION;
	}

	public String subject() {
		return subject;
	}

	public byte[] before() {
		return before;
	}

	public byte[] after() {
		return after;
	}

	public int depth() {
		return depth;
	}

	public long parentId() {
		return parentId;
	}

	public long createdMillis() {
		return createdMillis;
	}

	public String detail() {
		return detail;
	}

	public ConsequenceStatus status() {
		return status;
	}

	public void setStatus(ConsequenceStatus status) {
		this.status = status;
	}

	/**
	 * Folds a later change of the same target into this record: the original value is kept as the
	 * restore target while the resulting value moves forward. This is what keeps a container that
	 * was clicked twenty times from producing twenty full snapshots.
	 */
	public void merge(byte[] newAfter, int newTick, String newDetail) {
		this.after = newAfter == null ? new byte[0] : newAfter;
		this.tick = Math.max(this.tick, newTick);
		if (newDetail != null && !newDetail.isEmpty()) {
			this.detail = newDetail;
		}
	}

	public byte[] encode() {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 + before.length + after.length);
		try (DataOutputStream out = new DataOutputStream(buffer)) {
			ByteIO.writeVarLong(out, id);
			ByteIO.writeVarLong(out, activityId);
			ByteIO.writeVarInt(out, kind.id());
			ByteIO.writeVarInt(out, origin.id());
			ByteIO.writeVarInt(out, tick);
			ByteIO.writeString(out, dimension);
			out.writeLong(packedPos);
			ByteIO.writeString(out, subject);
			ByteIO.writeByteArray(out, before);
			ByteIO.writeByteArray(out, after);
			ByteIO.writeVarInt(out, depth);
			ByteIO.writeVarLong(out, parentId);
			out.writeLong(createdMillis);
			ByteIO.writeString(out, detail);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		return buffer.toByteArray();
	}

	public static ConsequenceRecord decode(byte[] payload) {
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
			long id = ByteIO.readVarLong(in);
			long activityId = ByteIO.readVarLong(in);
			ConsequenceKind kind = ConsequenceKind.byId(ByteIO.readVarInt(in));
			ConsequenceOrigin origin = ConsequenceOrigin.byId(ByteIO.readVarInt(in));
			int tick = ByteIO.readVarInt(in);
			String dimension = ByteIO.readString(in);
			long packedPos = in.readLong();
			String subject = ByteIO.readString(in);
			byte[] before = ByteIO.readByteArray(in);
			byte[] after = ByteIO.readByteArray(in);
			int depth = ByteIO.readVarInt(in);
			long parentId = ByteIO.readVarLong(in);
			long createdMillis = in.readLong();
			String detail = ByteIO.readString(in);
			if (kind == null) {
				return null;
			}
			return new ConsequenceRecord(id, activityId, kind, origin, tick, dimension, packedPos, subject, before, after,
					depth, parentId, createdMillis, detail);
		} catch (IOException e) {
			return null;
		}
	}
}
