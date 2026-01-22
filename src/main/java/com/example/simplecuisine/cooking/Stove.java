package com.example.simplecuisine.cooking;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import java.util.UUID;

public class Stove {
    private final Location location;
    private final ItemStack[] items = new ItemStack[6]; // 6 slots (3x2 grid)
    private final int[] cookingTime = new int[6];
    private final int[] maxTime = new int[6];
    private final UUID[] displayUuids = new UUID[6]; // Track display entities

    public Stove(Location location) {
        this.location = location;
    }

    public Location getLocation() {
        return location;
    }

    public ItemStack getItem(int slot) {
        if (slot < 0 || slot >= items.length) return null;
        return items[slot];
    }

    public void setItem(int slot, ItemStack item, int time) {
        if (slot < 0 || slot >= items.length) return;
        this.items[slot] = item;
        this.maxTime[slot] = time;
        this.cookingTime[slot] = 0;
    }
    
    public void removeItem(int slot) {
        if (slot < 0 || slot >= items.length) return;
        this.items[slot] = null;
        this.cookingTime[slot] = 0;
        this.maxTime[slot] = 0;
        // Entity cleanup handled by manager/visualizer
    }

    public int getCookingTime(int slot) {
        return cookingTime[slot];
    }

    public void incrementCookingTime(int slot) {
        this.cookingTime[slot]++;
    }

    public int getMaxTime(int slot) {
        return maxTime[slot];
    }
    
    public boolean isSlotEmpty(int slot) {
        return items[slot] == null || items[slot].getType().isAir();
    }
    
    public UUID getDisplayUuid(int slot) {
        return displayUuids[slot];
    }
    
    public void setDisplayUuid(int slot, UUID uuid) {
        this.displayUuids[slot] = uuid;
    }
    
    public int getSlotCount() {
        return items.length;
    }
}
