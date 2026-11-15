package com.ctrlz.v2.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Outcome of an undo attempt, sent back so the client can show it on the action bar.
 *
 * <p>{@code engine} is included deliberately: the biggest trouble when two engines coexist is not
 * knowing which one handled a key press, so every result says who produced it.
 */
public record UndoResultPayload(String status, int restored, String detail, String engine)
		implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<UndoResultPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath("ctrlz", "v2_undo_result"));

	public static final StreamCodec<RegistryFriendlyByteBuf, UndoResultPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, UndoResultPayload::status,
			ByteBufCodecs.VAR_INT, UndoResultPayload::restored,
			ByteBufCodecs.STRING_UTF8, UndoResultPayload::detail,
			ByteBufCodecs.STRING_UTF8, UndoResultPayload::engine,
			UndoResultPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
