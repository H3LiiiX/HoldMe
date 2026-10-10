package dev.h3liiix.holdme.mixin;

import dev.h3liiix.holdme.Uppies;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Uppies: a player sitting on another player sits on top of the carrier's raised hands instead of on their head.
 * (This mod is the only thing that puts a player on a player.) Runs on both sides: the server positions the rider
 * with it and so does every client.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "getPassengerRidingPosition", at = @At("HEAD"), cancellable = true)
    private void handHolding$uppiesSeat(Entity passenger, CallbackInfoReturnable<Vec3> cir) {
        if (!((Object) this instanceof Player carrier) || !(passenger instanceof Player)) return;
        cir.setReturnValue(carrier.position().add(0.0, Uppies.seatHeight(carrier.isCrouching()), 0.0));
    }

    @org.spongepowered.asm.mixin.injection.Redirect(
        method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z",
        at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isClientSide()Z")
    )
    private boolean handHolding$bypassSerializeCheck(net.minecraft.world.level.Level instance, Entity entityToRide, boolean force, boolean sendEventAndTriggers) {
        if (entityToRide instanceof Player) return true;
        return instance.isClientSide();
    }
}
