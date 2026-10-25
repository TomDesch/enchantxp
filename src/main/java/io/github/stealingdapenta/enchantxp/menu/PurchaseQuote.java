package io.github.stealingdapenta.enchantxp.menu;

import io.github.stealingdapenta.enchantxp.enchanting.BookshelfPricing;
import io.github.stealingdapenta.enchantxp.enchanting.Enchantments;
import io.github.stealingdapenta.enchantxp.enchanting.Experience;
import io.github.stealingdapenta.enchantxp.enchanting.Prices;

import org.bukkit.GameMode;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;

/** The exact confirmation the player saw. Balance changes matter only when affordability changes. */
final class PurchaseQuote {
    final Enchantment enchantment;
    final int target;
    final GameMode mode;
    final int cost;
    final boolean affordable;

    PurchaseQuote(Enchantment enchantment, int target, GameMode mode, int cost, boolean affordable) {
        this.enchantment = enchantment;
        this.target = target;
        this.mode = mode;
        this.cost = cost;
        this.affordable = affordable;
    }

    static PurchaseQuote read(Player player, EnchantSession session, Enchantment enchantment, int target,
            Prices prices, BookshelfPricing shelves) {
        return read(player, session, enchantment, target, prices, shelves, shelves.count(session.table));
    }

    static PurchaseQuote read(Player player, EnchantSession session, Enchantment enchantment, int target,
            Prices prices, BookshelfPricing shelves, int count) {
        int base = prices.checked(shelves.quote(prices, enchantment,
                Enchantments.level(session.snapshot(), enchantment), target, count));
        GameMode mode = player.getGameMode();
        boolean creative = mode == GameMode.CREATIVE;
        int cost = creative ? 0 : base;
        return new PurchaseQuote(enchantment, target, mode, cost, creative || balance(player) >= cost);
    }

    static int balance(Player player) {
        return Experience.total(player.getLevel(), player.getExp());
    }

    boolean same(PurchaseQuote other) {
        return other != null && enchantment.equals(other.enchantment) && target == other.target &&
                mode == other.mode && cost == other.cost && affordable == other.affordable;
    }
}
