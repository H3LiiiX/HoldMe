package dev.h3liiix.holdme;

import net.minecraft.world.entity.player.Player;

import java.util.UUID;

public interface PlayerMixinAccess {
    Player handHolding$getHoldingHandsWithLeft();

    void handHolding$setHoldingHandsWithLeft(Player holdingHandsWithLeft);

    Player handHolding$getHoldingHandsWithRight();

    void handHolding$setHoldingHandsWithRight(Player holdingHandsWithRight);

    /** The other player in an uppies pair (client-side state, see {@link dev.h3liiix.holdme.Uppies}). */
    Player handHolding$getUppiesPartner();

    void handHolding$setUppiesPartner(Player uppiesPartner);

    /** One of {@code Uppies.STAGE_*}. */
    int handHolding$getUppiesStage();

    void handHolding$setUppiesStage(int uppiesStage);

}
