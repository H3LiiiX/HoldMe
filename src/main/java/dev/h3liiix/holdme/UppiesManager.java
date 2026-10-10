package dev.h3liiix.holdme;

import dev.h3liiix.holdme.networking.SyncUppiesPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of "uppies".
 *
 * Flow: player A presses the key while looking at B -> A asks (both arms reach out towards B, everybody is told).
 * B, who is now prompted, presses the key while looking at A -> B gives uppies: A is made to ride B (vanilla
 * riding, so movement, camera and sneak-to-dismount all behave like any other mount) and B holds both arms up.
 * B pressing the key again puts A down. A pressing the key while asking cancels the request.
 *
 * "Rider" is always the player being lifted, "carrier" the player lifting them.
 */
public final class UppiesManager {
    private UppiesManager() {}

    /** How far apart (blocks) two players can be to start asking / to give uppies. */
    private static final double ASK_RANGE = 6.0;
    /** An unanswered request is dropped when the players get further apart than this. */
    private static final double ASK_BREAK_RANGE = 8.0;
    /** The rider's head sits ~3.1 blocks above the carrier's feet. Once the rider has been inside blocks for this
     *  many ticks (low ceiling) they are put down instead of being left to suffocate. */
    private static final int BLOCKED_GRACE_TICKS = 10;
    /** Active pairs are re-broadcast this often so clients that only just loaded a player catch up. */
    private static final int RESYNC_INTERVAL_TICKS = 20;

    private static final class Entry {
        final ServerPlayer rider;
        final ServerPlayer carrier;
        boolean accepted;
        int blockedTicks;

        Entry(ServerPlayer rider, ServerPlayer carrier) {
            this.rider = rider;
            this.carrier = carrier;
        }
    }

    /** Keyed by the rider: a player can only ask / ride one person at a time. */
    private static final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private static int ticks;

    // ---------------------------------------------------------------------------------------------
    // Key press
    // ---------------------------------------------------------------------------------------------

    public static void handleKey(MinecraftServer server, ServerPlayer player, String targetUuid) {
        // Carrying someone: put them down.
        Entry carrying = findByCarrier(player);
        if (carrying != null) {
            end(server, carrying, null);
            return;
        }

        ServerPlayer target = lookup(server, targetUuid);

        // Asking (or riding): looking at somebody else re-aims the request, otherwise the key cancels it.
        Entry own = entries.get(player.getUUID());
        if (own != null) {
            boolean retarget = !own.accepted && target != null && !target.getUUID().equals(own.carrier.getUUID()) && !target.getUUID().equals(player.getUUID());
            end(server, own, null);
            if (!retarget) return;
        }

        if (target == null || target.getUUID().equals(player.getUUID())) return;
        if (target.level() != player.level() || player.distanceToSqr(target) > ASK_RANGE * ASK_RANGE) return;

        // The other player is asking me: give them uppies.
        Entry theirs = entries.get(target.getUUID());
        if (theirs != null && !theirs.accepted && theirs.carrier.getUUID().equals(player.getUUID())) {
            HoldMe.cancelAll(server, player);
            HoldMe.cancelAll(server, target);
            accept(server, theirs);
            return;
        }

        // Otherwise start asking.
        if (player.isPassenger()) return;
        
        HoldMe.cancelAll(server, player);

        Entry ask = new Entry(player, target);
        entries.put(player.getUUID(), ask);
        broadcast(server, ask, Uppies.SYNC_ASKING, null);
    }

    private static void accept(MinecraftServer server, Entry entry) {
        ServerPlayer rider = entry.rider;
        ServerPlayer carrier = entry.carrier;

        boolean busy = carrier.isPassenger() || carrier.isVehicle() || rider.isPassenger() || rider.isVehicle();
        if (busy || !rider.startRiding(carrier, true, true)) {
            carrier.sendSystemMessage(Component.translatable("holdme.uppies_failed"), false);
            return;
        }

        entry.accepted = true;
        entry.blockedTicks = 0;
        carrier.connection.send(new net.minecraft.network.protocol.game.ClientboundSetPassengersPacket(carrier));
        broadcast(server, entry, Uppies.SYNC_ACCEPTED, null);
        playSound(rider, carrier, true);
    }

    // ---------------------------------------------------------------------------------------------
    // Server tick / lifecycle
    // ---------------------------------------------------------------------------------------------

