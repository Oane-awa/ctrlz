package com.ctrlz.core.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * Reads a {@link JournalWriter} segment and repairs the crash tail.
 *
 * <p>Recovery rules: an incomplete frame, a bad checksum or an unknown record type stops the
 * scan. Everything before that point is kept and the file is truncated to the last good
 * boundary, so a power loss can never produce a half applied activity graph.
 */
public final class JournalReader {
	private JournalReader() {
	}

	public static final class Result {
		public final List<Record> records;
		public final long[] offsets;
		public final int formatVersion;
		public final long validBytes;
		public final boolean truncated;
		public final String failure;

		Result(List<Record> records, long[] offsets, int formatVersion, long validBytes, boolean truncated,
				String failure) {
			this.records = records;
			this.offsets = offsets;
			this.formatVersion = formatVersion;
			this.validBytes = validBytes;
			this.truncated = truncated;
			this.failure = failure;
		}
	}

	public static Result read(Path path) throws IOException {
		List<Record> records = new ArrayList<>();
		List<Long> offsets = new ArrayList<>();
		if (!java.nio.file.Files.exists(path)) {
			return new Result(records, new long[0], JournalWriter.FORMAT_VERSION, 0L, false, null);
		}
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			long size = channel.size();
			if (size < 4) {
				return new Result(records, new long[0], JournalWriter.FORMAT_VERSION, 0L, size > 0, "header missing");
			}
			int headerLength = computeHeaderLength(channel, size);
			ByteBuffer header = ByteBuffer.allocate(headerLength);
			channel.read(header, 0L);
			header.flip();
			int magic = header.getInt();
			if (magic != JournalWriter.MAGIC) {
				return new Result(records, new long[0], 0, 0L, true, "bad magic");
			}
			int formatVersion = readVarInt(header);
			long position = headerLength;
			while (position + 8 <= size) {
				ByteBuffer lengthBuffer = ByteBuffer.allocate(8);
				channel.read(lengthBuffer, position);
				lengthBuffer.flip();
				int bodyLength = lengthBuffer.getInt();
				int expectedCrc = lengthBuffer.getInt();
				if (bodyLength <= 0 || position + 8 + bodyLength > size) {
					return truncate(channel, position, records, offsets, formatVersion, "partial record");
				}
				ByteBuffer body = ByteBuffer.allocate(bodyLength);
				channel.read(body, position + 8);
				body.flip();
				byte[] raw = new byte[bodyLength];
				body.get(raw);
				CRC32C crc = new CRC32C();
				crc.update(raw, 0, raw.length);
				if ((int) crc.getValue() != expectedCrc) {
					return truncate(channel, position, records, offsets, formatVersion, "checksum mismatch");
				}
				RecordType type = RecordType.byId(raw[0] & 0xFF);
				if (type == null) {
					return truncate(channel, position, records, offsets, formatVersion,
							"unknown record type " + (raw[0] & 0xFF));
				}
				byte[] payload = new byte[bodyLength - 1];
				System.arraycopy(raw, 1, payload, 0, payload.length);
				records.add(new Record(type, payload));
				offsets.add(position);
				position += 8 + bodyLength;
			}
			return new Result(records, toArray(offsets), formatVersion, position, position != size,
					position != size ? "trailing bytes" : null);
		}
	}

	private static long[] toArray(List<Long> values) {
		long[] result = new long[values.size()];
		for (int i = 0; i < result.length; i++) {
			result[i] = values.get(i);
		}
		return result;
	}

	/** Reads a single framed record at an absolute offset. Returns null when the frame is unreadable. */
	public static Record readAt(Path path, long offset) throws IOException {
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
			ByteBuffer lengthBuffer = ByteBuffer.allocate(8);
			if (channel.read(lengthBuffer, offset) != 8) {
				return null;
			}
			lengthBuffer.flip();
			int bodyLength = lengthBuffer.getInt();
			int expectedCrc = lengthBuffer.getInt();
			if (bodyLength <= 0 || bodyLength > 64 * 1024 * 1024) {
				return null;
			}
			ByteBuffer body = ByteBuffer.allocate(bodyLength);
			if (channel.read(body, offset + 8) != bodyLength) {
				return null;
			}
			body.flip();
			byte[] raw = new byte[bodyLength];
			body.get(raw);
			CRC32C crc = new CRC32C();
			crc.update(raw, 0, raw.length);
			if ((int) crc.getValue() != expectedCrc) {
				return null;
			}
			RecordType type = RecordType.byId(raw[0] & 0xFF);
			if (type == null) {
				return null;
			}
			byte[] payload = new byte[bodyLength - 1];
			System.arraycopy(raw, 1, payload, 0, payload.length);
			return new Record(type, payload);
		}
	}

	private static int readVarInt(ByteBuffer buffer) {
		int result = 0;
		int shift = 0;
		while (true) {
			int b = buffer.get() & 0xFF;
			result |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				return result;
			}
			shift += 7;
			if (shift > 35) {
				throw new IllegalStateException("VarInt too long in header");
			}
		}
	}

	private static int computeHeaderLength(FileChannel channel, long size) throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(size, 64));
		channel.read(buffer, 0L);
		buffer.flip();
		buffer.getInt();
		int length = 4;
		while (buffer.hasRemaining()) {
			int b = buffer.get() & 0xFF;
			length++;
			if ((b & 0x80) == 0) {
				break;
			}
		}
		length += 8;
		return length;
	}

	private static Result truncate(FileChannel channel, long position, List<Record> records, List<Long> offsets,
			int formatVersion, String reason) throws IOException {
		channel.truncate(position);
		channel.force(true);
		return new Result(records, toArray(offsets), formatVersion, position, true, reason);
	}
}
