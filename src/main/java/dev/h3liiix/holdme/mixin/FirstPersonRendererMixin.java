package dev.h3liiix.holdme.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.h3liiix.holdme.client.FirstPersonReach;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * First-person hands while holding hands:
 * - reaching out (waiting for the other player): the arm eases forward from the bottom of the screen and stays
 *   locked to the camera;
 * - connected to the other player: that hand (and whatever it holds) is hidden so it doesn't block the view;
 * - letting go: the arm eases back down, then the normal hand / item returns.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonRendererMixin {

    // Tuning of the reached-out pose, in screen space (blocks): towards the middle, up, and forwards.
    private static final float REACH_INWARD = 0.10f;
    private static final float REACH_UP = 0.06f;
    private static final float REACH_FORWARD = 0.22f;
    /** Small forwards/backwards motion while waiting for the other player to take the hand. */
    private static final float WAITING_MOTION = 0.015f;

    @Shadow
    private void renderPlayerArm(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int light, float equipProgress, float swingProgress, HumanoidArm arm, PlayerRenderState playerRenderState) {}

    @Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
    private void handHolding$reachingArm(PlayerRenderState playerRenderState, FirstPersonHandsAndItemsRenderState state,
                                         float partialTick, float xRot, InteractionHand hand, float swingProgress,
                                         ItemStack itemStack, float equipProgress, PoseStack poseStack,
                                         SubmitNodeCollector submitNodeCollector, int light, CallbackInfo ci) {
        Player player = Minecraft.getInstance().player;
        if (player == null || playerRenderState.avatarRenderState == null) return;

        HumanoidArm mainArm = playerRenderState.avatarRenderState.mainArm;
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND ? mainArm : mainArm.getOpposite();
        boolean leftArm = arm == HumanoidArm.LEFT;

        // Holding the other player's hand: hide this hand completely.
        if (FirstPersonReach.isConnected(player, leftArm)) {
            ci.cancel();
            return;
        }

        float reach = FirstPersonReach.progress(leftArm, partialTick);

        // Only the other hand is reaching out (or holding): hide this hand if it is empty, so just the reaching
        // arm is on screen.
        if (reach <= 0.0f && itemStack.isEmpty() && FirstPersonReach.isReaching(player, !leftArm)) {
            ci.cancel();
            return;
        }

        if (reach <= 0.0f) return; // not reaching: normal vanilla hand / item

        poseStack.pushPose();

        // Undo vanilla's hand sway (the hands lagging behind when you turn), so the reaching arm is locked to the camera.
        poseStack.rotateDegrees(Axis.YP, -(state.viewYRot - state.yBob) * 0.1f);
        poseStack.rotateDegrees(Axis.XP, -(state.viewXRot - state.xBob) * 0.1f);

        float side = leftArm ? -1.0f : 1.0f;
        float waiting = Mth.sin((player.tickCount + partialTick) * 0.2f) * WAITING_MOTION * reach;
        poseStack.translate(-side * REACH_INWARD * reach, REACH_UP * reach, -(REACH_FORWARD * reach) + waiting);

        // equipProgress 1 = below the screen, 0 = in place: the arm slides up into view as it reaches out.
        this.renderPlayerArm(poseStack, submitNodeCollector, light, 1.0f - reach, 0.0f, arm, playerRenderState);

        poseStack.popPose();
        ci.cancel();
    }
}

