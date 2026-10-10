package dev.h3liiix.holdme.networking;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client -> server: the uppies key was pressed. {@code targetUuid} is the player under the crosshair, or an
 * empty string when the player is not looking at anyone. The server decides what the press means (ask,
 * cancel, give uppies, or put the rider down).
 */
public record UppiesPayload(String targetUuid) implements CustomPacketPayload {
    public static final Type<UppiesPayload> ID = new Type<>(PacketConstants.UPPIES_PACKET_ID);
    public static final StreamCodec<ByteBuf, UppiesPayload> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, UppiesPayload::targetUuid, UppiesPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
