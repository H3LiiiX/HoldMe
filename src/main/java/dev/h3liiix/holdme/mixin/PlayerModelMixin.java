package dev.h3liiix.holdme.mixin;

import dev.h3liiix.holdme.PlayerMixinAccess;
import dev.h3liiix.holdme.Uppies;
import dev.h3liiix.holdme.client.HandPositions;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.Minecraft;

@Mixin(PlayerModel.class)
public class PlayerModelMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
    private void setupAnim(AvatarRenderState state, CallbackInfo ci) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        // AvatarRenderState is also used for non-player avatars, which have no hand holding data.
        if (!(level.getEntity(state.id) instanceof Player holder)) return;

        // The model is drawn at the position interpolated by this frame's partial tick, so the arms have to be
        // aimed using the same interpolation - otherwise they lag behind the body while moving (a visible gap).
        float partialTick = Mth.clamp(state.ageInTicks - holder.tickCount, 0.0f, 1.0f);

        var model = (PlayerModel) (Object) this;
        var access = (PlayerMixinAccess) holder;

        positionArm(holder, access.handHolding$getHoldingHandsWithLeft(), model.leftArm, true, partialTick);
        positionArm(holder, access.handHolding$getHoldingHandsWithRight(), model.rightArm, false, partialTick);

        // Uppies wins over hand holding if both ever apply.
        Player uppiesPartner = access.handHolding$getUppiesPartner();
        if (uppiesPartner != null && !uppiesPartner.isRemoved()) {
            int stage = access.handHolding$getUppiesStage();
            if (stage == Uppies.STAGE_ASKING) {
                // Both arms reach out to the player being asked - the same pose as reaching out to hold hands.
                handHolding$reachTowards(holder, uppiesPartner, model.leftArm, true, partialTick);
                handHolding$reachTowards(holder, uppiesPartner, model.rightArm, false, partialTick);
            } else if (stage == Uppies.STAGE_CARRYING) {
                handHolding$raiseArms(model, holder, partialTick, state);
            }
        }
    }

    /** An arm that keeps its normal length and points at where the two players' hands would meet. */
    private void handHolding$reachTowards(Player holder, Player target, ModelPart arm, boolean holderLeft, float partialTick) {
        Vec3 holderShoulder = HandPositions.shoulder(holder, arm.x, arm.y, arm.z, partialTick);
        // Facing each other, the left arm meets the other player's right side and vice versa.
        Vec3 targetShoulder = HandPositions.shoulder(target, !holderLeft, partialTick);
        Vec3 meetingPoint = HandPositions.meetingPoint(holderShoulder, targetShoulder);
        HandPositions.aimArm(arm, holderLeft, holderShoulder, meetingPoint, HandPositions.bodyRot(holder, partialTick), false);
    }

    /**
     * Carrying someone: both arms straight up, the rider sits on the hands.
     *
     * The arms are ordinary model parts, so whatever is held in a hand is still drawn at the end of the raised arm.
     * Vanilla's swing animation is rebuilt on top of the raised pose (it was just overwritten): the swinging arm
     * chops down from vertical and back, so attacking / mining / using items still shows to everyone, including
     * in the carrier's own third-person view. First person is not touched at all.
     */
    private void handHolding$raiseArms(PlayerModel model, Player holder, float partialTick, AvatarRenderState state) {
        float attackTime = holder.getSwingAnimation(partialTick);
        boolean swinging = attackTime > 0.0f;

        HumanoidArm swingArm = holder.getMainArm();
        var currentSwing = holder.getCurrentSwing();
        if (currentSwing != null && currentSwing.hand() != InteractionHand.MAIN_HAND) swingArm = swingArm.getOpposite();

        // Same maths as vanilla's HumanoidModel attack animation.
        float chop = 0.0f;
        float tilt = 0.0f;
        if (swinging) {
            float f = 1.0f - attackTime;
            f *= f;
            f *= f;
            f = 1.0f - f;
            float lunge = Mth.sin(f * (float) Math.PI);
            float headLean = Mth.sin(attackTime * (float) Math.PI) * -(model.head.xRot - 0.7f) * 0.75f;
            chop = lunge * 1.2f + headLean;
            tilt = Mth.sin(attackTime * (float) Math.PI) * -0.4f;
        }

        // The body twist vanilla applies while swinging (already set on the model).
        float twist = model.body.yRot;

        boolean leftBusy = state.leftArmPose != net.minecraft.client.model.HumanoidModel.ArmPose.EMPTY && state.leftArmPose != net.minecraft.client.model.HumanoidModel.ArmPose.ITEM;
        if (state.isUsingItem && (state.useItemHand == InteractionHand.MAIN_HAND ? state.mainArm : state.mainArm.getOpposite()) == HumanoidArm.LEFT) leftBusy = true;
        
        boolean rightBusy = state.rightArmPose != net.minecraft.client.model.HumanoidModel.ArmPose.EMPTY && state.rightArmPose != net.minecraft.client.model.HumanoidModel.ArmPose.ITEM;
        if (state.isUsingItem && (state.useItemHand == InteractionHand.MAIN_HAND ? state.mainArm : state.mainArm.getOpposite()) == HumanoidArm.RIGHT) rightBusy = true;
        
        if (state.leftArmPose.isTwoHanded() || state.rightArmPose.isTwoHanded()) {
            leftBusy = true;
            rightBusy = true;
        }

        if (!leftBusy) handHolding$raiseArm(model.leftArm, swinging && swingArm == HumanoidArm.LEFT, chop, tilt, twist);
        if (!rightBusy) handHolding$raiseArm(model.rightArm, swinging && swingArm == HumanoidArm.RIGHT, chop, tilt, twist);
    }

    private void handHolding$raiseArm(ModelPart arm, boolean swinging, float chop, float tilt, float twist) {
        // xRot 0 hangs down, -PI/2 points forward, -PI points straight up. Vanilla swings the arm forward and up
        // (xRot goes down); from straight up the same swing is a chop forward and down, so it is added instead.
        arm.xRot = -(float) Math.PI + (swinging ? chop : 0.0f);
        arm.yRot = swinging ? twist * 2.0f : twist;
        arm.zRot = swinging ? -tilt : 0.0f;
        arm.yScale = 1.0f;
    }

    private void positionArm(Player holder, Player holdee, ModelPart arm, boolean holderLeft, float partialTick) {
        // A held player that has left (or is no longer loaded) must not keep the arm pointing at nothing.
        if (holdee == null || holdee.isRemoved()) return;

        var holdeeAccess = (PlayerMixinAccess) holdee;
        boolean holdeeUsesLeft = holdeeAccess.handHolding$getHoldingHandsWithLeft() == holder;
        boolean holdeeUsesRight = holdeeAccess.handHolding$getHoldingHandsWithRight() == holder;

        // Which of the partner's hands this arm meets. Holding both hands: each hand meets the partner's
        // opposite hand (left with right), which is the one directly in front when facing each other - otherwise
        // both arms would go for the same hand and one of them would cross over.
        boolean holdeeLeft;
        if (holdeeUsesLeft && holdeeUsesRight) holdeeLeft = !holderLeft;
        else if (holdeeUsesLeft) holdeeLeft = true;
        else if (holdeeUsesRight) holdeeLeft = false;
        else holdeeLeft = !holderLeft;

        boolean mutual = holdeeUsesLeft || holdeeUsesRight;

        // The arm's real pivot is read from the model (it already accounts for crouching).
        Vec3 holderShoulder = HandPositions.shoulder(holder, arm.x, arm.y, arm.z, partialTick);
        Vec3 holdeeShoulder = HandPositions.shoulder(holdee, holdeeLeft, partialTick);
        Vec3 meetingPoint = HandPositions.meetingPoint(holderShoulder, holdeeShoulder);

        // Held by both players: each arm stretches until its hand is exactly on the shared meeting point.
        HandPositions.aimArm(arm, holderLeft, holderShoulder, meetingPoint, HandPositions.bodyRot(holder, partialTick), mutual);
    }
}
