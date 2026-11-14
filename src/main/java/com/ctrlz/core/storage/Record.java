package com.ctrlz.core.storage;

import com.ctrlz.core.util.ByteIO;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.zip.CRC32C;

/**
 * A single durability unit inside the journal files.
 *
 * <p>Every record is self describing and checksummed so a truncated or corrupted tail can be
 * dropped on load without losing the rest of the history.
 */
public final class Record {
	private static final int HEADER_BYTES = 9;

	private final RecordType type;
	private final byte[] payload;

	public Record(RecordType type, byte[] payload) {
		this.type = type;
		this.payload = payload;
	}

	public RecordType type() {
		return type;
	}

	public byte[] payload() {
		return payload;
	}

	public byte[] frame() {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream(payload.length + HEADER_BYTES);
		try (DataOutputStream out = new DataOutputStream(buffer)) {
			out.writeByte(type.id());
			out.write(payload);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		byte[] body = buffer.toByteArray();
		CRC32C crc = new CRC32C();
		crc.update(body, 0, body.length);
		byte[] result = new byte[body.length + 8];
		int length = body.length;
		result[0] = (byte) (length >>> 24);
		result[1] = (byte) (length >>> 16);
		result[2] = (byte) (length >>> 8);
		result[3] = (byte) length;
		long value = crc.getValue();
		result[4] = (byte) (value >>> 24);
		result[5] = (byte) (value >>> 16);
		result[6] = (byte) (value >>> 8);
		result[7] = (byte) value;
		System.arraycopy(body, 0, result, 8, body.length);
		return result;
	}

	public static final class Builder {
		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
		private final DataOutputStream out = new DataOutputStream(buffer);

		public Builder writeInt(int value) {
			try {
				ByteIO.writeVarInt(out, value);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return this;
		}

		public Builder writeLong(long value) {
			try {
				ByteIO.writeVarLong(out, value);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return this;
		}

		public Builder writeRawLong(long value) {
			try {
				out.writeLong(value);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return this;
		}

		public Builder writeZigZag(int value) {
			try {
				ByteIO.writeZigZag(out, value);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return this;
		}

		public Builder writeString(String value) {
			try {
				ByteIO.writeString(out, value);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return this;
		}

		public Builder writeBytes(byte[] value) {
			try {
				ByteIO.writeByteArray(out, value);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return this;
		}

		public Builder writeUuid(UUID value) {
			try {
				ByteIO.writeUuid(out, value);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return this;
		}

		public Builder writeBoolean(boolean value) {
			try {
				out.writeBoolean(value);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return this;
		}

		public Record build(RecordType type) {
			try {
				out.flush();
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return new Record(type, buffer.toByteArray());
		}
	}
}
