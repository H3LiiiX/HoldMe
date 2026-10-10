package dev.h3liiix.holdme.client;

import dev.h3liiix.holdme.PlayerMixinAccess;
import dev.h3liiix.holdme.Uppies;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * State of the local player's first-person "reaching out" animation, one value per arm.
 * 0 = arm in its normal place, 1 = fully reached out. Updated every client tick and interpolated per frame.
 */
public final class FirstPersonReach {
    private FirstPersonReach() {}

    /** How much of the reach happens per tick: 1/5 = it takes a quarter of a second to reach out or pull back. */
    private static final float SPEED = 1.0f / 5.0f;

    private static float leftO, left, rightO, right;

    public static void tick(Player player) {
        var access = (PlayerMixinAccess) player;
        // Asking for uppies reaches both arms out, exactly like reaching out to hold both hands.
        boolean asking = isAskingForUppies(player);
        leftO = left;
        rightO = right;
        left = approach(left, asking || access.handHolding$getHoldingHandsWithLeft() != null ? 1.0f : 0.0f);
        right = approach(right, asking || access.handHolding$getHoldingHandsWithRight() != null ? 1.0f : 0.0f);
    }

    private static float approach(float value, float target) {
        return value < target ? Math.min(target, value + SPEED) : Math.max(target, value - SPEED);
    }

    /** Eased reach progress (0..1) for this frame. */
    public static float progress(boolean leftArm, float partialTick) {
        float t = leftArm ? Mth.lerp(partialTick, leftO, left) : Mth.lerp(partialTick, rightO, right);
        return t * t * (3.0f - 2.0f * t); // smoothstep: eases in and out
    }

    /** True when this arm is reached out towards someone, whether or not they have taken the hand yet. */
    public static boolean isReaching(Player player, boolean leftArm) {
        if (isAskingForUppies(player)) return true;
        var access = (PlayerMixinAccess) player;
        return (leftArm ? access.handHolding$getHoldingHandsWithLeft() : access.handHolding$getHoldingHandsWithRight()) != null;
    }

    /** True while this player has both arms out, asking the player they looked at to pick them up. */
    public static boolean isAskingForUppies(Player player) {
        var access = (PlayerMixinAccess) player;
        Player partner = access.handHolding$getUppiesPartner();
        return access.handHolding$getUppiesStage() == Uppies.STAGE_ASKING && partner != null && !partner.isRemoved();
    }

    /** True when this arm is actually holding the other player's hand (both have reached out). */
    public static boolean isConnected(Player player, boolean leftArm) {
        var access = (PlayerMixinAccess) player;
        Player partner = leftArm ? access.handHolding$getHoldingHandsWithLeft() : access.handHolding$getHoldingHandsWithRight();
        if (partner == null || partner.isRemoved()) return false;
        var partnerAccess = (PlayerMixinAccess) partner;
        return partnerAccess.handHolding$getHoldingHandsWithLeft() == player
                || partnerAccess.handHolding$getHoldingHandsWithRight() == player;
    }
}