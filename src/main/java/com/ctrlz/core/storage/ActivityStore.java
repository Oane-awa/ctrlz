package com.ctrlz.core.storage;

import com.ctrlz.core.activity.ActivityRecord;
import com.ctrlz.core.activity.ActivityStatus;
import com.ctrlz.core.consequence.ConsequenceRecord;
import com.ctrlz.core.consequence.ConsequenceStatus;
import com.ctrlz.core.util.ByteIO;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Durable, segmented storage for activities and their consequences.
 *
 * <p>Activity metadata/traces live in {@code activities/} segments, consequence state lives in
 * {@code consequences/} segments. Both are append only journals, both are checksummed, and they
 * are linked purely by activity id, which is what keeps recording cheap while keeping restore data
 * independently manageable.
 */
public final class ActivityStore implements AutoCloseable {
	public static final String ACTIVITY_DIR = "activities";
	public static final String CONSEQUENCE_DIR = "consequences";

	/** Storage policy coming from the mod config. */
	public record Limits(int maxActivities, long maxTotalBytes, long segmentBytes, int checkpointIntervalTicks) {
		public static Limits defaults() {
			return new Limits(5000, 128L * 1024L * 1024L, 8L * 1024L * 1024L, 200);
		}
	}

	private final Path root;
	private Limits limits;
	private final Map<Long, ActivityRecord> activities = new LinkedHashMap<>();
	private final Map<Long, List<long[]>> consequenceRefs = new HashMap<>();
	private final Map<Long, ConsequenceStatus> consequenceStatus = new HashMap<>();
	private final Map<Long, Integer> activitySegment = new HashMap<>();
	private final Map<Long, Integer> consequenceSegmentOfActivity = new HashMap<>();
	private JournalWriter activityWriter;
	private JournalWriter consequenceWriter;
	private int segmentIndex;
	private boolean readOnly;
	private long totalBytes;
	private long nextActivityId;
	private long nextConsequenceId;
	private long droppedActivities;
	private long droppedBytes;

	public ActivityStore(Path root, Limits limits) {
		this.root = root;
		this.limits = limits;
	}

	public void setLimits(Limits limits) {
		this.limits = limits;
	}

	public Limits limits() {
		return limits;
	}

	public long nextActivityId() {
		return nextActivityId;
	}

	public long allocateActivityId() {
		return ++nextActivityId;
	}

	public void open() throws IOException {
		Files.createDirectories(activityDir());
		Files.createDirectories(consequenceDir());
		loadSegments();
		recoverDanglingActivities();
		pruneToLimits();
		segmentIndex = highestSegmentIndex() + 1;
		activityWriter = JournalWriter.open(segmentPath(activityDir(), segmentIndex));
		consequenceWriter = JournalWriter.open(segmentPath(consequenceDir(), segmentIndex));
		readOnly = false;
	}

	private void loadSegments() throws IOException {
		for (int index : segmentIndexes(activityDir())) {
			JournalReader.Result result = JournalReader.read(segmentPath(activityDir(), index));
			replay(result, index, true);
			totalBytes += result.validBytes;
		}
		for (int index : segmentIndexes(consequenceDir())) {
			JournalReader.Result result = JournalReader.read(segmentPath(consequenceDir(), index));
			replay(result, index, false);
			totalBytes += result.validBytes;
		}
	}