    public static void tick(MinecraftServer server) {
        ticks++;
        if (entries.isEmpty()) return;

        for (Entry entry : new ArrayList<>(entries.values())) {
            ServerPlayer rider = entry.rider;
            ServerPlayer carrier = entry.carrier;

            if (isStale(server, rider) || isStale(server, carrier) || !rider.isAlive() || !carrier.isAlive()
                    || rider.level() != carrier.level()) {
                end(server, entry, null);
                continue;
            }

            if (!entry.accepted) {
                if (rider.distanceToSqr(carrier) > ASK_BREAK_RANGE * ASK_BREAK_RANGE) end(server, entry, null);
                continue;
            }

            // The rider climbed down (sneak), was dismounted, or teleported away.
            if (rider.getVehicle() != carrier) {
                end(server, entry, null);
                continue;
            }

            // Low ceiling: put the rider down rather than letting them suffocate.
            if (!rider.level().noCollision(rider, rider.getBoundingBox())) {
                if (++entry.blockedTicks > BLOCKED_GRACE_TICKS) {
                    end(server, entry, null);
                    continue;
                }
            } else {
                entry.blockedTicks = 0;
            }
        }

        if (ticks % RESYNC_INTERVAL_TICKS == 0) {
            for (Entry entry : entries.values()) {
                broadcast(server, entry, entry.accepted ? Uppies.SYNC_ACCEPTED : Uppies.SYNC_ASKING, null);
            }
        }
    }

    /** A player joined: tell them about every pair that is currently active. */
    public static void onJoin(ServerPlayer joining) {
        for (Entry entry : entries.values()) {
            send(joining, entry, entry.accepted ? Uppies.SYNC_ACCEPTED : Uppies.SYNC_ASKING);
        }
    }

    /** A player left: end every pair they were part of. */
    public static void onDisconnect(MinecraftServer server, ServerPlayer leaving) {
        for (Entry entry : new ArrayList<>(entries.values())) {
            if (entry.rider.getUUID().equals(leaving.getUUID()) || entry.carrier.getUUID().equals(leaving.getUUID())) {
                end(server, entry, leaving);
            }
        }
    }

    public static void cancelAll(MinecraftServer server, ServerPlayer player) {
        for (Entry entry : new ArrayList<>(entries.values())) {
            if (entry.rider.getUUID().equals(player.getUUID()) || entry.carrier.getUUID().equals(player.getUUID())) {
                end(server, entry, null);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private static void end(MinecraftServer server, Entry entry, ServerPlayer exclude) {
        entries.remove(entry.rider.getUUID());
        if (entry.accepted) {
            if (entry.rider.getVehicle() == entry.carrier) {
                entry.rider.stopRiding();
            }
            if (!isStale(server, entry.carrier)) {
                entry.carrier.connection.send(new net.minecraft.network.protocol.game.ClientboundSetPassengersPacket(entry.carrier));
            }
        }
        broadcast(server, entry, Uppies.SYNC_ENDED, exclude);
        if (entry.accepted && !isStale(server, entry.rider) && !isStale(server, entry.carrier)) {
            playSound(entry.rider, entry.carrier, false);
        }
    }

    private static Entry findByCarrier(ServerPlayer carrier) {
        for (Entry entry : entries.values()) {
            if (entry.accepted && entry.carrier.getUUID().equals(carrier.getUUID())) return entry;
        }
        return null;
    }

    private static ServerPlayer lookup(MinecraftServer server, String uuid) {
        if (uuid == null || uuid.isEmpty()) return null;
        try {
            return server.getPlayerList().getPlayer(UUID.fromString(uuid));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isStale(MinecraftServer server, ServerPlayer player) {
        return player.isRemoved() || server.getPlayerList().getPlayer(player.getUUID()) != player;
    }

    private static void broadcast(MinecraftServer server, Entry entry, int stage, ServerPlayer exclude) {
        for (ServerPlayer receiver : server.getPlayerList().getPlayers()) {
            if (exclude != null && receiver.getUUID().equals(exclude.getUUID())) continue;
            send(receiver, entry, stage);
        }
    }

    private static void send(ServerPlayer receiver, Entry entry, int stage) {
        ServerPlayNetworking.send(receiver, new SyncUppiesPayload(entry.rider.getUUID().toString(), entry.carrier.getUUID().toString(), stage));
    }

    private static void playSound(ServerPlayer a, ServerPlayer b, boolean pickUp) {
        double x = (a.getX() + b.getX()) / 2.0;
        double y = (a.getY() + b.getY()) / 2.0;
        double z = (a.getZ() + b.getZ()) / 2.0;
        if (pickUp) {
            a.level().playSound(null, x, y, z, SoundEvents.LEAD_TIED, SoundSource.PLAYERS, 1.0f, 1.2f);
        } else {
            a.level().playSound(null, x, y, z, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5f, 0.8f);
        }
    }
}
