package dev.h3liiix.holdme.client;

import dev.h3liiix.holdme.PlayerMixinAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Purely visual compensation for network delay.
 *
 * Your own player moves instantly on your screen, but every other player is drawn where they were a ping +
 * interpolation ago. So when you drag someone, they look further behind on your screen than they really are
 * (on their own screen everything lines up). The server can't fix that without slowing the leader down, so
 * instead the partner's body is simply drawn no further than {@link #MAX_VISUAL_GAP} away from you.
 *
 * Only rendering is affected - hitboxes, physics and the real positions are untouched.
 */
public final class HeldPartnerRender {
    private HeldPartnerRender() {}

    /** A held partner is never drawn further than this (horizontally, in blocks) from the player holding them. */
    private static final double MAX_VISUAL_GAP = 1.5;
    /** After letting go, the drawn body glides back to its real position over this many milliseconds. */
    private static final double RELEASE_MILLIS = 250.0;

    private static final class State {
        Vec3 lastOffset = Vec3.ZERO;
        Vec3 releaseOffset = null;
        long releaseTime;
    }

    private static final Map<UUID, State> states = new HashMap<>();

    /** Position interpolated exactly like vanilla's EntityRenderer.extractRenderState does it. */
    public static Vec3 lerpedPosition(Player player, float partialTick) {
        return new Vec3(
                Mth.lerp((double) partialTick, player.xOld, player.getX()),
                Mth.lerp((double) partialTick, player.yOld, player.getY()),
                Mth.lerp((double) partialTick, player.zOld, player.getZ())
        );
    }

    /** Where the player's body is actually drawn this frame (interpolated position + compensation). */
    public static Vec3 renderPosition(Player player, float partialTick) {
        return lerpedPosition(player, partialTick).add(offset(player, partialTick));
    }

    /** How far the player's drawn body is moved from their interpolated position. */
    public static Vec3 offset(Player player, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (player == mc.player) return Vec3.ZERO; // you always see yourself exactly where you are

        State state = states.computeIfAbsent(player.getUUID(), id -> new State());
        Player partner = mutualPartner(player);

        if (partner == null || partner.isRemoved() || partner.level() != player.level()) {
            return releasing(state);
        }

        Vec3 self = lerpedPosition(player, partialTick);
        Vec3 other = lerpedPosition(partner, partialTick);
        double dx = other.x - self.x;
        double dz = other.z - self.z;
        double gap = Math.sqrt(dx * dx + dz * dz);

        Vec3 offset = Vec3.ZERO;
        if (gap > MAX_VISUAL_GAP) {
            // Holding you: the partner covers the whole difference (you are drawn where you really are).
            // Two other players holding each other: each covers half, so both meet in the middle.
            double share = partner == mc.player ? 1.0 : 0.5;
            double move = (gap - MAX_VISUAL_GAP) * share / gap;
            offset = new Vec3(dx * move, 0.0, dz * move);
        }

        state.lastOffset = offset;
        state.releaseOffset = null;
        return offset;
    }

    /** No longer holding hands: glide back to the real position instead of snapping. */
    private static Vec3 releasing(State state) {
        if (state.releaseOffset == null) {
            if (state.lastOffset.lengthSqr() < 1.0E-8) return Vec3.ZERO;
            state.releaseOffset = state.lastOffset;
            state.releaseTime = System.currentTimeMillis();
            state.lastOffset = Vec3.ZERO;
        }

        double t = (System.currentTimeMillis() - state.releaseTime) / RELEASE_MILLIS;
        if (t >= 1.0) {
            state.releaseOffset = null;
            return Vec3.ZERO;
        }
        double ease = 1.0 - t;
        return state.releaseOffset.scale(ease * ease);
    }

    /** The player this one is holding hands with, where both have reached out to each other. */
    public static Player mutualPartner(Player player) {
        var access = (PlayerMixinAccess) player;
        Player left = access.handHolding$getHoldingHandsWithLeft();
        if (left != null && holds(left, player)) return left;
        Player right = access.handHolding$getHoldingHandsWithRight();
        if (right != null && holds(right, player)) return right;
        return null;
    }

    private static boolean holds(Player holder, Player target) {
        var access = (PlayerMixinAccess) holder;
        return access.handHolding$getHoldingHandsWithLeft() == target
                || access.handHolding$getHoldingHandsWithRight() == target;
    }
}
