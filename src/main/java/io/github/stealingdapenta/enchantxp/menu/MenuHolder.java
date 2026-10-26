package io.github.stealingdapenta.enchantxp.menu;

import io.github.stealingdapenta.enchantxp.presentation.MenuStage;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

final class MenuHolder implements InventoryHolder {

    final UUID sessionId;
    final TableMenus controller;
    final MenuLayout layout;
    final UUID owner;
    final MenuStage page;
    final Enchantment enchantment;
    final int level;
    final int index;
    final int pages;
    final int availableChoices;
    final PurchaseQuote quote;
    final Map<Integer, Enchantment> choices = new HashMap<>();
    final Map<Integer, Integer> levels = new HashMap<>();
    private Inventory inventory;

    MenuHolder(TableMenus controller, MenuLayout layout, EnchantSession session, Route route, Pagination pagination, PurchaseQuote quote) {
        this.controller = controller;
        this.layout = layout;
        this.sessionId = session.id;
        this.owner = session.owner;
        this.page = route.stage;
        this.enchantment = route.enchantment;
        this.level = route.level;
        this.index = pagination.index;
        this.pages = pagination.pages;
        this.availableChoices = pagination.count;
        this.quote = quote;
    }

    /**
     * A requested stage and target; pagination clamps the requested page before publication.
     */
    static final class Route {

        final MenuStage stage;
        final Enchantment enchantment;
        final int level;
        final int index;

        Route(MenuStage stage, Enchantment enchantment, int level, int index) {
            this.stage = stage;
            this.enchantment = enchantment;
            this.level = level;
            this.index = index;
        }
    }

    static final class Pagination {

        final int index;
        final int pages;
        final int count;

        Pagination(int requested, int count, int capacity) {
            this.count = count;
            this.pages = Math.max(1, (count + capacity - 1) / capacity);
            this.index = Math.max(0, Math.min(requested, pages - 1));
        }
    }

    void attach(Inventory inventory) {
        if (this.inventory != null) {
            throw new IllegalStateException("Menu inventory already attached");
        }
        this.inventory = inventory;
    }

    /** Returns the once-attached inventory; null only while this unpublished holder is being built. */
    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
