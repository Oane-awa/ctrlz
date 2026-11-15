package com.ctrlz.v2.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * "Undo the newest valid operation." Carries no data on purpose: the server decides everything, the
 * client never proposes an operation id.
 */
public record UndoRequestPayload() implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<UndoRequestPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath("ctrlz", "v2_undo_request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, UndoRequestPayload> CODEC = StreamCodec
			.unit(new UndoRequestPayload());

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