	private void replay(JournalReader.Result result, int index, boolean activityJournal) {
		for (int i = 0; i < result.records.size(); i++) {
			Record record = result.records.get(i);
			long offset = result.offsets[i];
			switch (record.type()) {
				case ACTIVITY_BEGIN, ACTIVITY_CHECKPOINT, ACTIVITY_END, ACTIVITY_STATUS -> {
					ActivityRecord decoded = ActivityRecord.decode(record.payload(),
							record.type() == RecordType.ACTIVITY_CHECKPOINT);
					if (decoded == null) {
						continue;
					}
					ActivityRecord existing = activities.get(decoded.id());
					if (existing == null || record.type() == RecordType.ACTIVITY_CHECKPOINT) {
						activities.put(decoded.id(), decoded);
						activitySegment.put(decoded.id(), index);
					} else {
						existing.setEnd(decoded.endTick(), decoded.endMillis(), decoded.endPacked());
						for (int s = 0; s < ActivityStatCount(); s++) {
							existing.stats()[s] = decoded.stats()[s];
						}
						existing.setConsequenceCount(decoded.consequenceCount());
						existing.setStatus(decoded.status(), decoded.statusReason());
						if (record.type() == RecordType.ACTIVITY_END && decoded.status() == ActivityStatus.SEALED) {
							existing.setStatus(ActivityStatus.SEALED, decoded.statusReason());
						}
					}
					activities.get(decoded.id()).clearDirty();
					nextActivityId = Math.max(nextActivityId, decoded.id());
				}
				case ACTIVITY_DELETE -> {
					try (DataInputStream in = new DataInputStream(
							new ByteArrayInputStream(record.payload()))) {
						long id = ByteIO.readVarLong(in);
						activities.remove(id);
						consequenceRefs.remove(id);
					} catch (IOException ignored) {
					}
				}
				case CONSEQUENCE -> {
					ConsequenceRecord consequence = ConsequenceRecord.decode(record.payload());
					if (consequence == null) {
						continue;
					}
					consequenceRefs.computeIfAbsent(consequence.activityId(), key -> new ArrayList<>())
							.add(new long[] {index, offset});
					consequenceSegmentOfActivity.put(consequence.activityId(), index);
					ActivityRecord owner = activities.get(consequence.activityId());
					if (owner != null && owner.consequenceCount() == 0) {
						owner.setConsequenceCount(1);
					}
					nextConsequenceId = Math.max(nextConsequenceId, consequence.id());
				}
				case CONSEQUENCE_STATUS -> {
					try (DataInputStream in = new DataInputStream(
							new ByteArrayInputStream(record.payload()))) {
						long id = ByteIO.readVarLong(in);
						ConsequenceStatus status = ConsequenceStatus.byId(ByteIO.readVarInt(in));
						consequenceStatus.put(id, status);
					} catch (IOException ignored) {
					}
				}
				default -> {
				}
			}
		}
	}

	private static int ActivityStatCount() {
		return com.ctrlz.core.activity.ActivityStat.COUNT;
	}

	private void recoverDanglingActivities() {
		for (ActivityRecord record : activities.values()) {
			if (record.status() == ActivityStatus.RECORDING) {
				record.setStatus(ActivityStatus.INCOMPLETE, "ctrlz.reason.crash");
				record.clearDirty();
			}
		}
	}

	public void begin(ActivityRecord record) throws IOException {
		activityWriter.append(new Record(RecordType.ACTIVITY_BEGIN, record.encode(false)));
		activities.put(record.id(), record);
		activitySegment.put(record.id(), segmentIndex);
		record.clearDirty();
	}

	public void checkpoint(ActivityRecord record) throws IOException {
		activityWriter.append(new Record(RecordType.ACTIVITY_CHECKPOINT, record.encode(true)));
		record.clearDirty();
		rotateIfNeeded();
	}

	public void seal(ActivityRecord record) throws IOException {
		record.setStatus(ActivityStatus.SEALED, "");
		activityWriter.append(new Record(RecordType.ACTIVITY_END, record.encode(false)));
		record.clearDirty();
		rotateIfNeeded();
	}

	public void updateStatus(ActivityRecord record) throws IOException {
		activityWriter.append(new Record(RecordType.ACTIVITY_STATUS, record.encode(false)));
		record.clearDirty();
	}

