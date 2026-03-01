package com.doge.worldinventory.data;

import java.util.List;

/**
 * Serializable snapshot of a player's full inventory.
 * Field names match Hytale's player inventory JSON structure.
 */
public class InventorySnapshot {

    private List<SlotEntry> storage;   // 36 slots
    private List<SlotEntry> armor;     // 4 slots
    private List<SlotEntry> hotbar;    // 9 slots
    private List<SlotEntry> utility;   // 4 slots
    private List<SlotEntry> tool;      // 23 slots
    private int activeHotbarSlot;

    public InventorySnapshot() {}

    public List<SlotEntry> getStorage() { return storage; }
    public void setStorage(List<SlotEntry> storage) { this.storage = storage; }

    public List<SlotEntry> getArmor() { return armor; }
    public void setArmor(List<SlotEntry> armor) { this.armor = armor; }

    public List<SlotEntry> getHotbar() { return hotbar; }
    public void setHotbar(List<SlotEntry> hotbar) { this.hotbar = hotbar; }

    public List<SlotEntry> getUtility() { return utility; }
    public void setUtility(List<SlotEntry> utility) { this.utility = utility; }

    public List<SlotEntry> getTool() { return tool; }
    public void setTool(List<SlotEntry> tool) { this.tool = tool; }

    public int getActiveHotbarSlot() { return activeHotbarSlot; }
    public void setActiveHotbarSlot(int activeHotbarSlot) { this.activeHotbarSlot = activeHotbarSlot; }

    /**
     * Represents one inventory slot — null entry means the slot is empty.
     */
    public static class SlotEntry {
        private String Id;
        private int Quantity;
        private double Durability;

        public SlotEntry() {}

        public SlotEntry(String id, int quantity, double durability) {
            this.Id = id;
            this.Quantity = quantity;
            this.Durability = durability;
        }

        public String getId() { return Id; }
        public void setId(String id) { this.Id = id; }

        public int getQuantity() { return Quantity; }
        public void setQuantity(int quantity) { this.Quantity = quantity; }

        public double getDurability() { return Durability; }
        public void setDurability(double durability) { this.Durability = durability; }
    }
}
