package dev.h3liiix.holdme.networking;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ToggleHandHoldPayload(String uuid, boolean leftHand) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ToggleHandHoldPayload> ID = new CustomPacketPayload.Type<>(PacketConstants.REQUEST_HAND_HOLD_PACKET_ID);
    public static final StreamCodec<ByteBuf, ToggleHandHoldPayload> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ToggleHandHoldPayload::uuid, ByteBufCodecs.BOOL, ToggleHandHoldPayload::leftHand, ToggleHandHoldPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}