	public void appendConsequence(ConsequenceRecord consequence) throws IOException {
		long offset = consequenceWriter.position();
		consequenceWriter.append(new Record(RecordType.CONSEQUENCE, consequence.encode()));
		consequenceRefs.computeIfAbsent(consequence.activityId(), key -> new ArrayList<>())
				.add(new long[] {segmentIndex, offset});
		consequenceSegmentOfActivity.put(consequence.activityId(), segmentIndex);
		rotateIfNeeded();
	}

	public void appendConsequenceStatus(long consequenceId, ConsequenceStatus status) throws IOException {
		consequenceStatus.put(consequenceId, status);
		Record.Builder builder = new Record.Builder();
		builder.writeLong(consequenceId);
		builder.writeInt(status.id());
		consequenceWriter.append(builder.build(RecordType.CONSEQUENCE_STATUS));
		rotateIfNeeded();
	}

	public long allocateConsequenceId() {
		return ++nextConsequenceId;
	}

	public ActivityRecord byId(long id) {
		return activities.get(id);
	}

	public List<ActivityRecord> recentActivities(UUID playerId, int limit) {
		List<ActivityRecord> result = new ArrayList<>();
		List<ActivityRecord> all = new ArrayList<>(activities.values());
		for (int i = all.size() - 1; i >= 0 && result.size() < limit; i--) {
			ActivityRecord record = all.get(i);
			if (playerId == null || playerId.equals(record.playerId())) {
				result.add(record);
			}
		}
		return result;
	}

	public List<ActivityRecord> allActivities() {
		return new ArrayList<>(activities.values());
	}

	public int activityCount() {
		return activities.size();
	}

	public long totalBytes() {
		return totalBytes;
	}

	public long droppedActivities() {
		return droppedActivities;
	}

	public int consequenceCount(long activityId) {
		List<long[]> refs = consequenceRefs.get(activityId);
		return refs == null ? 0 : refs.size();
	}

	public List<ConsequenceRecord> consequences(long activityId) {
		List<long[]> refs = consequenceRefs.get(activityId);
		if (refs == null || refs.isEmpty()) {
			return List.of();
		}
		// Records are read back from the files, so whatever is still buffered must be written first;
		// otherwise the newest consequences of an activity would silently be missing.
		try {
			if (consequenceWriter != null) {
				consequenceWriter.flush(false);
			}
		} catch (IOException ignored) {
		}
		List<ConsequenceRecord> result = new ArrayList<>(refs.size());
		Path consequencePath = null;
		int lastSegment = -1;
		for (long[] ref : refs) {
			int segment = (int) ref[0];
			if (segment != lastSegment) {
				consequencePath = segmentPath(consequenceDir(), segment);
				lastSegment = segment;
			}
			try {
				Record record = JournalReader.readAt(consequencePath, ref[1]);
				if (record == null) {
					continue;
				}
				ConsequenceRecord consequence = ConsequenceRecord.decode(record.payload());
				if (consequence == null) {
					continue;
				}
				ConsequenceStatus status = consequenceStatus.get(consequence.id());
				if (status != null) {
					consequence.setStatus(status);
				}
				result.add(consequence);
			} catch (IOException ignored) {
			}
		}
		result.sort(Comparator.comparingInt(ConsequenceRecord::tick).thenComparingLong(ConsequenceRecord::id));
		return result;
	}

	public void deleteActivity(long id) throws IOException {
		Record.Builder builder = new Record.Builder();
		builder.writeLong(id);
		activityWriter.append(builder.build(RecordType.ACTIVITY_DELETE));
		activities.remove(id);
		consequenceRefs.remove(id);
		activitySegment.remove(id);
		consequenceSegmentOfActivity.remove(id);
	}

	public void flush(boolean fsync) throws IOException {
		if (activityWriter != null) {
			activityWriter.flush(fsync);
		}
		if (consequenceWriter != null) {
			consequenceWriter.flush(fsync);
		}
		if (fsync) {
			totalBytes = sizeOfDir(activityDir()) + sizeOfDir(consequenceDir());
		}
	}

