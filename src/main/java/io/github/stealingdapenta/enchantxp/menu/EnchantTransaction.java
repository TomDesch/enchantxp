package io.github.stealingdapenta.enchantxp.menu;

import io.github.stealingdapenta.enchantxp.enchanting.BookshelfPricing;
import io.github.stealingdapenta.enchantxp.enchanting.EnchantAccess;
import io.github.stealingdapenta.enchantxp.enchanting.Enchantments;
import io.github.stealingdapenta.enchantxp.enchanting.EnchantPermissions;
import io.github.stealingdapenta.enchantxp.enchanting.EnchantPolicy;
import io.github.stealingdapenta.enchantxp.enchanting.Experience;
import io.github.stealingdapenta.enchantxp.enchanting.Prices;

import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/** Called synchronously after a deferred confirmation; a claimed session can never charge twice. */
final class EnchantTransaction {
    private final Prices prices;
    private final EnchantPolicy policy;
    private final BookshelfPricing shelves;
    private final EnchantPermissions permissions;

    EnchantTransaction(Prices prices) {
        this(prices, EnchantPolicy.vanilla());
    }

    EnchantTransaction(Prices prices, EnchantPolicy policy) {
        this(prices, policy, BookshelfPricing.disabled(), EnchantPermissions.disabled());
    }

    EnchantTransaction(Prices prices, EnchantPolicy policy, BookshelfPricing shelves, EnchantPermissions permissions) {
        this.prices = prices;
        this.policy = policy;
        this.shelves = shelves;
        this.permissions = permissions;
    }

    boolean commit(Player player, EnchantSession session, Enchantment enchantment, int level, BooleanSupplier context) {
        return commit(player, session, enchantment, level, context, null);
    }

