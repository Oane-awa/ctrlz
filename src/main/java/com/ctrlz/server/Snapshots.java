package com.ctrlz.server;

import io.netty.buffer.Unpooled;
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
import net.minecraft.core.BlockPos;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Binary and textual helpers for capturing vanilla state.
 *
 * <p>Block states are stored as {@code namespace:block[prop=value,...]} which survives restarts and
 * stays readable in the history screen, block entity payloads use the vanilla NBT writer, and item
 * stacks use the registry aware stream codec so every data component (durability, enchantments,
 * custom names, container contents) round trips without loss.
 */
public final class Snapshots {
	private Snapshots() {
	}

	public static String encodeBlockState(BlockState state) {
		StringBuilder builder = new StringBuilder();
		builder.append(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		if (!state.getProperties().isEmpty()) {
			builder.append('[');
			boolean first = true;
			for (Property<?> property : state.getProperties()) {
				if (!first) {
					builder.append(',');
				}
				builder.append(property.getName()).append('=').append(valueName(state, property));
				first = false;
			}
			builder.append(']');
		}
		return builder.toString();
	}

	private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
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
		Identifier id;
		try {
			id = Identifier.parse(idPart);
		} catch (RuntimeException e) {
			return null;
		}
		Block block = BuiltInRegistries.BLOCK.getValue(id);
		if (block == null) {
			return null;
		}
		BlockState state = block.defaultBlockState();
		if (propsPart.isEmpty()) {
			return state;
		}
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
	private static <T extends Comparable<T>> BlockState setTyped(BlockState state, Property<?> property, String value) {
		Property<T> typed = (Property<T>) property;
		Optional<T> parsed = typed.getValue(value);
		return parsed.map(t -> state.setValue(typed, t)).orElse(state);
	}

	public static byte[] blockEntityData(ServerLevel level, BlockPos pos) {
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (blockEntity == null) {
			return new byte[0];
		}
		CompoundTag tag = blockEntity.saveWithFullMetadata(level.registryAccess());
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

	public static byte[] encodeItems(List<ItemStack> stacks, RegistryAccess registries) {
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
		buffer.writeVarInt(stacks.size());
		for (ItemStack stack : stacks) {
			ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, stack == null ? ItemStack.EMPTY : stack);
		}
		byte[] out = new byte[buffer.readableBytes()];
		buffer.readBytes(out);
		return out;
	}

	public static List<ItemStack> decodeItems(byte[] data, RegistryAccess registries) {
		List<ItemStack> result = new ArrayList<>();
		if (data == null || data.length == 0) {
			return result;
		}
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), registries);
		try {
			int size = buffer.readVarInt();
			for (int i = 0; i < size; i++) {
				result.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
			}
		} catch (RuntimeException e) {
			result.clear();
		}
		return result;
	}

	public static byte[] encodeSingleItem(ItemStack stack, RegistryAccess registries) {
		return encodeItems(List.of(stack == null ? ItemStack.EMPTY : stack), registries);
	}

	public static ItemStack decodeSingleItem(byte[] data, RegistryAccess registries) {
		List<ItemStack> items = decodeItems(data, registries);
		return items.isEmpty() ? ItemStack.EMPTY : items.get(0);
	}

	public static String describe(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return "";
		}
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		return stack.getCount() > 1 ? id + " x" + stack.getCount() : id;
	}
}
