package dev.h3liiix.holdme.mixin;

import dev.h3liiix.holdme.PlayerMixinAccess;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;

@Mixin(Player.class)
public class PlayerMixin implements PlayerMixinAccess {
    @Unique
    private Player holdingHandsWithLeft;

    @Unique
    private Player holdingHandsWithRight;

    @Unique
    private Player uppiesPartner;

    @Unique
    private int uppiesStage;

    @Override
    public Player handHolding$getHoldingHandsWithLeft() {
        return holdingHandsWithLeft;
    }

    @Override
    public void handHolding$setHoldingHandsWithLeft(Player holdingHandsWithLeft) {
        this.holdingHandsWithLeft = holdingHandsWithLeft;
    }

    @Override
    public Player handHolding$getHoldingHandsWithRight() {
        return holdingHandsWithRight;
    }

    @Override
    public void handHolding$setHoldingHandsWithRight(Player holdingHandsWithRight) {
        this.holdingHandsWithRight = holdingHandsWithRight;
    }

    @Override
    public Player handHolding$getUppiesPartner() {
        return uppiesPartner;
    }

    @Override
    public void handHolding$setUppiesPartner(Player uppiesPartner) {
        this.uppiesPartner = uppiesPartner;
    }

    @Override
    public int handHolding$getUppiesStage() {
        return uppiesStage;
    }

    @Override
    public void handHolding$setUppiesStage(int uppiesStage) {
        this.uppiesStage = uppiesStage;
    }

    @org.spongepowered.asm.mixin.injection.ModifyVariable(
            method = "causeFoodExhaustion(F)V",
            at = @org.spongepowered.asm.mixin.injection.At("HEAD"),
            argsOnly = true
    )
    private float handHolding$modifyExhaustion(float exhaustion) {
        if (((Player)(Object)this).getFirstPassenger() instanceof Player) {
            return exhaustion * 1.33f;
        }
        return exhaustion;
    }
}
