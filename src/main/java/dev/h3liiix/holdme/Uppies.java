package dev.h3liiix.holdme;

/**
 * Shared constants for the "uppies" feature (one player reaches both arms out to be picked up, the other
 * player can then carry them on their raised hands).
 *
 * Lives in common code (no client classes) because the seat height is needed by the server as well.
 */
public final class Uppies {
    private Uppies() {}

    // --- Per-player state, as stored on the client (see PlayerMixin) ---------------------------------
    /** Not involved in uppies. */
    public static final int STAGE_NONE = 0;
    /** This player has both arms reached out towards their partner, asking to be picked up. */
    public static final int STAGE_ASKING = 1;
    /** This player is sitting on their partner's hands. */
    public static final int STAGE_RIDING = 2;
    /** This player is carrying their partner on raised hands. */
    public static final int STAGE_CARRYING = 3;

    // --- Server -> client sync values (see SyncUppiesPayload) ----------------------------------------
    public static final int SYNC_ENDED = 0;
    public static final int SYNC_ASKING = 1;
    public static final int SYNC_ACCEPTED = 2;

    // --- Seat geometry (mirrors the player model, see HandPositions) ---------------------------------
    private static final double PX = 1.0 / 16.0;
    /** Height of the model's origin above the entity's feet after the (-1, -1, 1) flip. */
    private static final double MODEL_ROOT_HEIGHT = 1.501;
    /** Vanilla arm pivot height (px): standing / crouching, and how much lower a crouching model is drawn. */
    private static final double SHOULDER_Y_STANDING = 2.0;
    private static final double SHOULDER_Y_CROUCHING = 5.2;
    private static final double CROUCH_RENDER_OFFSET = 2.0 * PX;
    /** Distance from the arm pivot to the end of the hand. */
    private static final double ARM_REACH = 10.0 * PX;
    /** The rider sits slightly into the hands rather than hovering exactly on the fingertips. */
    private static final double SEAT_SINK = 0.05;

    /**
     * Height above the carrier's feet where the rider's seat is: the tips of the hands when both arms point
     * straight up. (Vanilla then lowers the rider by their own sitting offset.)
     */
    public static double seatHeight(boolean carrierCrouching) {
        double shoulder = MODEL_ROOT_HEIGHT - (carrierCrouching ? SHOULDER_Y_CROUCHING : SHOULDER_Y_STANDING) * PX;
        if (carrierCrouching) shoulder -= CROUCH_RENDER_OFFSET;
        return shoulder + ARM_REACH - SEAT_SINK;
    }
}
