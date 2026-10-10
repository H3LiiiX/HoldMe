package dev.h3liiix.holdme;

import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

public class HandHold {
    public ServerPlayer holder;
    public ServerPlayer holdee;
    public boolean leftHand;

    public HandHold(ServerPlayer holder, ServerPlayer holdee, boolean leftHand) {
        this.holder = holder;
        this.holdee = holdee;
        this.leftHand = leftHand;
    }

    @Override
    public int hashCode() {
        return (holder.getUUID().toString() + ":" + holdee.getUUID().toString() + ":" + leftHand).hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof HandHold handHold)) return false;
        return leftHand == handHold.leftHand && Objects.equals(holder, handHold.holder) && Objects.equals(holdee, handHold.holdee);
    }
}
