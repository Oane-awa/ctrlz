package com.ctrlz.persistence;

import com.ctrlz.history.Coverage;
import com.ctrlz.history.Operation;
import com.ctrlz.history.OperationType;
import com.ctrlz.history.Snapshot;
import com.ctrlz.history.StateFingerprint;
import com.ctrlz.history.UndoResult;
import com.ctrlz.platform.SnapshotCodec;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent carrier of the history stack (stage 1: records, counters and world fingerprint).
 *
 * <p>Layout: one JSON document stored as a single string inside {@link SavedData}, so the vanilla
 * save pipeline owns the actual file handling. Payloads (block entity NBT, item stacks) are base64
 * blobs produced by {@link SnapshotCodec}, never re-encoded through a registry dependent codec.
 */
public final class HistoryState extends SavedData {
	/** Bump when the document layout changes; a mismatch invalidates the whole history. */
	public static final int SCHEMA_VERSION = 1;

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	public static final SavedDataType<HistoryState> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath("ctrlz", "history"),
			HistoryState::new,
			Codec.STRING.xmap(HistoryState::fromJson, HistoryState::toJson),
			DataFixTypes.LEVEL);

	private final List<Operation> operations = new ArrayList<>();
	private long playTick;
	private long seq;
	private String mcVersion = "";
	private String modFingerprint = "";
	private String worldStateToken = "";
	private String invalidReason = "";

	public List<Operation> operations() {
		return operations;
	}

	public long playTick() {
		return playTick;
	}

	public void setPlayTick(long playTick) {
		if (this.playTick != playTick) {
			this.playTick = playTick;
			setDirty();
		}
	}

	public long seq() {
		return seq;
	}

	public void setSeq(long seq) {
		if (this.seq != seq) {
			this.seq = seq;
			setDirty();
		}
	}

	public String invalidReason() {
		return invalidReason;
	}

	/** Stores the history and marks the state dirty so the next save writes it. */
	public void store(List<Operation> restored, long playTick, long seq, String mcVersion, String modFingerprint,
			String worldStateToken) {
		this.operations.clear();
		this.operations.addAll(restored);
		this.playTick = playTick;
		this.seq = seq;
		this.mcVersion = mcVersion;
		this.modFingerprint = modFingerprint;
		this.worldStateToken = worldStateToken;
		setDirty();
	}

	/** Drops everything; used when the fingerprint check fails or {@code /undohistory purge} runs. */
	public void invalidate(String reason) {
		operations.clear();
		invalidReason = reason == null ? "" : reason;
		setDirty();
	}

	public String toJson() {
		JsonObject root = new JsonObject();
		root.addProperty("schemaVersion", SCHEMA_VERSION);
		root.addProperty("mcVersion", mcVersion);
		root.addProperty("modFingerprint", modFingerprint);
		root.addProperty("worldStateToken", worldStateToken);
		root.addProperty("playTick", playTick);
		root.addProperty("seq", seq);
		JsonArray records = new JsonArray();
		for (Operation operation : operations) {
			records.add(encodeOperation(operation));
		}
		root.add("operations", records);
		return GSON.toJson(root);
	}

	public static HistoryState fromJson(String json) {
		HistoryState state = new HistoryState();
		if (json == null || json.isEmpty()) {
			return state;
		}
		JsonObject root;
		try {
			root = GSON.fromJson(json, JsonObject.class);
		} catch (RuntimeException e) {
			state.invalidReason = "unreadable";
			return state;
		}
		if (root == null) {
			state.invalidReason = "unreadable";
			return state;
		}
		int schema = root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : -1;
		if (schema != SCHEMA_VERSION) {
			state.invalidReason = "schema " + schema;
			return state;
		}
		state.mcVersion = string(root, "mcVersion");
		state.modFingerprint = string(root, "modFingerprint");
		state.worldStateToken = string(root, "worldStateToken");
		state.playTick = root.has("playTick") ? root.get("playTick").getAsLong() : 0L;
		state.seq = root.has("seq") ? root.get("seq").getAsLong() : 0L;
		if (root.has("operations") && root.get("operations").isJsonArray()) {
			for (JsonElement element : root.getAsJsonArray("operations")) {
				Operation operation = decodeOperation(element.getAsJsonObject());
				if (operation != null) {
					state.operations.add(operation);
				}
			}
		}
		return state;
	}

	private static String string(JsonObject object, String key) {
		return object.has(key) ? object.get(key).getAsString() : "";
	}

	// ------------------------------------------------------------------ record codec

	private static JsonObject encodeOperation(Operation operation) {
		JsonObject object = new JsonObject();
		object.addProperty("id", operation.operationId().toString());
		object.addProperty("player", operation.playerUuid().toString());
		object.addProperty("world", operation.worldKey());
		object.addProperty("seq", operation.seq());
		object.addProperty("created", operation.createdPlayTick());
		object.addProperty("committed", operation.committedPlayTick());
		object.addProperty("type", operation.type().name());
		object.addProperty("gameMode", operation.gameMode());
		object.addProperty("coverage", operation.coverage().name());
		object.addProperty("undone", operation.undone());
		object.addProperty("appliedSeq", operation.appliedSeq());
		object.addProperty("p2", operation.p2Touched());
		if (operation.blocked()) {
			object.addProperty("blocked", operation.blockedReason());
			object.addProperty("blockedStatus", operation.blockedStatus().name());
		}
		JsonArray snapshots = new JsonArray();
		for (Snapshot snapshot : operation.snapshots()) {
			snapshots.add(encodeSnapshot(snapshot));
		}
		object.add("snapshots", snapshots);
		JsonObject after = new JsonObject();
		for (Map.Entry<String, StateFingerprint> entry : operation.afterFingerprints().entrySet()) {
			after.addProperty(entry.getKey(), entry.getValue().value());
		}
		object.add("after", after);
		return object;
	}

	private static Operation decodeOperation(JsonObject object) {
		try {
			List<Snapshot> snapshots = new ArrayList<>();
			if (object.has("snapshots")) {
				for (JsonElement element : object.getAsJsonArray("snapshots")) {
					Snapshot snapshot = decodeSnapshot(element.getAsJsonObject());
					if (snapshot != null) {
						snapshots.add(snapshot);
					}
				}
			}
			Map<String, StateFingerprint> after = new LinkedHashMap<>();
			if (object.has("after")) {
				for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("after").entrySet()) {
					after.put(entry.getKey(), new StateFingerprint(entry.getValue().getAsString()));
				}
			}
			Operation operation = Operation.restored(
					UUID.fromString(string(object, "id")),
					UUID.fromString(string(object, "player")),
					string(object, "world"),
					object.get("seq").getAsLong(),
					object.get("created").getAsLong(),
					object.has("committed") ? object.get("committed").getAsLong() : 0L,
					OperationType.valueOf(string(object, "type")),
					string(object, "gameMode"),
					Coverage.valueOf(string(object, "coverage")),
					snapshots,
					after,
					object.has("undone") && object.get("undone").getAsBoolean(),
					object.has("appliedSeq") ? object.get("appliedSeq").getAsLong() : 0L,
					object.has("p2") && object.get("p2").getAsBoolean());
			if (object.has("blocked")) {
				UndoResult.Status status = UndoResult.Status.CONFLICT;
				if (object.has("blockedStatus")) {
					try {
						status = UndoResult.Status.valueOf(object.get("blockedStatus").getAsString());
					} catch (IllegalArgumentException ignored) {
						// Unknown name (older document): the record stays blocked, only the label degrades.
					}
				}
				operation.block(status, object.get("blocked").getAsString());
			}
			return operation;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static JsonObject encodeSnapshot(Snapshot snapshot) {
		JsonObject object = new JsonObject();
		if (snapshot instanceof Snapshot.Block block) {
			object.addProperty("k", "block");
			object.addProperty("dim", block.dimension());
			object.addProperty("pos", block.packedPos());
			object.addProperty("state", block.blockState());
			object.addProperty("nbt", base64(block.blockEntityNbt()));
		} else if (snapshot instanceof Snapshot.Container container) {
			object.addProperty("k", "container");
			object.addProperty("dim", container.dimension());
			object.addProperty("pos", container.packedPos());
			object.addProperty("nbt", base64(container.blockEntityNbt()));
			object.add("items", encodeSlots(container.items()));
		} else if (snapshot instanceof Snapshot.Inventory inventory) {
			object.addProperty("k", "inventory");
			object.addProperty("player", inventory.player().toString());
			object.addProperty("scope", inventory.scope().name());
			object.add("items", encodeSlots(inventory.items()));
		} else if (snapshot instanceof Snapshot.Entity entity) {
			object.addProperty("k", "entity");
			object.addProperty("uuid", entity.uuid().toString());
			object.addProperty("type", entity.typeId());
			object.addProperty("existed", entity.existedBefore());
			object.addProperty("x", entity.x());
			object.addProperty("y", entity.y());
			object.addProperty("z", entity.z());
			object.addProperty("nbt", base64(entity.fullNbt()));
		} else if (snapshot instanceof Snapshot.PlayerState state) {
			object.addProperty("k", "player");
			object.addProperty("dim", state.dimension());
			object.addProperty("x", state.x());
			object.addProperty("y", state.y());
			object.addProperty("z", state.z());
			object.addProperty("yaw", state.yaw());
			object.addProperty("pitch", state.pitch());
			object.addProperty("vx", state.vx());
			object.addProperty("vy", state.vy());
			object.addProperty("vz", state.vz());
			object.addProperty("health", state.health());
			object.addProperty("food", state.food());
			object.addProperty("xp", state.xp());
			object.addProperty("fire", state.fireTicks());
			object.addProperty("effects", base64(state.effects()));
		}
		return object;
	}

	private static Snapshot decodeSnapshot(JsonObject object) {
		try {
			String kind = string(object, "k");
			return switch (kind) {
				case "block" -> new Snapshot.Block(string(object, "dim"), object.get("pos").getAsLong(),
						string(object, "state"), unbase64(string(object, "nbt")));
				case "container" -> new Snapshot.Container(string(object, "dim"), object.get("pos").getAsLong(),
						unbase64(string(object, "nbt")), decodeSlots(object));
				case "inventory" -> new Snapshot.Inventory(UUID.fromString(string(object, "player")),
						Snapshot.Scope.valueOf(string(object, "scope")), decodeSlots(object));
				case "entity" -> new Snapshot.Entity(UUID.fromString(string(object, "uuid")), string(object, "type"),
						unbase64(string(object, "nbt")), object.get("existed").getAsBoolean(),
						object.get("x").getAsDouble(), object.get("y").getAsDouble(),
						object.get("z").getAsDouble());
				case "player" -> new Snapshot.PlayerState(string(object, "dim"), object.get("x").getAsDouble(),
						object.get("y").getAsDouble(), object.get("z").getAsDouble(), object.get("yaw").getAsFloat(),
						object.get("pitch").getAsFloat(), object.get("vx").getAsDouble(),
						object.get("vy").getAsDouble(), object.get("vz").getAsDouble(),
						object.get("health").getAsFloat(), object.get("food").getAsInt(),
						object.get("xp").getAsInt(), unbase64(string(object, "effects")),
						object.get("fire").getAsInt());
				default -> null;
			};
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static JsonObject encodeSlots(Map<Integer, byte[]> slots) {
		JsonObject object = new JsonObject();
		for (Map.Entry<Integer, byte[]> entry : slots.entrySet()) {
			object.addProperty(Integer.toString(entry.getKey()), base64(entry.getValue()));
		}
		return object;
	}

	private static Map<Integer, byte[]> decodeSlots(JsonObject object) {
		Map<Integer, byte[]> slots = new LinkedHashMap<>();
		if (!object.has("items")) {
			return slots;
		}
		for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("items").entrySet()) {
			slots.put(Integer.parseInt(entry.getKey()), unbase64(entry.getValue().getAsString()));
		}
		return slots;
	}

	private static String base64(byte[] data) {
		return data == null || data.length == 0 ? "" : Base64.getEncoder().encodeToString(data);
	}

	private static byte[] unbase64(String text) {
		return text == null || text.isEmpty() ? new byte[0] : Base64.getDecoder().decode(text);
	}
}
