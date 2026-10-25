package io.github.stealingdapenta.enchantxp;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

final class MenuHolder implements InventoryHolder {
    enum Page { ITEM, ENCHANTMENT, LEVEL, CONFIRM }

    final UUID sessionId;
    final TableMenus controller;
    final MenuLayout layout;
    final UUID owner;
    final Page page;
    final Enchantment enchantment;
    final int level;
    final int index;
    final int pages;
    final int availableChoices;
    final PurchaseQuote quote;
    final Map<Integer, Enchantment> choices = new HashMap<Integer, Enchantment>();
    final Map<Integer, Integer> levels = new HashMap<Integer, Integer>();
    private Inventory inventory;

    MenuHolder(TableMenus controller, MenuLayout layout, EnchantSession session, Page page,
            Enchantment enchantment, int level, int index, int pages) {
        this(controller, layout, session, page, enchantment, level, index, pages, null, 0);
    }

    MenuHolder(TableMenus controller, MenuLayout layout, EnchantSession session, Page page,
            Enchantment enchantment, int level, int index, int pages, PurchaseQuote quote, int availableChoices) {
        this.controller = controller;
        this.layout = layout;
        this.sessionId = session.id;
        this.owner = session.owner;
        this.page = page;
        this.enchantment = enchantment;
        this.level = level;
        this.index = index;
        this.pages = pages;
        this.availableChoices = availableChoices;
        this.quote = quote;
    }

    void attach(Inventory inventory) {
        if (this.inventory != null) {
            throw new IllegalStateException("Menu inventory already attached");
        }
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
