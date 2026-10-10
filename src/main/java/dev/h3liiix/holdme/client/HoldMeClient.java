package dev.h3liiix.holdme.client;

import dev.h3liiix.holdme.HoldMe;
import dev.h3liiix.holdme.PlayerMixinAccess;
import dev.h3liiix.holdme.Uppies;
import dev.h3liiix.holdme.networking.ToggleHandHoldPayload;
import dev.h3liiix.holdme.networking.SyncHandHoldPayload;
import dev.h3liiix.holdme.networking.SyncUppiesPayload;
import dev.h3liiix.holdme.networking.UppiesPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.world.entity.player.Player;


import java.util.ArrayList;
import java.util.UUID;

public class HoldMeClient implements ClientModInitializer {
    private static KeyMapping handHoldKeybindLeft;
    private static KeyMapping handHoldKeybindRight;
    private static KeyMapping uppiesKeybind;
    private boolean wasShowingPrompt = false;
    /** "Started holding" syncs that arrived before the other players were loaded, applied once they are. */
    private record PendingSync(SyncHandHoldPayload payload, ClientPlayNetworking.Context context) {}
    private final ArrayList<PendingSync> levelLoadQueue = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        networking();

        handHoldKeybindLeft = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                HoldMe.NAMESPACE + ".hold_hand_left",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_G,
                KeyMapping.Category.MISC
        ));

        handHoldKeybindRight = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                HoldMe.NAMESPACE + ".hold_hand_right",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_H,
                KeyMapping.Category.MISC
        ));

        uppiesKeybind = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                HoldMe.NAMESPACE + ".uppies",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_U,
                KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            FirstPersonReach.tick(client.player);

            // The server works out what the press means: ask for uppies, cancel, give uppies, or put the rider down.
            while (uppiesKeybind.consumeClick()) {
                String target = client.crosshairPickEntity instanceof Player looked ? looked.getUUID().toString() : "";
                ClientPlayNetworking.send(new UppiesPayload(target));
            }

            while (handHoldKeybindLeft.consumeClick()) {
                var holdee = ((PlayerMixinAccess) client.player).handHolding$getHoldingHandsWithLeft();
                if (holdee != null) {
                    ClientPlayNetworking.send(new ToggleHandHoldPayload(holdee.getUUID().toString(), true));
                    return;
                }

                if (client.crosshairPickEntity == null || !(client.crosshairPickEntity instanceof net.minecraft.world.entity.player.Player)) return;
                ClientPlayNetworking.send(new ToggleHandHoldPayload(client.crosshairPickEntity.getUUID().toString(), true));
            }
            while (handHoldKeybindRight.consumeClick()) {
                var holdee = ((PlayerMixinAccess) client.player).handHolding$getHoldingHandsWithRight();
                if (holdee != null) {
                    ClientPlayNetworking.send(new ToggleHandHoldPayload(holdee.getUUID().toString(), false));
                    return;
                }

                if (client.crosshairPickEntity == null || !(client.crosshairPickEntity instanceof net.minecraft.world.entity.player.Player)) return;
                ClientPlayNetworking.send(new ToggleHandHoldPayload(client.crosshairPickEntity.getUUID().toString(), false));
            }

            if (client.level != null && client.level.players().size() > 1 && !levelLoadQueue.isEmpty()) {
                var pending = levelLoadQueue.removeFirst();
                parsePacket(pending.context(), pending.payload());
            }

            net.minecraft.network.chat.Component prompt = null;

            if (client.crosshairPickEntity instanceof net.minecraft.world.entity.player.Player otherPlayer) {
                var otherAccess = (PlayerMixinAccess) otherPlayer;
                boolean theyOfferL = otherAccess.handHolding$getHoldingHandsWithLeft() == client.player;
                boolean theyOfferR = otherAccess.handHolding$getHoldingHandsWithRight() == client.player;
                
                if (theyOfferL || theyOfferR) {
                    var myAccess = (PlayerMixinAccess) client.player;
                    boolean weHoldL = myAccess.handHolding$getHoldingHandsWithLeft() == otherPlayer;
                    boolean weHoldR = myAccess.handHolding$getHoldingHandsWithRight() == otherPlayer;
                    
                    int theyOfferCount = (theyOfferL ? 1 : 0) + (theyOfferR ? 1 : 0);
                    int weHoldCount = (weHoldL ? 1 : 0) + (weHoldR ? 1 : 0);
                    
                    if (theyOfferCount > weHoldCount) {
                        prompt = net.minecraft.network.chat.Component.translatable("holdme.prompt", handHoldKeybindLeft.getTranslatedKeyMessage(), handHoldKeybindRight.getTranslatedKeyMessage());
                    }
                }

                // They have both arms out asking to be picked up (and we are free to do so).
                if (prompt == null
                        && otherAccess.handHolding$getUppiesStage() == Uppies.STAGE_ASKING
                        && otherAccess.handHolding$getUppiesPartner() == client.player
                        && ((PlayerMixinAccess) client.player).handHolding$getUppiesStage() == Uppies.STAGE_NONE
                        && !client.player.isPassenger() && !client.player.isVehicle()) {
                    prompt = net.minecraft.network.chat.Component.translatable("holdme.uppies_prompt", uppiesKeybind.getTranslatedKeyMessage());
                }
            }

            if (prompt != null) {
                client.gui.hud.setOverlayMessage(prompt, false);
                wasShowingPrompt = true;
            } else if (wasShowingPrompt) {
                client.gui.hud.setOverlayMessage(null, false);
                wasShowingPrompt = false;
            }
        });
    }

    private void networking() {
        ClientPlayNetworking.registerGlobalReceiver(SyncUppiesPayload.ID, (payload, context) -> applyUppies(context, payload));

        ClientPlayNetworking.registerGlobalReceiver(SyncHandHoldPayload.ID, (payload, context) -> {
            if (!payload.holding()) {
                // Letting go is always applied straight away (e.g. the other player left the server), and it
                // cancels a "started holding" that was still waiting for the players to load.
                levelLoadQueue.removeIf(pending -> pending.payload().holderUuid().equals(payload.holderUuid())
                        && pending.payload().holdeeUuid().equals(payload.holdeeUuid())
                        && pending.payload().leftHand() == payload.leftHand());
                parsePacket(context, payload);
                return;
            }

            if (context.client().level.players().size() <= 1) {
                levelLoadQueue.add(new PendingSync(payload, context));
                return;
            }

            parsePacket(context, payload);
        });
    }

    private void parsePacket(ClientPlayNetworking.Context context, SyncHandHoldPayload payload) {
        Player holder = context.client().level.getPlayerByUUID(UUID.fromString(payload.holderUuid()));
        var holdee = context.client().level.getPlayerByUUID(UUID.fromString(payload.holdeeUuid()));
        if (holder == null) return;
        var access = ((PlayerMixinAccess) holder);
        if (payload.leftHand()) {
            if (!payload.holding())
                access.handHolding$setHoldingHandsWithLeft(null);
            else access.handHolding$setHoldingHandsWithLeft(holdee);
        } else {
            if (!payload.holding())
                access.handHolding$setHoldingHandsWithRight(null);
            else access.handHolding$setHoldingHandsWithRight(holdee);
        }

    }

    /**
     * Stores the state of an uppies pair on the (client-side) player objects. The server repeats active pairs
     * every second, so a player that was not loaded yet when this arrived is picked up by the next repeat.
     */
    private void applyUppies(ClientPlayNetworking.Context context, SyncUppiesPayload payload) {
        var level = context.client().level;
        if (level == null) return;

        Player rider = level.getPlayerByUUID(UUID.fromString(payload.riderUuid()));
        Player carrier = level.getPlayerByUUID(UUID.fromString(payload.carrierUuid()));

        if (payload.stage() == Uppies.SYNC_ENDED) {
            clearUppies(rider, payload.carrierUuid());
            clearUppies(carrier, payload.riderUuid());
            return;
        }

        if (rider == null || carrier == null) return;
        var riderAccess = (PlayerMixinAccess) rider;
        riderAccess.handHolding$setUppiesPartner(carrier);

        if (payload.stage() == Uppies.SYNC_ACCEPTED) {
            riderAccess.handHolding$setUppiesStage(Uppies.STAGE_RIDING);
            var carrierAccess = (PlayerMixinAccess) carrier;
            carrierAccess.handHolding$setUppiesPartner(rider);
            carrierAccess.handHolding$setUppiesStage(Uppies.STAGE_CARRYING);
        } else {
            riderAccess.handHolding$setUppiesStage(Uppies.STAGE_ASKING);
        }
    }

    /** Forget the uppies pair of {@code player}, if their partner is the player with this UUID. */
    private static void clearUppies(Player player, String partnerUuid) {
        if (player == null) return;
        var access = (PlayerMixinAccess) player;
        Player partner = access.handHolding$getUppiesPartner();
        if (partner != null && !partner.getUUID().toString().equals(partnerUuid)) return;
        access.handHolding$setUppiesPartner(null);
        access.handHolding$setUppiesStage(Uppies.STAGE_NONE);
    }

}
