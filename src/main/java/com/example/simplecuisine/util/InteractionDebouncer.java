package com.example.simplecuisine.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility to prevent double interactions (Main Hand + Off Hand) or rapid clicking.
 * Helps solve "Place vs Interact" race conditions and "Main Hand success, Off Hand trigger" issues.
 */
public class InteractionDebouncer {

    private static final Map<UUID, Long> lastInteractTime = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 50; // 50ms cooldown (1 tick)

    /**
     * Checks if the player can interact. Updates the last interact time if allowed.
     * @param playerUuid The player's UUID
     * @return true if interaction is allowed, false if debounced (cooldown active)
     */
    public static boolean canInteract(UUID playerUuid) {
        long now = System.currentTimeMillis();
        long last = lastInteractTime.getOrDefault(playerUuid, 0L);
        
        if (now - last < COOLDOWN_MS) {
            return false;
        }
        
        lastInteractTime.put(playerUuid, now);
        return true;
    }
    
    /**
     * Call this when a significant action (like placing a block) happens,
     * to prevent immediate subsequent interactions.
     * @param playerUuid The player's UUID
     * @param durationMs Duration of the block
     */
    public static void blockInteraction(UUID playerUuid, long durationMs) {
        lastInteractTime.put(playerUuid, System.currentTimeMillis() + durationMs);
    }
    
    /**
     * Resets the cooldown for the player.
     * Use this when an interaction was allowed but didn't result in an action (yielded).
     * @param playerUuid The player's UUID
     */
    public static void reset(UUID playerUuid) {
        lastInteractTime.remove(playerUuid);
    }
}
