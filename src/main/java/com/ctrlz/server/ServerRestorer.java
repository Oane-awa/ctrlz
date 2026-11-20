package com.ctrlz.server;

import com.ctrlz.core.consequence.ConsequenceRecord;
import com.ctrlz.core.rollback.ConflictType;
import com.ctrlz.core.rollback.ConsequenceRestorer;
import com.ctrlz.core.rollback.RollbackPreflight;
import com.ctrlz.core.util.ByteIO;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * Applies and validates inverse operations against the live world.
 *
 * <p>Each {@code check} compares the world with the state the activity left behind, so a rollback
 * refuses to touch a block or slot that somebody else changed afterwards.
 */
public final class ServerRestorer implements ConsequenceRestorer, RollbackPreflight.StateReader {
	private final MinecraftServer server;
	/** Entities picked up by a consequence of the very rollback being executed. */
	private final Set<String> pickedUpInBatch;

	public ServerRestorer(MinecraftServer server, Set<String> pickedUpInBatch) {
		this.server = server;
		this.pickedUpInBatch = pickedUpInBatch;
	}

	private ServerLevel level(String dimension) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level.dimension().identifier().toString().equals(dimension)) {
				return level;
			}
		}
		return null;
	}

	private BlockPos posOf(ConsequenceRecord consequence) {
		return new BlockPos(ByteIO.unpackX(consequence.packedPos()), ByteIO.unpackY(consequence.packedPos()),
				ByteIO.unpackZ(consequence.packedPos()));
	}

	// ------------------------------------------------------------------ checks

	@Override
	public ConflictType check(ConsequenceRecord consequence) {
		try {
			return switch (consequence.kind()) {
				case BLOCK_STATE -> checkBlock(consequence);
				case CONTAINER_SLOTS -> checkContainer(consequence);
				case INVENTORY_SLOTS -> checkInventory(consequence);
				case ITEM_ENTITY_SPAWN -> checkItemEntity(consequence);
				case PLAYER_STATE -> checkPlayer(consequence);
				case ENTITY_EFFECTS -> checkEffects(consequence);
				case ENTITY_HEALTH -> checkHealth(consequence);
				default -> ConflictType.NONE;
			};
		} catch (RuntimeException e) {
			return ConflictType.STATE_MISMATCH;
		}
	}

	/**
	 * Payload sanity of one consequence, independent of the world.
	 *
	 * <p>Runs before the simulation so a corrupted record is reported as such instead of surfacing
	 * as a mysterious state mismatch or, worse, being written as garbage.
	 */
	public ConflictType checkIntegrity(ConsequenceRecord consequence) {
		try {
			return switch (consequence.kind()) {
				case BLOCK_STATE -> {
					// A block state is always recorded, so an empty payload cannot be genuine.
					if (consequence.before().length == 0 || consequence.after().length == 0) {
						yield ConflictType.PAYLOAD_CORRUPT;
					}
					yield Snapshots.decodeBlockState(new String(consequence.before(), StandardCharsets.UTF_8)) == null
							? ConflictType.PAYLOAD_CORRUPT : ConflictType.NONE;
				}
				case BLOCK_ENTITY_DATA -> consequence.before().length == 0
						|| Snapshots.readTag(consequence.before()) == null ? ConflictType.PAYLOAD_CORRUPT
								: ConflictType.NONE;
				case CONTAINER_SLOTS, INVENTORY_SLOTS -> consequence.before().length == 0
						? ConflictType.PAYLOAD_CORRUPT : ConflictType.NONE;
				default -> ConflictType.NONE;
			};
		} catch (RuntimeException e) {
			return ConflictType.PAYLOAD_CORRUPT;
		}
	}

	// ------------------------------------------------------------------ read only state access

	/**
	 * Current state of one state object, encoded exactly like the recorded payloads so the
	 * preflight can compare them byte for byte. Never writes anything.
	 */
	@Override
	public byte[] readState(String key, ConsequenceRecord change) {
		try {
			if (change.kind() == com.ctrlz.core.consequence.ConsequenceKind.ITEM_ENTITY_SPAWN) {
				Entity entity = entityFromSubject(change.subject());
				if (!(entity instanceof ItemEntity item)) {
					return null;
				}
				return Snapshots.encodeSingleItem(item.getItem(), item.level().registryAccess());
			}
			return readByKey(key);
		} catch (RuntimeException e) {
			return null;
		}
	}

	@Override
	public boolean stateExists(String key, ConsequenceRecord change) {
		try {
			return switch (change.kind()) {
				case ITEM_ENTITY_SPAWN, ENTITY_HEALTH, ENTITY_EFFECTS -> entityFromSubject(change.subject()) != null;
				case PLAYER_STATE -> playerFromSubject(change.subject()) != null;
				case BLOCK_STATE, BLOCK_ENTITY_DATA -> level(change.dimension()) != null;
				default -> true;
			};
		} catch (RuntimeException e) {
			return false;
		}
	}

	/**
	 * Reads a state object by its preflight key ({@code block|dim|pos}, {@code slot|dim|pos|subject},
	 * {@code entity|uuid}). Used for the post restore verification, where only the key is left.
	 */
	public byte[] readByKey(String key) {
		if (key == null) {
			return null;
		}
		String[] parts = key.split("\\|", 4);
		if (parts.length < 2) {
			return null;
		}
		try {
			return switch (parts[0]) {
				case "block" -> {
					ServerLevel serverLevel = parts.length > 2 ? level(parts[1]) : null;
					if (serverLevel == null) {
						yield null;
					}
					BlockPos pos = posOf(Long.parseLong(parts[2]));
					yield Snapshots.encodeBlockState(serverLevel.getBlockState(pos))
							.getBytes(StandardCharsets.UTF_8);
				}
				case "slot" -> parts.length > 3 ? readSlot(parts[1], parts[2], parts[3]) : null;
				case "entity" -> findEntity(parts[1]) == null ? new byte[] { 0 } : new byte[] { 1 };
				default -> null;
			};
		} catch (RuntimeException e) {
			return null;
		}
	}

	private byte[] readSlot(String dimension, String packedPos, String subject) {
		if (subject.startsWith("unsupported#")) {
			return null;
		}
		if (subject.startsWith("ender#")) {
			ServerPlayer player = playerFromSubject(subject);
			return player == null ? null
					: readSlot(player.getEnderChestInventory(), slotIndex(subject), player.level());
		}
		if (subject.startsWith("inv#")) {
			ServerPlayer player = playerFromSubject(subject);
			return player == null ? null : readSlot(player.getInventory(), slotIndex(subject), player.level());
		}
		ServerLevel level = level(dimension);
		if (level == null) {
			return null;
		}
		if (!(level.getBlockEntity(posOf(Long.parseLong(packedPos))) instanceof Container container)) {
			return null;
		}
		return readSlot(container, slotIndex(subject), level);
	}

	private byte[] readSlot(Container container, int slot, net.minecraft.world.level.Level level) {
		if (slot < 0 || slot >= container.getContainerSize()) {
			return null;
		}
		return Snapshots.encodeSingleItem(container.getItem(slot), level.registryAccess());
	}

	private static BlockPos posOf(long packed) {
		return new BlockPos(ByteIO.unpackX(packed), ByteIO.unpackY(packed), ByteIO.unpackZ(packed));
	}

	/** Resolves {@code <uuid>}, {@code entity#<uuid>} and {@code player#<uuid>} subject forms. */
	private Entity entityFromSubject(String subject) {
		if (subject == null || subject.isEmpty()) {
			return null;
		}
		String[] parts = subject.split("#");
		if (parts.length >= 2) {
			if ("player".equals(parts[0])) {
				return playerFromSubject(subject);
			}
			return findEntity(parts[1]);
		}
		return findEntity(subject);
	}

	private ConflictType checkHealth(ConsequenceRecord consequence) {
		// A dead or unloaded target cannot be "restored": reporting success there would be a lie.
		return entityFromSubject(consequence.subject()) instanceof LivingEntity ? ConflictType.NONE
				: ConflictType.TARGET_MISSING;
	}

	private ConflictType checkBlock(ConsequenceRecord consequence) {
		ServerLevel level = level(consequence.dimension());
		if (level == null) {
			return ConflictType.TARGET_MISSING;
		}
		BlockState expected = Snapshots.decodeBlockState(new String(consequence.after(), StandardCharsets.UTF_8));
		BlockState original = Snapshots.decodeBlockState(new String(consequence.before(), StandardCharsets.UTF_8));
		if (expected == null || original == null) {
			return ConflictType.NO_SNAPSHOT;
		}
		return level.getBlockState(posOf(consequence)).equals(expected) ? ConflictType.NONE
				: ConflictType.STATE_MISMATCH;
	}

	private ConflictType checkContainer(ConsequenceRecord consequence) {
		String subject = consequence.subject();
		if (subject.startsWith("unsupported#")) {
			return ConflictType.UNSUPPORTED_MODDED_STATE;
		}
		if (subject.startsWith("ender#")) {
			ServerPlayer player = playerFromSubject(subject);
			if (player == null) {
				return ConflictType.TARGET_MISSING;
			}
			return slotMatches(player.getEnderChestInventory(), slotIndex(subject), consequence, player.level())
					? ConflictType.NONE : ConflictType.ITEM_MISMATCH;
		}
		ServerLevel level = level(consequence.dimension());
		if (level == null || !consequence.hasPosition()
				|| !(level.getBlockEntity(posOf(consequence)) instanceof Container container)) {
			return ConflictType.TARGET_MISSING;
		}
		return slotMatches(container, slotIndex(subject), consequence, level) ? ConflictType.NONE
				: ConflictType.ITEM_MISMATCH;
	}

	private ConflictType checkInventory(ConsequenceRecord consequence) {
		ServerPlayer player = playerFromSubject(consequence.subject());
		if (player == null) {
			return ConflictType.TARGET_MISSING;
		}
		return slotMatches(player.getInventory(), slotIndex(consequence.subject()), consequence, player.level())
				? ConflictType.NONE : ConflictType.ITEM_MISMATCH;
	}

	private boolean slotMatches(Container container, int slot, ConsequenceRecord consequence,
			net.minecraft.world.level.Level level) {
		if (slot < 0 || slot >= container.getContainerSize()) {
			return false;
		}
		ItemStack expected = Snapshots.decodeSingleItem(consequence.after(), level.registryAccess());
		return ItemStack.matches(container.getItem(slot), expected);
	}

	private ConflictType checkItemEntity(ConsequenceRecord consequence) {
		if (consequence.after().length == 0) {
			return ConflictType.NONE;
		}
		// The drop is gone. If it ended up in somebody's inventory, rolling back its source would
		// leave that item behind as well as the restored block, which is a duplicate.
		CtrlZServer hub = CtrlZServer.get();
		boolean missing = findEntity(consequence.subject()) == null;
		if (missing && hub != null && hub.wasPickedUp(consequence.subject())
				&& !pickedUpInBatch.contains(consequence.subject())) {
			return ConflictType.STATE_MISMATCH;
		}
		return ConflictType.NONE;
	}

	private ConflictType checkPlayer(ConsequenceRecord consequence) {
		ServerPlayer player = playerFromSubject(consequence.subject());
		ServerLevel level = level(consequence.dimension());
		if (player == null || level == null) {
			return ConflictType.TARGET_MISSING;
		}
		double[] target = parsePosition(consequence.before());
		if (target == null) {
			return ConflictType.NO_SNAPSHOT;
		}
		BlockPos feet = BlockPos.containing(target[0], target[1], target[2]);
		BlockPos head = feet.above();
		boolean free = level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
				&& level.getBlockState(head).getCollisionShape(level, head).isEmpty();
		return free ? ConflictType.NONE : ConflictType.STATE_MISMATCH;
	}

	/**
	 * The effect set is restored on the live entity, so a target that is gone (dead, despawned,
	 * unloaded, logged off) cannot be restored. Refusing is deliberate: reporting success while the
	 * effects stayed as they are would be a partial restore described as a full one.
	 */
	private ConflictType checkEffects(ConsequenceRecord consequence) {
		LivingEntity target = livingFromSubject(consequence.subject());
		if (target == null) {
			return ConflictType.TARGET_MISSING;
		}
		return EffectSnapshots.isCaptured(consequence.before()) ? ConflictType.NONE : ConflictType.NO_SNAPSHOT;
	}

	// ------------------------------------------------------------------ restore / reapply

	@Override
	public boolean restore(ConsequenceRecord consequence) {
		try {
			return switch (consequence.kind()) {
				case BLOCK_STATE -> applyBlock(consequence, consequence.before());
				case BLOCK_ENTITY_DATA -> restoreBlockEntity(consequence);
				case CONTAINER_SLOTS -> applyContainer(consequence, consequence.before());
				case INVENTORY_SLOTS -> applyInventory(consequence, consequence.before());
				case ITEM_ENTITY_SPAWN -> consequence.after().length > 0 ? discardItem(consequence)
						: spawnItem(consequence, consequence.before(), false);
				case ENTITY_HEALTH -> applyHealth(consequence, consequence.before());
				case ENTITY_EFFECTS -> applyEffects(consequence, consequence.before());
				case PLAYER_STATE -> applyPlayer(consequence, consequence.before());
				default -> true;
			};
		} catch (RuntimeException e) {
			return false;
		}
	}

	@Override
	public boolean reapply(ConsequenceRecord consequence) {
		try {
			return switch (consequence.kind()) {
				case BLOCK_STATE -> applyBlock(consequence, consequence.after());
				case CONTAINER_SLOTS -> applyContainer(consequence, consequence.after());
				case INVENTORY_SLOTS -> applyInventory(consequence, consequence.after());
				case ITEM_ENTITY_SPAWN -> consequence.after().length > 0 ? spawnItem(consequence, consequence.after(), true)
						: discardItem(consequence);
				case ENTITY_HEALTH -> applyHealth(consequence, consequence.after());
				case ENTITY_EFFECTS -> applyEffects(consequence, consequence.after());
				case PLAYER_STATE -> applyPlayer(consequence, consequence.after());
				default -> true;
			};
		} catch (RuntimeException e) {
			return false;
		}
	}

	private boolean applyBlock(ConsequenceRecord consequence, byte[] stateBytes) {
		ServerLevel level = level(consequence.dimension());
		BlockState state = Snapshots.decodeBlockState(new String(stateBytes, StandardCharsets.UTF_8));
		if (level == null || state == null) {
			return false;
		}
		level.setBlock(posOf(consequence), state, 3);
		return true;
	}

	private boolean restoreBlockEntity(ConsequenceRecord consequence) {
		ServerLevel level = level(consequence.dimension());
		if (level == null) {
			return false;
		}
		if (consequence.before().length == 0) {
			return true;
		}
		BlockPos pos = posOf(consequence);
		BlockEntity blockEntity = level.getBlockEntity(pos);
		net.minecraft.nbt.CompoundTag tag = Snapshots.readTag(consequence.before());
		if (blockEntity == null || tag == null) {
			return false;
		}
		blockEntity.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
				net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), tag));
		blockEntity.setChanged();
		BlockState state = level.getBlockState(pos);
		level.sendBlockUpdated(pos, state, state, 3);
		return true;
	}

	private boolean applyContainer(ConsequenceRecord consequence, byte[] itemBytes) {
		String subject = consequence.subject();
		if (subject.startsWith("unsupported#")) {
			return false;
		}
		if (subject.startsWith("ender#")) {
			ServerPlayer player = playerFromSubject(subject);
			return player != null && setSlot(player.getEnderChestInventory(), slotIndex(subject), itemBytes,
					player.level());
		}
		ServerLevel level = level(consequence.dimension());
		if (level == null || !consequence.hasPosition()
				|| !(level.getBlockEntity(posOf(consequence)) instanceof Container container)) {
			return false;
		}
		return setSlot(container, slotIndex(subject), itemBytes, level);
	}

	private boolean applyInventory(ConsequenceRecord consequence, byte[] itemBytes) {
		ServerPlayer player = playerFromSubject(consequence.subject());
		return player != null
				&& setSlot(player.getInventory(), slotIndex(consequence.subject()), itemBytes, player.level());
	}

	private boolean setSlot(Container container, int slot, byte[] itemBytes, net.minecraft.world.level.Level level) {
		if (slot < 0 || slot >= container.getContainerSize()) {
			return false;
		}
		container.setItem(slot, Snapshots.decodeSingleItem(itemBytes, level.registryAccess()));
		container.setChanged();
		return true;
	}

	private boolean discardItem(ConsequenceRecord consequence) {
		Entity entity = findEntity(consequence.subject());
		if (entity != null) {
			entity.discard();
		}
		CtrlZServer hub = CtrlZServer.get();
		if (hub != null && consequence.after().length == 0) {
			// Re-applying a pickup: the item is inside an inventory again.
			hub.markPickedUp(consequence.subject());
		}
		return true;
	}

	/**
	 * Brings an item entity back. It keeps the UUID it had when it was recorded, so that undoing
	 * the activity that originally dropped it still finds and removes this very entity.
	 */
	private boolean spawnItem(ConsequenceRecord consequence, byte[] itemBytes, boolean freshSpawn) {
		ServerLevel level = level(consequence.dimension());
		if (level == null || !consequence.hasPosition()) {
			return false;
		}
		ItemStack stack = Snapshots.decodeSingleItem(itemBytes, level.registryAccess());
		if (stack.isEmpty()) {
			return true;
		}
		BlockPos pos = posOf(consequence);
		ItemEntity entity = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
		try {
			UUID uuid = UUID.fromString(consequence.subject());
			if (level.getEntity(uuid) == null) {
				entity.setUUID(uuid);
			}
		} catch (IllegalArgumentException ignored) {
		}
		// Long enough that a player standing on the spot does not instantly pick it up again.
		entity.setPickUpDelay(60);
		boolean added = level.addFreshEntity(entity);
		CtrlZServer hub = CtrlZServer.get();
		if (added && !freshSpawn && hub != null) {
			hub.unmarkPickedUp(consequence.subject());
		}
		return added;
	}

	private boolean applyHealth(ConsequenceRecord consequence, byte[] healthBytes) {
		Entity entity = findEntity(consequence.subject());
		if (!(entity instanceof LivingEntity living)) {
			return true;
		}
		try {
			living.setHealth(Float.parseFloat(new String(healthBytes, StandardCharsets.UTF_8)));
		} catch (NumberFormatException e) {
			return false;
		}
		return true;
	}

	/**
	 * Replaces the target's whole effect set with the recorded one; see {@link EffectSnapshots}.
	 *
	 * <p>A payload that is absent or unreadable returns false, which makes the engine re-apply what it
	 * already did instead of pretending the effects were restored.
	 */
	private boolean applyEffects(ConsequenceRecord consequence, byte[] payload) {
		LivingEntity target = livingFromSubject(consequence.subject());
		if (target == null) {
			return false;
		}
		return EffectSnapshots.apply(target, payload);
	}

	/** Subjects look like {@code player#<uuid>} or {@code entity#<uuid>}. */
	private LivingEntity livingFromSubject(String subject) {
		String[] parts = subject.split("#");
		if (parts.length < 2) {
			return null;
		}
		Entity entity = "player".equals(parts[0]) ? playerFromSubject(subject) : findEntity(parts[1]);
		return entity instanceof LivingEntity living ? living : null;
	}

	private boolean applyPlayer(ConsequenceRecord consequence, byte[] positionBytes) {
		ServerPlayer player = playerFromSubject(consequence.subject());
		ServerLevel level = level(consequence.dimension());
		double[] position = parsePosition(positionBytes);
		if (player == null || level == null || position == null) {
			return false;
		}
		boolean moved = player.teleportTo(level, position[0], position[1], position[2], Set.of(), player.getYRot(),
				player.getXRot(), true);
		// Falling back to the start must not hurt, and old momentum must not carry on.
		player.resetFallDistance();
		player.setDeltaMovement(Vec3.ZERO);
		return moved;
	}

	private static double[] parsePosition(byte[] bytes) {
		try {
			String[] parts = new String(bytes, StandardCharsets.UTF_8).split(";");
			if (parts.length != 3) {
				return null;
			}
			return new double[] { Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
					Double.parseDouble(parts[2]) };
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private Entity findEntity(String uuidText) {
		try {
			UUID uuid = UUID.fromString(uuidText);
			for (ServerLevel level : server.getAllLevels()) {
				Entity entity = level.getEntity(uuid);
				if (entity != null) {
					return entity;
				}
			}
		} catch (IllegalArgumentException ignored) {
		}
		return null;
	}

	/** Subjects look like {@code inv#<uuid>#<slot>}, {@code ender#<uuid>#<slot>} or {@code player#<uuid>}. */
	private ServerPlayer playerFromSubject(String subject) {
		String[] parts = subject.split("#");
		if (parts.length < 2 || !("inv".equals(parts[0]) || "ender".equals(parts[0]) || "player".equals(parts[0]))) {
			return null;
		}
		try {
			return server.getPlayerList().getPlayer(UUID.fromString(parts[1]));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static int slotIndex(String subject) {
		int hash = subject.lastIndexOf('#');
		if (hash < 0) {
			return -1;
		}
		try {
			return Integer.parseInt(subject.substring(hash + 1));
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