	private void rotateIfNeeded() throws IOException {
		if (activityWriter.sizeBytes() > limits.segmentBytes() || consequenceWriter.sizeBytes() > limits.segmentBytes()) {
			activityWriter.flush(true);
			consequenceWriter.flush(true);
			activityWriter.close();
			consequenceWriter.close();
			segmentIndex++;
			activityWriter = JournalWriter.open(segmentPath(activityDir(), segmentIndex));
			consequenceWriter = JournalWriter.open(segmentPath(consequenceDir(), segmentIndex));
		}
	}

	@Override
	public void close() {
		try {
			if (activityWriter != null) {
				activityWriter.close();
			}
			if (consequenceWriter != null) {
				consequenceWriter.close();
			}
		} catch (IOException ignored) {
		}
	}

	private Path activityDir() {
		return root.resolve(ACTIVITY_DIR);
	}

	private Path consequenceDir() {
		return root.resolve(CONSEQUENCE_DIR);
	}

	private static Path segmentPath(Path dir, int index) {
		return dir.resolve(String.format("seg-%06d.ctz", index));
	}

	private static List<Integer> segmentIndexes(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> stream = Files.list(dir)) {
			List<Integer> indexes = new ArrayList<>();
			stream.filter(path -> path.getFileName().toString().startsWith("seg-")).forEach(path -> {
				String name = path.getFileName().toString();
				int start = name.indexOf('-') + 1;
				int end = name.lastIndexOf('.');
				try {
					indexes.add(Integer.parseInt(name.substring(start, end)));
				} catch (NumberFormatException ignored) {
				}
			});
			indexes.sort(Integer::compareTo);
			return indexes;
		}
	}

	private int highestSegmentIndex() throws IOException {
		int max = 0;
		for (int index : segmentIndexes(activityDir())) {
			max = Math.max(max, index);
		}
		for (int index : segmentIndexes(consequenceDir())) {
			max = Math.max(max, index);
		}
		return max;
	}

	private static long sizeOfDir(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) {
			return 0L;
		}
		try (Stream<Path> stream = Files.list(dir)) {
			return stream.filter(Files::isRegularFile).mapToLong(path -> {
				try {
					return Files.size(path);
				} catch (IOException e) {
					return 0L;
				}
			}).sum();
		}
	}

	private void pruneToLimits() {
		if (limits.maxTotalBytes() <= 0L && limits.maxActivities() <= 0) {
			return;
		}
		try {
			while (!activities.isEmpty()
					&& ((limits.maxActivities() > 0 && activities.size() > limits.maxActivities())
							|| (limits.maxTotalBytes() > 0L && totalBytes > limits.maxTotalBytes()))) {
				Long oldest = activities.keySet().iterator().next();
				int segment = activitySegment.getOrDefault(oldest, 0);
				dropSegment(segment);
			}
		} catch (IOException ignored) {
		}
	}

	private void dropSegment(int segment) throws IOException {
		List<Long> victims = new ArrayList<>();
		for (Map.Entry<Long, Integer> entry : new ArrayList<>(activitySegment.entrySet())) {
			if (entry.getValue() <= segment) {
				victims.add(entry.getKey());
			}
		}
		for (Long id : victims) {
			activities.remove(id);
			consequenceRefs.remove(id);
			consequenceSegmentOfActivity.remove(id);
			activitySegment.remove(id);
			droppedActivities++;
		}
		for (int index : segmentIndexes(activityDir())) {
			if (index <= segment) {
				Path path = segmentPath(activityDir(), index);
				droppedBytes += Files.size(path);
				Files.deleteIfExists(path);
			}
		}
		for (int index : segmentIndexes(consequenceDir())) {
			if (index <= segment) {
				Path path = segmentPath(consequenceDir(), index);
				droppedBytes += Files.size(path);
				Files.deleteIfExists(path);
			}
		}
		totalBytes = sizeOfDir(activityDir()) + sizeOfDir(consequenceDir());
	}
}