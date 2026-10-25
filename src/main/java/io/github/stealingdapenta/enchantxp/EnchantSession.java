package io.github.stealingdapenta.enchantxp;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

final class EnchantSession {
    enum Sort {
        DEFAULT, ASCENDING, DESCENDING;

        Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }

        String label(boolean levels) {
            return this == DEFAULT ? "Default" :
                    (levels ? "Level " : "Alphabetical ") + (this == ASCENDING ? "ascending" : "descending");
        }
    }

    static final int STORAGE_SIZE = 36;
    private static final int NO_SELECTION = -1;
    final UUID id = UUID.randomUUID();
    final UUID owner;
    final int heldSlot;
    final int slot;
    final Location table;
    private final ItemStack snapshot;
    MenuHolder menu;
    boolean pending;
    int pendingNavigation = -1;
    long actionGeneration;
    Sort sort = Sort.DEFAULT;
    private boolean consumed;

    EnchantSession(UUID owner, int slot, ItemStack item, Location table) {
        this(owner, slot, slot, item, table);
    }

    EnchantSession(UUID owner, int heldSlot, Location table) {
        this(owner, heldSlot, NO_SELECTION, null, table);
    }

    EnchantSession(UUID owner, int heldSlot, int slot, ItemStack item, Location table) {
        this.owner = owner;
        this.heldSlot = heldSlot;
        this.slot = slot;
        this.snapshot = item == null ? null : item.clone();
        this.table = table.clone();
    }

    boolean hasSelection() {
        return slot >= 0 && slot < STORAGE_SIZE && snapshot != null;
    }

    ItemStack snapshot() {
        if (!hasSelection()) {
            throw new IllegalStateException("No item selected");
        }
        return snapshot.clone();
    }

    boolean matches(ItemStack item) {
        return hasSelection() && snapshot.equals(item);
    }

    boolean claim() {
        if (consumed) {
            return false;
        }

        consumed = true;
        return true;
    }

    boolean available() {
        return !consumed;
    }

    void invalidate() {
        actionGeneration++;
        consumed = true;
    }
}
