package com.ctrlz.platform;

import com.ctrlz.history.Snapshot;
import com.ctrlz.history.StateFingerprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reads the state that an operation is about to change.
 *
 * <p>Slice 0 needs exactly two shapes: a block (state plus block entity payload) and a spawned drop
 * entity that has to disappear again when the block comes back. Everything runs on the server
 * thread while the world is still in its pre-operation state.
 */
public final class SnapshotCapture {
	private SnapshotCapture() {
	}

	/** Captures a block and its block entity data before it is modified. */
	public static Snapshot.Block block(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		byte[] blockEntityNbt = state.hasBlockEntity()
				? SnapshotCodec.blockEntityData(level, pos)
				: new byte[0];
		return new Snapshot.Block(SnapshotCodec.dimensionId(level), SnapshotCodec.pack(pos),
				SnapshotCodec.encodeBlockState(state), blockEntityNbt);
	}

	/** The state the operation left behind, used later as the conflict baseline. */
	public static StateFingerprint blockFingerprint(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		byte[] blockEntityNbt = state.hasBlockEntity()
				? SnapshotCodec.blockEntityData(level, pos)
				: new byte[0];
		return new StateFingerprint(SnapshotCodec.blockFingerprint(state, blockEntityNbt));
	}

	/**
	 * Player state captured before an operation.
	 *
	 * <p>{@code effects} carries the complete status effect set encoded with the native
	 * {@code MobEffectInstance} stream codec (see {@link com.ctrlz.server.EffectSnapshots}), so the
	 * field is real restore data and not a placeholder. Position/health/experience are recorded as
	 * well; the undo of those lands in a later slice, effects are already applied.
	 */
	public static Snapshot.PlayerState playerState(ServerLevel level, net.minecraft.world.entity.player.Player player) {
		net.minecraft.world.phys.Vec3 velocity = player.getDeltaMovement();
		return new Snapshot.PlayerState(SnapshotCodec.dimensionId(level), player.getX(), player.getY(), player.getZ(),
				player.getYRot(), player.getXRot(), velocity.x, velocity.y, velocity.z, player.getHealth(),
				player.getFoodData().getFoodLevel(), player.totalExperience,
				com.ctrlz.server.EffectSnapshots.encode(player), player.getRemainingFireTicks());
	}

	/**
	 * Records an entity that did not exist before the operation, so undoing the operation can remove
	 * exactly that entity and nothing else.
	 */
	public static Snapshot.Entity spawnedEntity(Entity entity) {
		String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
		return new Snapshot.Entity(entity.getUUID(), typeId, new byte[0], false,
				entity.getX(), entity.getY(), entity.getZ());
	}

	public static boolean isItemDrop(Entity entity) {
		return entity instanceof ItemEntity;
	}
}
