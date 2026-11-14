package com.ctrlz.server;

import com.ctrlz.core.consequence.ConsequenceKind;
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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Applies and validates inverse operations against the live world.
 *
 * <p>Each {@code check} compares the world with the state the activity left behind, so a rollback
 * refuses to touch a block or slot that somebody else changed afterwards.
 */
public final class ServerRestorer implements ConsequenceRestorer {
	private final MinecraftServer server;

	public ServerRestorer(MinecraftServer server) {
		this.server = server;
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

	@Override
	public ConflictType check(ConsequenceRecord consequence) {
		try {
			return switch (consequence.kind()) {
				case BLOCK_STATE -> checkBlock(consequence);
				case CONTAINER_SLOTS -> checkContainer(consequence);
				case INVENTORY_SLOTS -> checkInventory(consequence);
				case ITEM_ENTITY_SPAWN -> ConflictType.NONE;
				case ENTITY_HEALTH -> ConflictType.NONE;
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
		if (expected == null) {
			return ConflictType.NO_SNAPSHOT;
		}
		BlockState current = level.getBlockState(posOf(consequence));
		return current.equals(expected) ? ConflictType.NONE : ConflictType.STATE_MISMATCH;
	}

	private ConflictType checkContainer(ConsequenceRecord consequence) {
		return ConflictType.NONE;
	}

	private ConflictType checkInventory(ConsequenceRecord consequence) {
		return ConflictType.NONE;
	}

	@Override
	public boolean restore(ConsequenceRecord consequence) {
		try {
			return switch (consequence.kind()) {
				case BLOCK_STATE -> restoreBlock(consequence);
				case BLOCK_ENTITY_DATA -> restoreBlockEntity(consequence);
				case CONTAINER_SLOTS -> restoreContainer(consequence);
				case INVENTORY_SLOTS -> restoreInventory(consequence);
				case ITEM_ENTITY_SPAWN -> restoreItemEntity(consequence);
				case ENTITY_HEALTH -> restoreHealth(consequence);
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
				case BLOCK_ENTITY_DATA -> true;
				case CONTAINER_SLOTS -> applyContainer(consequence, consequence.after());
				case INVENTORY_SLOTS -> applyInventory(consequence, consequence.after());
				case ITEM_ENTITY_SPAWN -> reapplyItemEntity(consequence);
				case ENTITY_HEALTH -> applyHealth(consequence, consequence.after());
				default -> true;
			};
		} catch (RuntimeException e) {
			return false;
		}
	}

	private boolean restoreBlock(ConsequenceRecord consequence) {
		return applyBlock(consequence, consequence.before());
	}

	private boolean applyBlock(ConsequenceRecord consequence, byte[] stateBytes) {
		ServerLevel level = level(consequence.dimension());
		if (level == null) {
			return false;
		}
		BlockState state = Snapshots.decodeBlockState(new String(stateBytes, StandardCharsets.UTF_8));
		if (state == null) {
			return false;
		}
		level.setBlock(posOf(consequence), state, 3);
		return true;
	}

	private boolean restoreBlockEntity(ConsequenceRecord consequence) {
		ServerLevel level = level(consequence.dimension());
		if (level == null || consequence.before().length == 0) {
			return true;
		}
		BlockEntity blockEntity = level.getBlockEntity(posOf(consequence));
		if (blockEntity == null) {
			return false;
		}
		net.minecraft.nbt.CompoundTag tag = Snapshots.readTag(consequence.before());
		if (tag == null) {
			return false;
		}
		blockEntity.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
				net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), tag));
		blockEntity.setChanged();
		level.sendBlockUpdated(posOf(consequence), level.getBlockState(posOf(consequence)),
				level.getBlockState(posOf(consequence)), 3);
		return true;
	}

	private boolean restoreContainer(ConsequenceRecord consequence) {
		return applyContainer(consequence, consequence.before());
	}

	private boolean applyContainer(ConsequenceRecord consequence, byte[] itemBytes) {
		ServerLevel level = level(consequence.dimension());
		if (level == null || !consequence.hasPosition()) {
			return false;
		}
		BlockEntity blockEntity = level.getBlockEntity(posOf(consequence));
		if (!(blockEntity instanceof Container container)) {
			return false;
		}
		int slot = slotIndex(consequence.subject());
		if (slot < 0 || slot >= container.getContainerSize()) {
			return false;
		}
		ItemStack stack = Snapshots.decodeSingleItem(itemBytes, level.registryAccess());
		container.setItem(slot, stack);
		container.setChanged();
		return true;
	}

	private boolean restoreInventory(ConsequenceRecord consequence) {
		return applyInventory(consequence, consequence.before());
	}

	private boolean applyInventory(ConsequenceRecord consequence, byte[] itemBytes) {
		ServerPlayer player = playerFromSubject(consequence);
		if (player == null) {
			return false;
		}
		int slot = slotIndex(consequence.subject());
		if (slot < 0 || slot >= player.getInventory().getContainerSize()) {
			return false;
		}
		ItemStack stack = Snapshots.decodeSingleItem(itemBytes, player.level().registryAccess());
		player.getInventory().setItem(slot, stack);
		player.getInventory().setChanged();
		return true;
	}

	private boolean restoreItemEntity(ConsequenceRecord consequence) {
		ServerLevel level = level(consequence.dimension());
		if (level == null) {
			return false;
		}
		if (consequence.after().length > 0) {
			Entity entity = findEntity(consequence.subject());
			if (entity != null) {
				entity.discard();
			}
			return true;
		}
		if (consequence.before().length > 0 && consequence.hasPosition()) {
			BlockPos pos = posOf(consequence);
			ItemStack stack = Snapshots.decodeSingleItem(consequence.before(), level.registryAccess());
			if (stack.isEmpty()) {
				return true;
			}
			ItemEntity itemEntity = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
					stack);
			itemEntity.setPickUpDelay(0);
			level.addFreshEntity(itemEntity);
		}
		return true;
	}

	private boolean reapplyItemEntity(ConsequenceRecord consequence) {
		if (consequence.after().length > 0 && consequence.hasPosition()) {
			ServerLevel level = level(consequence.dimension());
			if (level == null) {
				return false;
			}
			BlockPos pos = posOf(consequence);
			ItemStack stack = Snapshots.decodeSingleItem(consequence.after(), level.registryAccess());
			if (!stack.isEmpty()) {
				level.addFreshEntity(new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
						stack));
			}
		}
		return true;
	}

	private boolean restoreHealth(ConsequenceRecord consequence) {
		return applyHealth(consequence, consequence.before());
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

	private ServerPlayer playerFromSubject(ConsequenceRecord consequence) {
		String[] parts = consequence.subject().split("#");
		if (parts.length < 3 || !"inv".equals(parts[0])) {
			return null;
		}
		try {
			UUID uuid = UUID.fromString(parts[1]);
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			return player;
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
