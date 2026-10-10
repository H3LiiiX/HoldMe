package dev.h3liiix.holdme.client;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Geometry for locking two held hands together.
 *
 * Everything here mirrors how the vanilla player model is put into the world:
 * entity position -> (crouch offset) -> rotateY(180 - bodyRot) -> scale(-1, -1, 1) -> translate(0, -1.501, 0)
 * -> arm pivot -> arm rotation (Z * Y * X) -> arm scale.
 * The arm cube is addBox(+-1px offset, -2, -2, 4, 12, 4): it reaches 10px below its pivot and its centre line
 * is about 1px to the outside of the pivot. Both are taken into account so the hand tips land exactly on the
 * meeting point instead of falling short of it.
 */
public final class HandPositions {
    private HandPositions() {}

    private static final double PX = 1.0 / 16.0;

    /** Height of the model's origin above the entity's feet after the (-1, -1, 1) flip. */
    private static final double MODEL_ROOT_HEIGHT = 1.501;
    /** The player model is rendered 2px lower while crouching. */
    private static final double CROUCH_RENDER_OFFSET = 2.0 * PX;
    /** Vanilla arm pivot height (px): standing / crouching. */
    private static final float SHOULDER_Y_STANDING = 2.0f;
    private static final float SHOULDER_Y_CROUCHING = 5.2f;
    /** Vanilla arm pivot x (px, model space): the left arm is at +5, the right arm at -5. */
    private static final float SHOULDER_X = 5.0f;

    /** Distance from the arm pivot to the end of the arm for an unscaled arm. */
    private static final double ARM_REACH = 10.0 * PX;
    /** Sideways offset of the arm's centre line from its pivot (3px slim arm = 0.5px, 4px arm = 1px). */
    private static final double ARM_CENTER_OFFSET = 0.75 * PX;

    /** Arm length the hands try to keep: the unscaled arm. The hands are raised or lowered to make this fit. */
    private static final double PREFERRED_ARM_LENGTH = ARM_REACH;
    /** The hands are never held lower than this below the shoulders, nor higher than the minimum. */
    private static final double MAX_HANDS_BELOW_SHOULDERS = 0.75;
    private static final double MIN_HANDS_BELOW_SHOULDERS = 0.05;
    /** Each hand reaches this much past the meeting point so there is never a visible seam. */
    private static final double OVERLAP = 0.005;

    private static final double MIN_ARM_SCALE = 0.1;
    /** An arm never stretches to more than this many times its normal length. If the players are further apart
     *  than the arms can reach, the arms simply point at each other without touching (the server breaks the hold
     *  well before that gets extreme). */
    private static final double MAX_ARM_SCALE = 1.8;

    /** Shoulder (arm pivot) of a player using the vanilla pose - for the player we are not rendering. */
    public static Vec3 shoulder(LivingEntity entity, boolean leftArm, float partialTick) {
        float pivotX = leftArm ? SHOULDER_X : -SHOULDER_X;
        float pivotY = entity.isCrouching() ? SHOULDER_Y_CROUCHING : SHOULDER_Y_STANDING;
        return shoulder(entity, pivotX, pivotY, 0.0f, partialTick);
    }

    /** Shoulder (arm pivot, given in model pixels) of a player, interpolated exactly like the renderer does. */
    public static Vec3 shoulder(LivingEntity entity, float pivotX, float pivotY, float pivotZ, float partialTick) {
        // Where the body is actually drawn this frame (includes the network-delay compensation).
        Vec3 pos = entity instanceof net.minecraft.world.entity.player.Player player
                ? HeldPartnerRender.renderPosition(player, partialTick)
                : new Vec3(Mth.lerp((double) partialTick, entity.xOld, entity.getX()),
                           Mth.lerp((double) partialTick, entity.yOld, entity.getY()),
                           Mth.lerp((double) partialTick, entity.zOld, entity.getZ()));
        double x = pos.x;
        double y = pos.y;
        double z = pos.z;

        // model space -> after scale(-1, -1, 1) and translate(0, -1.501, 0)
        double lx = -pivotX * PX;
        double ly = MODEL_ROOT_HEIGHT - pivotY * PX;
        double lz = pivotZ * PX;
        if (entity.isCrouching()) ly -= CROUCH_RENDER_OFFSET;

        double angle = Math.toRadians(180.0 - bodyRot(entity, partialTick));
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);

