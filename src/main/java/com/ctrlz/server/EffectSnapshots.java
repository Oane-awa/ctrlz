package com.ctrlz.server;

import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Complete status effect snapshots for players and mobs.
 *
 * <p>Encoding uses the vanilla {@link MobEffectInstance#STREAM_CODEC}, so modded effects work through
 * their registry id, and the hidden effect chain, ambient/particle/icon flags and the exact remaining
 * duration all round trip without a hand written field list. A capture that contains zero effects is a
 * non empty byte array (one var-int), which is what makes "captured but empty" distinguishable from
 * "never captured" ({@code byte[0]}) — the difference decides whether a restore may clear effects.
 */
public final class EffectSnapshots {
	/** Defensive cap: a broken or hostile payload must not allocate an unbounded list. */
	private static final int MAX_EFFECTS = 256;

	private EffectSnapshots() {
	}

	/** Always returns at least one byte, so an effect free entity is a real capture. */
	public static byte[] encode(LivingEntity entity) {
		Collection<MobEffectInstance> active = entity.getActiveEffects();
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), entity.level().registryAccess());
		buffer.writeVarInt(active.size());
		for (MobEffectInstance instance : active) {
			MobEffectInstance.STREAM_CODEC.encode(buffer, instance);
		}
		byte[] out = new byte[buffer.readableBytes()];
		buffer.readBytes(out);
		return out;
	}

	public static boolean isCaptured(byte[] data) {
		return data != null && data.length > 0;
	}

	/** Decoded effects, or {@code null} when the payload is absent or unreadable. */
	public static List<MobEffectInstance> decode(byte[] data, RegistryAccess registries) {
		if (!isCaptured(data)) {
			return null;
		}
		RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), registries);
		try {
			int size = buffer.readVarInt();
			if (size < 0 || size > MAX_EFFECTS) {
				return null;
			}
			List<MobEffectInstance> decoded = new ArrayList<>(size);
			for (int i = 0; i < size; i++) {
				MobEffectInstance instance = MobEffectInstance.STREAM_CODEC.decode(buffer);
				if (instance != null) {
					decoded.add(instance);
				}
			}
			return decoded;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Replaces the entity's whole effect set with the captured one.
	 *
	 * <p>Every present effect is removed first and then only the captured instances are added, so an
	 * effect that did not exist at capture time is gone, an effect with a different amplifier or
	 * duration is recreated instead of merged (vanilla {@code addEffect} would take the longer
	 * duration), and the attribute modifiers are recomputed exactly once — removing and re-adding is
	 * what keeps speed/strength from stacking on every restore.
	 *
	 * @return true when the payload was usable (including a captured empty set), false when it was
	 *         absent or unreadable and the entity was therefore left untouched
	 */
	public static boolean apply(LivingEntity entity, byte[] data) {
		List<MobEffectInstance> target = decode(data, entity.level().registryAccess());
		if (target == null) {
			return false;
		}
		for (Holder<MobEffect> holder : new ArrayList<>(entity.getActiveEffectsMap().keySet())) {
			entity.removeEffect(holder);
		}
		for (MobEffectInstance instance : target) {
			MobEffectInstance copy = new MobEffectInstance(instance);
			if (!entity.addEffect(copy) && !entity.hasEffect(copy.getEffect())) {
				// addEffect runs canBeAffected; a restore must reproduce the recorded state anyway.
				entity.forceAddEffect(new MobEffectInstance(instance), null);
			}
		}
		return true;
	}

	/** Reads back the encoded payload for display in the history detail screen. */
	public static String describe(byte[] data, RegistryAccess registries) {
		List<MobEffectInstance> decoded = decode(data, registries);
		if (decoded == null) {
			return isCaptured(data) ? "unreadable" : "not captured";
		}
		if (decoded.isEmpty()) {
			return "none";
		}
		StringBuilder builder = new StringBuilder();
		int shown = Math.min(decoded.size(), 4);
		for (int i = 0; i < shown; i++) {
			if (i > 0) {
				builder.append(", ");
			}
			MobEffectInstance instance = decoded.get(i);
			builder.append(BuiltInRegistries.MOB_EFFECT.getKey(instance.getEffect().value()))
					.append(' ').append(roman(instance.getAmplifier()))
					.append(" (").append(instance.getDuration() / 20).append('s');
			if (!instance.isVisible()) {
				builder.append(", hidden particles");
			}
			if (!instance.showIcon()) {
				builder.append(", no icon");
			}
			builder.append(')');
		}
		if (decoded.size() > shown) {
			builder.append(", +").append(decoded.size() - shown).append(" more");
		}
		return builder.toString();
	}

	private static String roman(int amplifier) {
		return switch (amplifier) {
			case 0 -> "I";
			case 1 -> "II";
			case 2 -> "III";
			case 3 -> "IV";
			case 4 -> "V";
			default -> "+" + amplifier;
		};
	}
}
