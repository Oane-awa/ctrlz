package com.ctrlz.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Single wire message for every CtrlZ interaction.
 *
 * <p>An opcode plus a JSON body keeps the packet layer to one small codec while the payload shapes
 * can evolve with the storage format. Nothing here is trusted by the server: the opcode decides
 * which handler runs and every request is re-validated server side.
 */
public record CtrlZPayload(int opcode, String data) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<CtrlZPayload> TYPE = new CustomPacketPayload.Type<>(
			Identifier.fromNamespaceAndPath("ctrlz", "msg"));

	public static final StreamCodec<RegistryFriendlyByteBuf, CtrlZPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, CtrlZPayload::opcode,
			ByteBufCodecs.STRING_UTF8, CtrlZPayload::data,
			CtrlZPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
