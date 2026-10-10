package dev.h3liiix.holdme.networking;

import net.minecraft.resources.Identifier;

import static dev.h3liiix.holdme.HoldMe.NAMESPACE;

public class PacketConstants {
    public static final Identifier REQUEST_HAND_HOLD_PACKET_ID = Identifier.fromNamespaceAndPath(NAMESPACE, "request_hand_hold");
    public static final Identifier SYNC_HAND_HOLD_PACKET_ID = Identifier.fromNamespaceAndPath(NAMESPACE, "sync_hand_hold");
    public static final Identifier UPPIES_PACKET_ID = Identifier.fromNamespaceAndPath(NAMESPACE, "uppies");
    public static final Identifier SYNC_UPPIES_PACKET_ID = Identifier.fromNamespaceAndPath(NAMESPACE, "sync_uppies");
}
