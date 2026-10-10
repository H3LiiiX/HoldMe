package dev.h3liiix.holdme.networking;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> clients: the state of one uppies pair. {@code stage} is one of {@code Uppies.SYNC_*}.
 * The rider is the player asking to be picked up (and later the one sitting on the hands).
 */
public record SyncUppiesPayload(String riderUuid, String carrierUuid, int stage) implements CustomPacketPayload {
    public static final Type<SyncUppiesPayload> ID = new Type<>(PacketConstants.SYNC_UPPIES_PACKET_ID);
    public static final StreamCodec<ByteBuf, SyncUppiesPayload> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, SyncUppiesPayload::riderUuid, ByteBufCodecs.STRING_UTF8, SyncUppiesPayload::carrierUuid, ByteBufCodecs.VAR_INT, SyncUppiesPayload::stage, SyncUppiesPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
