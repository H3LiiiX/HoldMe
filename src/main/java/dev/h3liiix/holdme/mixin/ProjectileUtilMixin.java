package dev.h3liiix.holdme.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Predicate;

@Mixin(ProjectileUtil.class)
public class ProjectileUtilMixin {
    @ModifyVariable(
            method = "getEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;F)Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At("HEAD"),
            argsOnly = true
    )
    private static Predicate<Entity> handHolding$ignorePassengers7(Predicate<Entity> matching, net.minecraft.world.level.Level level, Entity source) {
        return entity -> matching.test(entity) && !(source instanceof net.minecraft.world.entity.player.Player && entity instanceof net.minecraft.world.entity.player.Player && entity.getRootVehicle() == source.getRootVehicle());
    }

    @ModifyVariable(
            method = "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;",
            at = @At("HEAD"),
            argsOnly = true
    )
    private static Predicate<Entity> handHolding$ignorePassengers6(Predicate<Entity> matching, Entity source) {
        return entity -> matching.test(entity) && !(source instanceof net.minecraft.world.entity.player.Player && entity instanceof net.minecraft.world.entity.player.Player && entity.getRootVehicle() == source.getRootVehicle());
    }
}
