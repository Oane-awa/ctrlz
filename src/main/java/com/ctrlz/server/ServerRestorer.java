package com.ctrlz.server;

import com.ctrlz.core.consequence.ConsequenceRecord;
import com.ctrlz.core.rollback.ConflictType;
import com.ctrlz.core.rollback.ConsequenceRestorer;
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
public final class ServerRestorer implements ConsequenceRestorer {
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
				default -> ConflictType.NONE;
			};
		} catch (RuntimeException e) {
			return ConflictType.STATE_MISMATCH;
		}
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
					? ConflictType.NONE : ConflictType.STATE_MISMATCH;
		}
		ServerLevel level = level(consequence.dimension());
		if (level == null || !consequence.hasPosition()
				|| !(level.getBlockEntity(posOf(consequence)) instanceof Container container)) {
			return ConflictType.TARGET_MISSING;
		}
		return slotMatches(container, slotIndex(subject), consequence, level) ? ConflictType.NONE
				: ConflictType.STATE_MISMATCH;
	}

	private ConflictType checkInventory(ConsequenceRecord consequence) {
		ServerPlayer player = playerFromSubject(consequence.subject());
		if (player == null) {
			return ConflictType.TARGET_MISSING;
		}
		return slotMatches(player.getInventory(), slotIndex(consequence.subject()), consequence, player.level())
				? ConflictType.NONE : ConflictType.STATE_MISMATCH;
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
