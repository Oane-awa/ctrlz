package com.ctrlz.core.storage;

import com.ctrlz.core.util.ByteIO;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Append only, checksummed journal segment.
 *
 * <p>Writes are buffered in memory and flushed on demand; the file always ends on a record
 * boundary because a partial frame is never committed to the channel.
 */
public final class JournalWriter implements AutoCloseable {
	public static final int MAGIC = 0x43545A31;
	public static final int FORMAT_VERSION = 1;

	private final Path path;
	private final FileChannel channel;
	private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
	private long writtenBytes;
	private int recordCount;

	private JournalWriter(Path path, FileChannel channel) {
		this.path = path;
		this.channel = channel;
	}

	public static JournalWriter open(Path path) throws IOException {
		Files.createDirectories(path.getParent());
		boolean fresh = !Files.exists(path) || Files.size(path) == 0L;
		FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				StandardOpenOption.APPEND);
		JournalWriter writer = new JournalWriter(path, channel);
		if (fresh) {
			ByteArrayOutputStream header = new ByteArrayOutputStream();
			try (DataOutputStream out = new DataOutputStream(header)) {
				out.writeInt(MAGIC);
				ByteIO.writeVarInt(out, FORMAT_VERSION);
				out.writeLong(System.currentTimeMillis());
			}
			byte[] bytes = header.toByteArray();
			channel.write(ByteBuffer.wrap(bytes));
			channel.force(true);
			writer.writtenBytes = bytes.length;
		} else {
			writer.writtenBytes = channel.size();
		}
		return writer;
	}

	public Path path() {
		return path;
	}

	public long sizeBytes() {
		return writtenBytes + buffer.size();
	}

	/** Byte offset at which the next appended record will be written. */
	public long position() {
		return writtenBytes + buffer.size();
	}

	public int recordCount() {
		return recordCount;
	}

	public void append(Record record) throws IOException {
		byte[] frame = record.frame();
		buffer.write(frame, 0, frame.length);
		recordCount++;
		if (buffer.size() >= 64 * 1024) {
			flush(false);
		}
	}

	public void flush(boolean fsync) throws IOException {
		if (buffer.size() == 0) {
			if (fsync) {
				channel.force(true);
			}
			return;
		}
		byte[] bytes = buffer.toByteArray();
		buffer.reset();
		ByteBuffer wrap = ByteBuffer.wrap(bytes);
		while (wrap.hasRemaining()) {
			channel.write(wrap);
		}
		writtenBytes += bytes.length;
		if (fsync) {
			channel.force(true);
		}
	}

	@Override
	public void close() throws IOException {
		try {
			flush(true);
		} finally {
			channel.close();
		}
	}
}
