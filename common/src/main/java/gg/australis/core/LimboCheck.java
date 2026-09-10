package gg.australis.core;

/**
 * Pure decision for the limbo verifier (Phase 2, deep tier): has a held client
 * behaved enough like a real player to be promoted off the limbo backend?
 *
 * <p>Kept dependency-free so it is trivially unit-testable; the Bukkit glue that
 * tracks movement/hold-time lives in the {@code limbo} module.
 */
public final class LimboCheck {

    private LimboCheck() { }

    /**
     * @param moved           the client has sent at least one real movement/look packet
     * @param heldMillis      how long the client has been observed in limbo
     * @param requireMovement whether a movement packet is required to pass
     * @param minHoldMillis   minimum observation window before a pass is allowed
     * @return true once the client has passed the behavioural check
     */
    public static boolean passes(boolean moved, long heldMillis,
                                 boolean requireMovement, long minHoldMillis) {
        if (heldMillis < minHoldMillis) {
            return false;
        }
        return !requireMovement || moved;
    }
}
