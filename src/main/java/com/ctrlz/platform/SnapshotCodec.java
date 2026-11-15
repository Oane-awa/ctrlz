package com.ctrlz.platform;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The only place that converts Minecraft state into the opaque payloads stored in {@link
 * com.ctrlz.history.Snapshot}.
 *
 * <p>Block states are stored as {@code namespace:block[prop=value,...]} strings, block entities and
 * item stacks as raw bytes. Item stacks use the registry aware stream codec, so count, durability,
 * enchantments and custom names round trip exactly.
 */
public final class SnapshotCodec {
	private SnapshotCodec() {
	}

	// ------------------------------------------------------------------ block state

	public static String encodeBlockState(BlockState state) {
		StringBuilder builder = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		if (!state.getProperties().isEmpty()) {
			builder.append('[');
			Map<String, String> sorted = new TreeMap<>();
			for (Property<?> property : state.getProperties()) {
				sorted.put(property.getName(), text(state, property));
			}
			boolean first = true;
			for (Map.Entry<String, String> entry : sorted.entrySet()) {
				if (!first) {
					builder.append(',');
				}
				builder.append(entry.getKey()).append('=').append(entry.getValue());
				first = false;
			}
			builder.append(']');
		}
		return builder.toString();
	}

	@SuppressWarnings("unchecked")
	private static <T extends Comparable<T>> String text(BlockState state, Property<?> property) {
		Property<T> typed = (Property<T>) property;
		return typed.getName(state.getValue(typed));
	}

	public static BlockState decodeBlockState(String encoded) {
		if (encoded == null || encoded.isEmpty()) {
			return null;
		}
		String idPart = encoded;
		String propsPart = "";
		int bracket = encoded.indexOf('[');
		if (bracket >= 0 && encoded.endsWith("]")) {
			idPart = encoded.substring(0, bracket);
			propsPart = encoded.substring(bracket + 1, encoded.length() - 1);
		}
		Block block;
		try {
			block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(idPart));
		} catch (RuntimeException e) {
			return null;
		}
		if (block == null) {
			return null;
		}
		BlockState state = block.defaultBlockState();
		for (String pair : propsPart.split(",")) {
			int eq = pair.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			state = applyProperty(state, pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
		}
		return state;
	}

	private static BlockState applyProperty(BlockState state, String name, String value) {
		for (Property<?> candidate : state.getProperties()) {
			if (candidate.getName().equals(name)) {
				return setTyped(state, candidate, value);
			}
		}
		return state;
	}

	@SuppressWarnings("unchecked")
	private static <T extends Comparable<T>> BlockState setTyped(BlockState state, Property<?> property,
			String value) {
		Property<T> typed = (Property<T>) property;
		Optional<T> parsed = typed.getValue(value);
		return parsed.map(t -> state.setValue(typed, t)).orElse(state);
	}

	/** Reads a property by name without depending on its concrete class. */
	public static String propertyText(BlockState state, String name) {
		for (Property<?> property : state.getProperties()) {
			if (property.getName().equals(name)) {
				return text(state, property);
			}
		}
		return null;
	}

	/**
	 * The other half of a two block structure (bed head/foot, door upper/lower, tall plant), detected
	 * through the {@code part}/{@code half} properties so modded blocks following vanilla work too.
	 */
	public static BlockPos siblingPosition(BlockState state, BlockPos pos) {
		String part = propertyText(state, "part");
		if (part != null) {
			boolean upper = "head".equals(part) || "upper".equals(part);
			boolean lower = "foot".equals(part) || "lower".equals(part);
			if (!upper && !lower) {
				return null;
			}
			String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
			Direction facing = facingOf(state);
			if (id.endsWith("bed") && facing != null) {
				return upper ? pos.relative(facing.getOpposite()) : pos.relative(facing);
			}
			return upper ? pos.below() : pos.above();
		}
		String half = propertyText(state, "half");
		if (half != null) {
			return "upper".equals(half) ? pos.below() : pos.above();
		}
		return null;
	}

	private static Direction facingOf(BlockState state) {
		String facing = propertyText(state, "facing");
		if (facing == null) {
			facing = propertyText(state, "direction");
		}
		return facing == null ? null : Direction.byName(facing);
	}

	// ------------------------------------------------------------------ block entity nbt

	public static byte[] blockEntityData(ServerLevel level, BlockPos pos) {
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (blockEntity == null) {
			return new byte[0];
		}
		return writeTag(blockEntity.saveWithFullMetadata(level.registryAccess()));
	}

	public static byte[] writeTag(CompoundTag tag) {
		if (tag == null) {
			return new byte[0];
		}
		try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				DataOutputStream out = new DataOutputStream(bytes)) {
			NbtIo.write(tag, out);
			out.flush();
			return bytes.toByteArray();
		} catch (IOException e) {
			return new byte[0];
		}
	}

	public static CompoundTag readTag(byte[] data) {
		if (data == null || data.length == 0) {
			return null;
		}
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
			return NbtIo.read(in, NbtAccounter.unlimitedHeap());
		} catch (IOException e) {
			return null;
		}
	}

	// ------------------------------------------------------------------ items

	public static byte[] encodeItem(ItemStack stack, RegistryAccess registries) {
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
		ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, stack == null ? ItemStack.EMPTY : stack);
		byte[] out = new byte[buffer.readableBytes()];
		buffer.readBytes(out);
		return out;
	}

	public static ItemStack decodeItem(byte[] data, RegistryAccess registries) {
		if (data == null || data.length == 0) {
			return ItemStack.EMPTY;
		}
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), registries);
		try {
			return ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
		} catch (RuntimeException e) {
			return ItemStack.EMPTY;
		}
	}

	/** Slot index to encoded stack, only for non empty slots (an empty slot needs no restore data). */
	public static Map<Integer, byte[]> encodeSlots(java.util.List<ItemStack> stacks, RegistryAccess registries) {
		Map<Integer, byte[]> encoded = new LinkedHashMap<>();
		for (int slot = 0; slot < stacks.size(); slot++) {
			ItemStack stack = stacks.get(slot);
			if (stack != null && !stack.isEmpty()) {
				encoded.put(slot, encodeItem(stack, registries));
			}
		}
		return encoded;
	}

	// ------------------------------------------------------------------ positions & fingerprints

	public static long pack(BlockPos pos) {
		return ((long) (pos.getX() & 0x3FFFFFF) << 38) | ((long) (pos.getZ() & 0x3FFFFFF) << 12)
				| (pos.getY() & 0xFFF);
	}

	public static BlockPos unpack(long packed) {
		return new BlockPos((int) (packed >> 38), (int) (packed << 52 >> 52), (int) (packed << 26 >> 38));
	}

	public static String dimensionId(ServerLevel level) {
		return level.dimension().identifier().toString();
	}

	/** Digest of a block position's state plus its block entity payload. */
	public static String blockFingerprint(BlockState state, byte[] blockEntityNbt) {
		int hash = 0;
		for (byte b : blockEntityNbt) {
			hash = 31 * hash + b;
		}
		return encodeBlockState(state) + "#" + blockEntityNbt.length + ":" + Integer.toHexString(hash);
	}

	public static String itemFingerprint(Map<Integer, byte[]> items) {
		long total = 0L;
		long count = 0L;
		for (Map.Entry<Integer, byte[]> entry : new TreeMap<>(items).entrySet()) {
			total = total * 31L + entry.getKey() * 31L + entry.getValue().length;
			count += entry.getValue().length;
		}
		return "items:" + items.size() + ":" + count + ":" + Long.toHexString(total);
	}
}
