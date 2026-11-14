package com.ctrlz.core.util;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Compact binary encoding helpers shared by every on-disk structure.
 *
 * <p>Storage size is a first class concern for this mod, so all integer fields are
 * variable length encoded and block positions are packed into a single long whenever
 * they fit into the vanilla build limits.
 */
public final class ByteIO {
	private ByteIO() {
	}

	public static void writeVarInt(DataOutput out, int value) throws IOException {
		while ((value & ~0x7F) != 0) {
			out.writeByte((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.writeByte(value);
	}

	public static int readVarInt(DataInput in) throws IOException {
		int result = 0;
		int shift = 0;
		while (true) {
			int b = in.readUnsignedByte();
			result |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				return result;
			}
			shift += 7;
			if (shift > 35) {
				throw new IOException("VarInt too long");
			}
		}
	}

	public static void writeVarLong(DataOutput out, long value) throws IOException {
		while ((value & ~0x7FL) != 0) {
			out.writeByte((int) (value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.writeByte((int) value);
	}

	public static long readVarLong(DataInput in) throws IOException {
		long result = 0;
		int shift = 0;
		while (true) {
			int b = in.readUnsignedByte();
			result |= (long) (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				return result;
			}
			shift += 7;
			if (shift > 70) {
				throw new IOException("VarLong too long");
			}
		}
	}

	public static void writeZigZag(DataOutput out, int value) throws IOException {
		writeVarInt(out, (value << 1) ^ (value >> 31));
	}

	public static int readZigZag(DataInput in) throws IOException {
		int raw = readVarInt(in);
		return (raw >>> 1) ^ -(raw & 1);
	}

	public static void writeZigZagLong(DataOutput out, long value) throws IOException {
		writeVarLong(out, (value << 1) ^ (value >> 63));
	}

	public static long readZigZagLong(DataInput in) throws IOException {
		long raw = readVarLong(in);
		return (raw >>> 1) ^ -(raw & 1);
	}

	public static void writeString(DataOutput out, String value) throws IOException {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		writeVarInt(out, bytes.length);
		out.write(bytes);
	}

	public static String readString(DataInput in) throws IOException {
		int len = readVarInt(in);
		if (len < 0 || len > 8 * 1024 * 1024) {
			throw new IOException("Illegal string length " + len);
		}
		byte[] bytes = new byte[len];
		in.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	public static void writeByteArray(DataOutput out, byte[] bytes) throws IOException {
		writeVarInt(out, bytes.length);
		out.write(bytes);
	}

	public static byte[] readByteArray(DataInput in) throws IOException {
		int len = readVarInt(in);
		if (len < 0 || len > 256 * 1024 * 1024) {
			throw new IOException("Illegal byte array length " + len);
		}
		byte[] bytes = new byte[len];
		in.readFully(bytes);
		return bytes;
	}

	public static void writeUuid(DataOutput out, UUID uuid) throws IOException {
		out.writeLong(uuid.getMostSignificantBits());
		out.writeLong(uuid.getLeastSignificantBits());
	}

	public static UUID readUuid(DataInput in) throws IOException {
		return new UUID(in.readLong(), in.readLong());
	}

	/**
	 * Packs a block position into a single long (x/z 26 bits, y 12 bits) which covers every
	 * coordinate a vanilla world can produce while saving 19 bytes per stored position.
	 */
	public static long packBlockPos(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
	}

	public static int unpackX(long packed) {
		return (int) (packed >> 38);
	}

	public static int unpackY(long packed) {
		return (int) (packed << 52 >> 52);
	}

	public static int unpackZ(long packed) {
		return (int) (packed << 26 >> 38);
	}

	/**
	 * Writes a time ordered delta encoded scalar list. Values are expected to be monotonic,
	 * so only the (usually tiny) delta is stored.
	 */
	public static void writeMonotonic(DataOutput out, long[] sortedValues) throws IOException {
		writeVarInt(out, sortedValues.length);
		long previous = 0L;
		for (long value : sortedValues) {
			writeVarLong(out, value - previous);
			previous = value;
		}
	}

	public static long[] readMonotonic(DataInput in) throws IOException {
		int size = readVarInt(in);
		long[] result = new long[size];
		long previous = 0L;
		for (int i = 0; i < size; i++) {
			previous += readVarLong(in);
			result[i] = previous;
		}
		return result;
	}
}