        return new Vec3(x + lx * cos + lz * sin, y + ly, z - lx * sin + lz * cos);
    }

    public static float bodyRot(LivingEntity entity, float partialTick) {
        return Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
    }

    /**
     * The point where the two hands meet. Both players compute the same point from the same two shoulders.
     * It lies below the middle of the shoulders, at the height where each arm keeps its normal length: players
     * standing close together hold hands low, and as they move apart the hands rise until the arms are
     * horizontal. Only when the players are further apart than that do the arms have to stretch.
     */
    public static Vec3 meetingPoint(Vec3 shoulderA, Vec3 shoulderB) {
        double half = shoulderA.distanceTo(shoulderB) / 2.0;
        double drop = Math.sqrt(Math.max(PREFERRED_ARM_LENGTH * PREFERRED_ARM_LENGTH - half * half, 0.0));
        drop = Math.max(MIN_HANDS_BELOW_SHOULDERS, Math.min(drop, MAX_HANDS_BELOW_SHOULDERS));

        return new Vec3(
                (shoulderA.x + shoulderB.x) / 2.0,
                (shoulderA.y + shoulderB.y) / 2.0 - drop,
                (shoulderA.z + shoulderB.z) / 2.0
        );
    }

    /**
     * Rotate (and, when {@code reachAll} is set, stretch) an arm so the centre of its hand ends up exactly on
     * {@code target}. Without {@code reachAll} the arm keeps its normal length and just points at the target.
     */
    public static void aimArm(ModelPart arm, boolean leftArm, Vec3 shoulder, Vec3 target, float bodyRot, boolean reachAll) {
        double sideways = leftArm ? ARM_CENTER_OFFSET : -ARM_CENTER_OFFSET;

        Vec3 delta = target.subtract(shoulder);
        double length = delta.length();
        if (length < 1.0E-6) return;
        if (reachAll) delta = delta.scale((length + OVERLAP) / length);

        // world offset -> model space (undo rotateY(180 - bodyRot) and the (-1, -1, 1) flip)
        double angle = Math.toRadians(180.0 - bodyRot);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double vx = -(delta.x * cos - delta.z * sin);
        double vy = -delta.y;
        double vz = delta.x * sin + delta.z * cos;

        // L is how far the hand end is along the arm's own axis; the hand end is (sideways, L) from the pivot.
        double distSqr = vx * vx + vy * vy + vz * vz;
        double armLength;
        if (reachAll) {
            double needed = Math.sqrt(Math.max(distSqr - sideways * sideways, 1.0E-9));
            double longest = ARM_REACH * MAX_ARM_SCALE;
            armLength = Math.min(needed, longest);
            // Out of reach: keep pointing at the target but with the longest allowed arm.
            if (needed > longest) {
                double k = Math.sqrt(armLength * armLength + sideways * sideways) / Math.sqrt(distSqr);
                vx *= k;
                vy *= k;
                vz *= k;
            }
        } else {
            armLength = ARM_REACH;
            double k = Math.sqrt(armLength * armLength + sideways * sideways) / Math.sqrt(distSqr);
            vx *= k;
            vy *= k;
            vz *= k;
        }

        double horizontalSqr = vx * vx + vz * vz;
        double forward = Math.sqrt(Math.max(horizontalSqr - sideways * sideways, 0.0));

        arm.xRot = (float) -Math.acos(Math.max(-1.0, Math.min(1.0, vy / armLength)));
        arm.yRot = (float) (Math.atan2(-forward, sideways) - Math.atan2(vz, vx));
        arm.zRot = 0.0f;
        arm.yScale = reachAll
                ? (float) Math.max(MIN_ARM_SCALE, Math.min(armLength / ARM_REACH, MAX_ARM_SCALE))
                : 1.0f;
    }
}