    boolean commit(Player player, EnchantSession session, Enchantment enchantment, int level,
            BooleanSupplier context, PurchaseQuote displayed) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("EnchantXP transactions require the server thread");
        }
        if (!session.available()) {
            return false;
        }
        if (!context.getAsBoolean() || !session.hasSelection() || !session.owner.equals(player.getUniqueId()) ||
                player.getInventory().getHeldItemSlot() != session.heldSlot ||
                !session.matches(player.getInventory().getItem(session.slot))) {
            return false;
        }
        ItemStack original = session.snapshot();
        int current = Enchantments.level(original, enchantment);
        if (!prices.offersUpgrade(enchantment, current, level) || !Enchantments.legal(original, enchantment, level, policy) ||
                !permissions.allows(player, enchantment, level) || !EnchantAccess.eligible(player)) {
            return false;
        }
        int count = shelves.count(session.table);
        if (!prices.accepts(shelves.quote(prices, enchantment, current, level, count))) {
            if (displayed == null) {
                return false;
            }
            throw new QuoteChangedException();
        }
        PurchaseQuote quote = PurchaseQuote.read(player, session, enchantment, level, prices, shelves, count);
        if (displayed != null && !displayed.same(quote)) {
            throw new QuoteChangedException();
        }
        if (!quote.affordable) {
            return false;
        }
        int destination = outputSlot(player, session.slot, original);
        if (destination < 0) {
            session.claim();
            throw new OutputSpaceException();
        }
        boolean split = destination != session.slot;
        ItemStack previousOutput = split ? player.getInventory().getItem(destination) : null;
        previousOutput = previousOutput == null ? null : previousOutput.clone();
        final ItemStack outputSnapshot = previousOutput;
        ItemStack result = original.clone();
        result.setAmount(1);
        if (Enchantments.book(result)) {
            ItemMeta originalMeta = result.getItemMeta();
            result.setType(Material.ENCHANTED_BOOK);
            ItemMeta converted = Bukkit.getItemFactory().asMetaFor(originalMeta, Material.ENCHANTED_BOOK);
            if (!(converted instanceof EnchantmentStorageMeta)) {
                throw new IllegalStateException("Server did not convert book metadata");
            }
            EnchantmentStorageMeta meta = (EnchantmentStorageMeta) converted;
            if (!meta.addStoredEnchant(enchantment, level, true) || !result.setItemMeta(meta)) {
                throw new IllegalStateException("Server rejected the stored enchantment");
            }
        } else if (level > enchantment.getMaxLevel()) {
            result.addUnsafeEnchantment(enchantment, level);
        } else {
            result.addEnchantment(enchantment, level);
        }
        if (Enchantments.level(result, enchantment) != level || !Enchantments.supported(result, policy)) {
            throw new IllegalStateException("Server rejected the prepared enchantment");
        }
        if (!context.getAsBoolean() || !session.matches(player.getInventory().getItem(session.slot))) {
            return false;
        }
        if (split && !empty(player.getInventory().getItem(destination))) {
            session.claim();
            throw new OutputSpaceException();
        }
        ItemStack remainder = original.clone();
        if (split) {
            remainder.setAmount(original.getAmount() - 1);
        }
        if (!permissions.allows(player, enchantment, level) || !EnchantAccess.eligible(player)) {
            throw new QuoteChangedException();
        }
        int freshCount = shelves.count(session.table);
        if (!prices.accepts(shelves.quote(prices, enchantment, current, level, freshCount))) {
            throw new QuoteChangedException();
        }
        PurchaseQuote fresh = PurchaseQuote.read(player, session, enchantment, level, prices, shelves, freshCount);
        if (!quote.same(fresh)) {
            throw new QuoteChangedException();
        }
        boolean creative = quote.mode == GameMode.CREATIVE;
        int total = creative ? 0 : PurchaseQuote.balance(player);
        if (!creative && total < quote.cost) {
            throw new QuoteChangedException();
        }
        Experience.Balance before = creative ? null : Experience.balance(total);
        Experience.Balance after = creative ? null : Experience.balance(total - quote.cost);
        if (!session.claim()) {
            return false;
        }
        try {
            if (!creative) {
                apply(player, after);
            }
            player.getInventory().setItem(session.slot, split ? remainder : result);
            if (split) {
                player.getInventory().setItem(destination, result);
            }
        } catch (RuntimeException failure) {
            // Compensation must prove the result is gone before returning consumed resources.
            boolean safeToRefund = split ? rollbackOutput(player, destination, outputSnapshot, failure) :
                    rollbackSource(player, session, original, failure);
            if (safeToRefund) {
                if (!creative) {
                    restore(() -> player.setLevel(before.level()), failure);
                    restore(() -> player.setExp(before.progress()), failure);
                    restore(() -> player.setTotalExperience(before.total()), failure);
                }
                if (split) {
                    restore(() -> player.getInventory().setItem(session.slot, original), failure);
                }
            }
            throw failure;
        }
        return true;
    }

    private static boolean rollbackOutput(Player player, int slot, ItemStack snapshot, RuntimeException failure) {
        restore(() -> player.getInventory().setItem(slot, snapshot), failure);
        try {
            ItemStack actual = player.getInventory().getItem(slot);
            if (actual == null || actual.getType() == Material.AIR) {
                return true;
            }
        } catch (RuntimeException readback) {
            attachFailure(failure, readback);
        }
        attachFailure(failure, new IllegalStateException(
                "Cannot verify empty book output slot; original source and XP were not restored"));
        return false;
    }

    private static boolean rollbackSource(Player player, EnchantSession session, ItemStack original, RuntimeException failure) {
        restore(() -> player.getInventory().setItem(session.slot, original), failure);
        try {
            if (session.matches(player.getInventory().getItem(session.slot))) {
                return true;
            }
        } catch (RuntimeException readback) {
            attachFailure(failure, readback);
        }
        attachFailure(failure, new IllegalStateException(
                "Cannot verify original source item; XP was not restored"));
        return false;
    }

    static int outputSlot(Player player, int sourceSlot, ItemStack item) {
        if (item.getType() != Material.BOOK || item.getAmount() == 1) {
            return sourceSlot;
        }
        for (int slot = 0; slot < EnchantSession.STORAGE_SIZE; slot++) {
            if (slot != sourceSlot && empty(player.getInventory().getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() == 0;
    }

    static final class OutputSpaceException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
    }

    static final class QuoteChangedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
    }

    private static void restore(Runnable setter, RuntimeException failure) {
        try {
            setter.run();
        } catch (RuntimeException rollback) {
            attachFailure(failure, rollback);
        }
    }

    private static void attachFailure(RuntimeException failure, RuntimeException rollback) {
        if (rollback != failure) {
            failure.addSuppressed(rollback);
        }
    }

    private static void apply(Player player, Experience.Balance balance) {
        player.setLevel(balance.level());
        player.setExp(balance.progress());
        player.setTotalExperience(balance.total());
    }
}
