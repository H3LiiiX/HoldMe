package dev.h3liiix.holdme;

import dev.h3liiix.holdme.networking.ToggleHandHoldPayload;
import dev.h3liiix.holdme.networking.SyncHandHoldPayload;
import dev.h3liiix.holdme.networking.SyncUppiesPayload;
import dev.h3liiix.holdme.networking.UppiesPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class HoldMe implements ModInitializer {
    public static final String NAMESPACE = "holdme";
    public static ArrayList<HandHold> handHoldings = new ArrayList<>();

    // --- Tuning -----------------------------------------------------------------------------------
    // The hold behaves like a short leash: nothing happens while the players are within HOLD_SLACK, beyond
    // that the player who is left behind is gently pulled along at a speed that grows with the stretch (but
    // never beyond MAX_PULL_SPEED). If the leash is stretched further than the MAX_* distances it breaks.

    /** Horizontal distance (blocks) the players can be apart before anyone gets pulled. */
    private static final double HOLD_SLACK = 1.25;
    /** Pull speed (blocks/tick) per block of stretch beyond HOLD_SLACK. Deliberately low: the server only sees
     *  a player's position after network latency, and a stiff correction on delayed data oscillates (that is
     *  the rubberbanding). */
    private static final double PULL_GAIN = 0.25;
    /** Fastest speed (blocks/tick) a player is ever dragged at. Vanilla sprinting is ~0.28. */
    private static final double MAX_PULL_SPEED = 0.36;
    /** The most velocity (blocks/tick) a single pull may add on top of what the player already has. */
    private static final double MAX_PULL_CORRECTION = 0.5;
    /** The pull speed may rise / fall by at most this much per tick, so dragging starts and stops smoothly. */
    private static final double PULL_RAMP_UP = 0.06;
    private static final double PULL_RAMP_DOWN = 0.10;
    /** Pull speeds below this are treated as zero. */
    private static final double MIN_PULL_SPEED = 1.0E-3;
    /** The stretch is judged this many ticks ahead using how fast the players are closing in / separating, so the
     *  pull is released before the dragged player arrives instead of after they have overshot. */
    private static final double LOOKAHEAD_TICKS = 3.0;
    /** The hold breaks when the players get further apart than this horizontally... */
    private static final double MAX_HORIZONTAL_DISTANCE = 8.0;
    /** ...or further apart than this vertically (e.g. one of them falls off a ledge). */
    private static final double MAX_VERTICAL_DISTANCE = 8.0;
    /** How much harder a crouching player is to move than a standing one. */
    private static final double CROUCH_WEIGHT = 5.0;
    /**
     * When neither player crouches, the one who is moving away from the other "leads" and is never
     * pulled back. This is the difference in "drive" (blocks/tick) at which the leader is fully free.
     */
    private static final double LEADER_SPEED_DIFFERENCE = 0.1;
    /** Movement bigger than this in one tick is a teleport, not walking. */
    private static final double MAX_TRACKED_MOVE_SQR = 4.0 * 4.0;
    /** Measured movement is averaged with the previous tick: position packets arrive unevenly (0, 1 or 2 per
     *  server tick), which would make the measured speed - and so the pull - jittery. */
    private static final double MOVEMENT_SMOOTHING = 0.5;

    // Auto-step for the player being pulled along (they have no input, so vanilla auto-jump never triggers).
    /** How far ahead (blocks) to look for a block in the way. */
    private static final double STEP_PROBE_DISTANCE = 0.4;
    /** A block that is still in the way this high up cannot be walked over by vanilla step-up (0.6). */
    private static final double STEP_OBSTACLE_HEIGHT = 0.65;
    /** Space needed above a one-block step for the player to jump onto it. */
    private static final double STEP_CLEARANCE_HEIGHT = 1.01;
    /** A normal vanilla jump - just enough for a one-block step. It is never boosted: extra upward velocity is
     *  what used to launch players. */
    private static final double JUMP_VELOCITY = 0.42;
    /** Ticks of the jump that are simulated by the server (the rise of a vanilla jump), because the server only
     *  learns that the player has left the ground a few ticks later. */
    private static final int STEP_ARC_TICKS = 6;
    /** Ticks before another assisted jump can start. */
    private static final int STEP_COOLDOWN_TICKS = 8;
    /** Upward velocity (blocks/tick) a pull may ever give a player, a safety net against launching. */
    private static final double MAX_ASSISTED_RISE = 0.5;

    // Vanilla client movement constants, used to rebuild a player's velocity from their real movement.
    private static final double GRAVITY = 0.08;
    private static final double VERTICAL_DRAG = 0.98;
    private static final double GROUND_FRICTION = 0.6 * 0.91;
    private static final double AIR_FRICTION = 0.91;

    /** Position / movement of every player during the last tick. Players move client-side, so the
     *  server's own deltaMovement for them is not reliable - the real movement is measured instead. */
    private static final Map<UUID, Vec3> lastPositions = new HashMap<>();
    private static final Map<UUID, Vec3> lastMovements = new HashMap<>();
    /** The speed (blocks/tick) each player is currently being dragged at, ramped up and down smoothly. */
    private static final Map<UUID, Double> pullSpeeds = new HashMap<>();
    private static final Map<UUID, StepState> stepStates = new HashMap<>();

    /** Progress of the assisted jump onto a one-block step. */
    private static final class StepState {
        double velocity;
        int arcTicks;
        int cooldown;
    }

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.serverboundPlay().register(ToggleHandHoldPayload.ID, ToggleHandHoldPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SyncHandHoldPayload.ID, SyncHandHoldPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(UppiesPayload.ID, UppiesPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SyncUppiesPayload.ID, SyncUppiesPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(UppiesPayload.ID, (payload, context) ->
                UppiesManager.handleKey(context.server(), context.player(), payload.targetUuid()));

        ServerPlayNetworking.registerGlobalReceiver(ToggleHandHoldPayload.ID, (payload, context) -> {
            var holdee = context.server().getPlayerList().getPlayer(UUID.fromString(payload.uuid()));
            if (holdee == null) return;
            var holder = context.player();

            var reach = new HandHold(holder, holdee, payload.leftHand());
            if (handHoldings.contains(reach)) {
                boolean wasMutual = hasMutualConnection(holder, holdee);
                handHoldings.remove(reach);
                syncHandHoldPayloadToEveryone(context.server().getPlayerList(), holder.getUUID().toString(), payload.uuid(), payload.leftHand(), false);
                
                HandHold mutualL = new HandHold(holdee, holder, true);
                if (handHoldings.contains(mutualL)) {
                    handHoldings.remove(mutualL);
                    syncHandHoldPayloadToEveryone(context.server().getPlayerList(), holdee.getUUID().toString(), holder.getUUID().toString(), true, false);
                }
                HandHold mutualR = new HandHold(holdee, holder, false);
                if (handHoldings.contains(mutualR)) {
                    handHoldings.remove(mutualR);
                    syncHandHoldPayloadToEveryone(context.server().getPlayerList(), holdee.getUUID().toString(), holder.getUUID().toString(), false, false);
                }
                if (wasMutual && !hasMutualConnection(holder, holdee)) {
                    playDisconnectSound(holder, holdee);
                }
            } else {
                if (holder.distanceToSqr(holdee) > 6.0 * 6.0) return;
                
                UppiesManager.cancelAll(context.server(), holder);

                boolean wasMutual = hasMutualConnection(holder, holdee);
                handHoldings.add(reach);
                syncHandHoldPayloadToEveryone(context.server().getPlayerList(), holder.getUUID().toString(), payload.uuid(), payload.leftHand(), true);
                
                boolean isMutualNow = hasMutualConnection(holder, holdee);
                if (!wasMutual && isMutualNow) {
                    UppiesManager.cancelAll(context.server(), holdee);
                    playConnectSound(holder, holdee);
                }
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            trackMovement(server);
            tickHandHolds(server);
            UppiesManager.tick(server);
        });

        ServerPlayConnectionEvents.JOIN.register(((handler, sender, server) -> {
            UppiesManager.onJoin(handler.player);
            for (var handHold : handHoldings) {
                syncHandHoldPayload(handler.player, handHold.holder, handHold.holdee, handHold.leftHand, true);
            }
        }));

        ServerPlayConnectionEvents.DISCONNECT.register(((handler, server) -> {
            var leaving = handler.player;
            UppiesManager.onDisconnect(server, leaving);
            var removed = new ArrayList<HandHold>();
            for (var handHold : handHoldings) {
                if (handHold.holder.equals(leaving) || handHold.holdee.equals(leaving)) removed.add(handHold);
            }

            java.util.Set<String> processedPairs = new java.util.HashSet<>();
            for (var handHold : removed) {
                 ServerPlayer other = handHold.holder.equals(leaving) ? handHold.holdee : handHold.holder;
                 if (hasMutualConnection(leaving, other)) {
                     if (processedPairs.add(pairKey(leaving, other))) {
                         playDisconnectSound(leaving, other);
                     }
                 }
            }

            handHoldings.removeAll(removed);

            // The players still online have to be told to let go, otherwise their hand stays stuck to nobody.
            for (var handHold : removed) {
                for (var other : server.getPlayerList().getPlayers()) {
                    if (other.equals(leaving)) continue;
                    syncHandHoldPayload(other, handHold.holder, handHold.holdee, handHold.leftHand, false);
                }
            }

            lastPositions.remove(handler.player.getUUID());
            lastMovements.remove(handler.player.getUUID());
            pullSpeeds.remove(handler.player.getUUID());
            stepStates.remove(handler.player.getUUID());
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // Server tick
    // ---------------------------------------------------------------------------------------------

    private static void trackMovement(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            Vec3 position = player.position();
            Vec3 previous = lastPositions.put(id, position);

            Vec3 movement;
            Vec3 raw = previous == null ? Vec3.ZERO : position.subtract(previous);
            if (previous == null || raw.lengthSqr() > MAX_TRACKED_MOVE_SQR) {
                movement = Vec3.ZERO; // first sighting or teleported
            } else {
                Vec3 before = lastMovements.getOrDefault(id, Vec3.ZERO);
                movement = before.scale(MOVEMENT_SMOOTHING).add(raw.scale(1.0 - MOVEMENT_SMOOTHING));
            }
            lastMovements.put(id, movement);

            StepState step = stepStates.get(id);
            if (step != null && step.cooldown > 0) step.cooldown--;
        }
    }

    private static void tickHandHolds(MinecraftServer server) {
        var toRemove = new ArrayList<HandHold>();
        var processedPairs = new HashSet<String>();

        for (var handHold : handHoldings) {
            ServerPlayer holder = handHold.holder;
            ServerPlayer holdee = handHold.holdee;

            if (holder == null || holdee == null) continue;

            // Someone left the server or respawned (the old ServerPlayer object is stale): let go.
            if (isStale(server, holder) || isStale(server, holdee)) {
                if (processedPairs.add(pairKey(holder, holdee))) breakHold(server, holder, holdee, toRemove);
                continue;
            }

            if (holder.level() != holdee.level()) {
                if (processedPairs.add(pairKey(holder, holdee))) {
                    breakHold(server, holder, holdee, toRemove);
                }
                continue;
            }

            // Direction from holder to holdee. Only the horizontal plane is used: the hold never
            // pulls anyone up or down, so falling off a ledge can no longer launch the player.
            double realDx = holdee.getX() - holder.getX();
            double realDz = holdee.getZ() - holder.getZ();
            double dy = holdee.getY() - holder.getY();
            double realHorizontal = Math.sqrt(realDx * realDx + realDz * realDz);

            if (realHorizontal > MAX_HORIZONTAL_DISTANCE || Math.abs(dy) > MAX_VERTICAL_DISTANCE) {
                if (processedPairs.add(pairKey(holder, holdee))) {
                    breakHold(server, holder, holdee, toRemove);
                }
                continue;
            }

            if (!isMutual(holder, holdee)) continue;
            // Two players can hold both hands (up to four entries) - only handle each pair once.
            if (!processedPairs.add(pairKey(holder, holdee))) continue;
            
            if (realHorizontal <= HOLD_SLACK) {
                releasePull(holder);
                releasePull(holdee);
                continue;
            }

            double ux = realDx / realHorizontal;
            double uz = realDz / realHorizontal;

            // How fast the gap is growing (negative while the players are closing in). Judging the stretch a few
            // ticks ahead releases the pull early enough that the dragged player does not overshoot.
            Vec3 holderMove = movementOf(holder);
            Vec3 holdeeMove = movementOf(holdee);
            double separationRate = (holdeeMove.x - holderMove.x) * ux + (holdeeMove.z - holderMove.z) * uz;
            double predicted = realHorizontal + separationRate * LOOKAHEAD_TICKS;

            if (predicted <= HOLD_SLACK) {
                releasePull(holder);
                releasePull(holdee);
                continue;
            }

            double strength = Math.min((predicted - HOLD_SLACK) * PULL_GAIN, MAX_PULL_SPEED);

            double[] shares = pullShares(holder, holdee, ux, uz);
            applyPull(holder, ux, uz, strength * shares[0]);    // towards the holdee
            applyPull(holdee, -ux, -uz, strength * shares[1]);  // towards the holder
        }

        for (var r : toRemove) handHoldings.remove(r);
    }

    /**
     * How the pulling force is shared out between the two players: {holderShare, holdeeShare}, always
     * summing to 1. A player with a share of 0 is not touched at all and walks at their normal speed.
     */
    private static double[] pullShares(ServerPlayer holder, ServerPlayer holdee, double ux, double uz) {
        // Crouching is the only way to hold someone back: the crouching player is hard to move, so the
        // other player is the one that gets held back.
        if (holder.isCrouching() || holdee.isCrouching()) {
            double holderWeight = holder.isCrouching() ? CROUCH_WEIGHT : 1.0;
            double holdeeWeight = holdee.isCrouching() ? CROUCH_WEIGHT : 1.0;
            double total = holderWeight + holdeeWeight;
            return new double[]{holdeeWeight / total, holderWeight / total};
        }

        // Nobody crouches: whoever is moving away from the other one leads and is never slowed down,
        // the other one (idle or catching up) is the one that gets pulled along.
        double holderDrive = drive(movementOf(holder), -ux, -uz);
        double holdeeDrive = drive(movementOf(holdee), ux, uz);
        double holderShare = 0.5 - (holderDrive - holdeeDrive) / (2.0 * LEADER_SPEED_DIFFERENCE);
        holderShare = Math.max(0.0, Math.min(1.0, holderShare));
        return new double[]{holderShare, 1.0 - holderShare};
    }

    /** How strongly a player is moving away from their partner ((awayX, awayZ) is the unit direction). */
    private static double drive(Vec3 movement, double awayX, double awayZ) {
        double away = movement.x * awayX + movement.z * awayZ;
        double sideways = Math.abs(movement.x * awayZ - movement.z * awayX);
        return away + 0.5 * sideways;
    }

    /**
     * Drag a player horizontally towards (dirX, dirZ) at (up to) {@code targetSpeed} blocks/tick.
     *
     * The velocity packet replaces the player's whole velocity on their client, so it is rebuilt from their real
     * movement (the server's own value for a player is stale) - otherwise it would wipe out their running /
     * jumping / falling. The pull only tops up the player's velocity towards the partner to the pull speed, it is
     * never added on top of it. (Adding it every tick feeds the pull back into the measured movement, which
     * snowballs into huge speeds.) The pull speed itself is ramped, so dragging eases in and out.
     */
    private static void applyPull(ServerPlayer player, double dirX, double dirZ, double targetSpeed) {
        UUID id = player.getUUID();

        double previous = pullSpeeds.getOrDefault(id, 0.0);
        double speed = previous + Math.max(-PULL_RAMP_DOWN, Math.min(PULL_RAMP_UP, targetSpeed - previous));
        if (speed < MIN_PULL_SPEED) {
            releasePull(player);
            return;
        }
        pullSpeeds.put(id, speed);
        if (player.isPassenger()) return;

        Vec3 moved = movementOf(player);
        double friction = player.onGround() ? GROUND_FRICTION : AIR_FRICTION;

        // The velocity the player would carry into the next tick anyway.
        double baseX = moved.x * friction;
        double baseZ = moved.z * friction;
        double alongPull = baseX * dirX + baseZ * dirZ;
        double correction = Math.max(0.0, Math.min(speed - alongPull, MAX_PULL_CORRECTION));

        double vx = baseX + dirX * correction;
        double vz = baseZ + dirZ * correction;
        double vy = verticalVelocity(player, moved, dirX, dirZ);

        player.setDeltaMovement(vx, vy, vz);
        player.syncVelocity = true;
    }

    /** The pull never adds upward speed, apart from a vanilla-sized jump to get up a one-block step. */
    private static double verticalVelocity(ServerPlayer player, Vec3 moved, double dirX, double dirZ) {
        StepState step = stepStates.computeIfAbsent(player.getUUID(), id -> new StepState());

        // Carry on with an assisted jump that is already underway. The server only learns that the player has left
        // the ground a few ticks later, so it cannot be left to the measured movement.
        if (step.arcTicks > 0) {
            double y = step.velocity;
            step.velocity = (step.velocity - GRAVITY) * VERTICAL_DRAG;
            step.arcTicks--;
            return y;
        }

        if (step.cooldown == 0 && shouldStepUp(player, dirX, dirZ)) {
            step.cooldown = STEP_COOLDOWN_TICKS;
            step.arcTicks = STEP_ARC_TICKS - 1;
            step.velocity = (JUMP_VELOCITY - GRAVITY) * VERTICAL_DRAG;
            return JUMP_VELOCITY;
        }

        if (player.isInWater() || player.isInLava() || player.onClimbable() || player.isFallFlying()) {
            return moved.y;
        }

        // Standing on something: vanilla keeps a small constant downward velocity. (The measured upward movement of
        // walking up stairs or slabs is a position change, not a velocity, and must not be sent back as one.)
        if (player.onGround()) return -GRAVITY * VERTICAL_DRAG;

        return Math.min((moved.y - GRAVITY) * VERTICAL_DRAG, MAX_ASSISTED_RISE);
    }

    /** Stop dragging a player: the pull speed fades out, the player's own velocity takes over. */
    private static void releasePull(ServerPlayer player) {
        UUID id = player.getUUID();
        Double speed = pullSpeeds.get(id);
        if (speed != null) {
            double faded = speed - PULL_RAMP_DOWN;
            if (faded < MIN_PULL_SPEED) pullSpeeds.remove(id);
            else pullSpeeds.put(id, faded);
        }
        StepState step = stepStates.get(id);
        if (step != null) step.arcTicks = 0;
    }

    /**
     * True when the player is on the ground with a one-block step in front of them (in the pull direction)
     * that they can jump onto. A player being dragged has no input, so this stands in for auto-jump -
     * without it they get stuck on the step while the other player runs on and the hold breaks.
     */
    private static boolean shouldStepUp(ServerPlayer player, double dirX, double dirZ) {
        if (!player.onGround()) return false;

        AABB box = player.getBoundingBox();
        double dx = dirX * STEP_PROBE_DISTANCE;
        double dz = dirZ * STEP_PROBE_DISTANCE;
        var level = player.level();

        boolean blocked = !level.noCollision(player, box.move(dx, STEP_OBSTACLE_HEIGHT, dz));
        boolean roomAbove = level.noCollision(player, box.move(0.0, STEP_CLEARANCE_HEIGHT, 0.0))
                && level.noCollision(player, box.move(dx, STEP_CLEARANCE_HEIGHT, dz));
        return blocked && roomAbove;
    }

    /** True when this ServerPlayer object is no longer the player that is online (left or respawned). */
    private static boolean isStale(MinecraftServer server, ServerPlayer player) {
        return player.isRemoved() || server.getPlayerList().getPlayer(player.getUUID()) != player;
    }

    private static Vec3 movementOf(ServerPlayer player) {
        return lastMovements.getOrDefault(player.getUUID(), Vec3.ZERO);
    }

    private static boolean isMutual(ServerPlayer a, ServerPlayer b) {
        return handHoldings.contains(new HandHold(b, a, true)) || handHoldings.contains(new HandHold(b, a, false));
    }

    private static boolean hasMutualConnection(ServerPlayer a, ServerPlayer b) {
        boolean aHoldsB = handHoldings.contains(new HandHold(a, b, true)) || handHoldings.contains(new HandHold(a, b, false));
        boolean bHoldsA = handHoldings.contains(new HandHold(b, a, true)) || handHoldings.contains(new HandHold(b, a, false));
        return aHoldsB && bHoldsA;
    }

    private static String pairKey(ServerPlayer a, ServerPlayer b) {
        String ua = a.getUUID().toString();
        String ub = b.getUUID().toString();
        return ua.compareTo(ub) <= 0 ? ua + ":" + ub : ub + ":" + ua;
    }

    /** Let go of every hand between the two players. */
    private static void breakHold(MinecraftServer server, ServerPlayer a, ServerPlayer b, List<HandHold> toRemove) {
        boolean wasMutual = hasMutualConnection(a, b);
        boolean removedAny = false;
        for (var handHold : handHoldings) {
            boolean betweenThem = (handHold.holder.equals(a) && handHold.holdee.equals(b))
                    || (handHold.holder.equals(b) && handHold.holdee.equals(a));
            if (!betweenThem) continue;

            toRemove.add(handHold);
            removedAny = true;
            syncHandHoldPayloadToEveryone(server.getPlayerList(), handHold.holder.getUUID().toString(),
                    handHold.holdee.getUUID().toString(), handHold.leftHand, false);
        }
        if (wasMutual && removedAny) {
            playDisconnectSound(a, b);
        }
    }

    private static void playConnectSound(ServerPlayer a, ServerPlayer b) {
        double x = (a.getX() + b.getX()) / 2.0;
        double y = (a.getY() + b.getY()) / 2.0;
        double z = (a.getZ() + b.getZ()) / 2.0;
        a.level().playSound(null, x, y, z, SoundEvents.LEAD_TIED, SoundSource.PLAYERS, 1.0f, 1.0f);
    }

    private static void playDisconnectSound(ServerPlayer a, ServerPlayer b) {
        double x = (a.getX() + b.getX()) / 2.0;
        double y = (a.getY() + b.getY()) / 2.0;
        double z = (a.getZ() + b.getZ()) / 2.0;
        a.level().playSound(null, x, y, z, SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.5f, 0.8f);
    }

    public static void cancelAll(MinecraftServer server, ServerPlayer player) {
        var removed = new ArrayList<HandHold>();
        for (var handHold : handHoldings) {
            if (handHold.holder.equals(player) || handHold.holdee.equals(player)) removed.add(handHold);
        }

        java.util.Set<String> processedPairs = new java.util.HashSet<>();
        for (var handHold : removed) {
             ServerPlayer other = handHold.holder.equals(player) ? handHold.holdee : handHold.holder;
             if (hasMutualConnection(player, other)) {
                 if (processedPairs.add(pairKey(player, other))) {
                     playDisconnectSound(player, other);
                 }
             }
        }

        handHoldings.removeAll(removed);

        for (var handHold : removed) {
            for (var other : server.getPlayerList().getPlayers()) {
                syncHandHoldPayload(other, handHold.holder, handHold.holdee, handHold.leftHand, false);
            }
        }
    }

    public static void syncHandHoldPayload(ServerPlayer receiver, ServerPlayer holder, ServerPlayer holdee, boolean leftHand, boolean holding) {
        syncHandHoldPayload(receiver, holder.getUUID().toString(), holdee.getUUID().toString(), leftHand, holding);
    }

    public static void syncHandHoldPayloadToEveryone(PlayerList playerManager, String holder, String holdee, boolean leftHand, boolean holding) {
        for (var player : playerManager.getPlayers()) {
            syncHandHoldPayload(player, holder, holdee, leftHand, holding);
        }
    }

    public static void syncHandHoldPayload(ServerPlayer receiver, String holder, String holdee, boolean leftHand, boolean holding) {
        ServerPlayNetworking.send(receiver, new SyncHandHoldPayload(holder, holdee, leftHand, holding));
    }
}
