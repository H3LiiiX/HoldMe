package dev.h3liiix.holdme.mixin;

import dev.h3liiix.holdme.client.HeldPartnerRender;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Draws a held partner closer to compensate for network delay (see {@link HeldPartnerRender}). */
@Mixin(AvatarRenderer.class)
public class AvatarRendererMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
    private void handHolding$compensateDelay(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        if (!(entity instanceof Player player)) return;

        Vec3 offset = HeldPartnerRender.offset(player, partialTick);
        if (offset == Vec3.ZERO) return;

        state.x += offset.x;
        state.z += offset.z;
    }
}
