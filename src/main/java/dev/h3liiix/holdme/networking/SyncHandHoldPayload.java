package dev.h3liiix.holdme.networking;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record SyncHandHoldPayload(String holderUuid, String holdeeUuid, boolean leftHand, boolean holding) implements CustomPacketPayload {
    public static final Type<SyncHandHoldPayload> ID = new Type<>(PacketConstants.SYNC_HAND_HOLD_PACKET_ID);
    public static final StreamCodec<ByteBuf, SyncHandHoldPayload> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, SyncHandHoldPayload::holderUuid, ByteBufCodecs.STRING_UTF8, SyncHandHoldPayload::holdeeUuid, ByteBufCodecs.BOOL, SyncHandHoldPayload::leftHand, ByteBufCodecs.BOOL, SyncHandHoldPayload::holding, SyncHandHoldPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }

}