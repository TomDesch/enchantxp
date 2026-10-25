package io.github.stealingdapenta.enchantxp.menu;

import io.github.stealingdapenta.enchantxp.enchanting.BookshelfPricing;
import io.github.stealingdapenta.enchantxp.enchanting.Enchantments;
import io.github.stealingdapenta.enchantxp.enchanting.EnchantPolicy;
import io.github.stealingdapenta.enchantxp.enchanting.Experience;
import io.github.stealingdapenta.enchantxp.enchanting.Prices;
import io.github.stealingdapenta.enchantxp.presentation.MenuStage;
import io.github.stealingdapenta.enchantxp.presentation.MenuText;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Builds unpublished views; the controller alone owns session publication and event fences. */
final class MenuRenderer {
    private final Prices prices;
    private final EnchantPolicy policy;
    private final MenuText text;
    private final MenuLayout layout;
    private final BookshelfPricing shelves;

    MenuRenderer(Prices prices, EnchantPolicy policy, MenuText text, MenuLayout layout, BookshelfPricing shelves) {
        this.prices = prices;
        this.policy = policy;
        this.text = text;
        this.layout = layout;
        this.shelves = shelves;
    }

    MenuHolder build(TableMenus controller, Player player, EnchantSession session, MenuStage page,
            Enchantment enchantment, int level, int index, int countShelves,
            List<Enchantment> availableChoices, List<Integer> availableLevels) {
        List<Enchantment> choices = new ArrayList<Enchantment>(availableChoices);
        List<Integer> levels = new ArrayList<Integer>(availableLevels);
        if (session.sort != EnchantSession.Sort.DEFAULT) {
            Comparator<Enchantment> names = (first, second) ->
                    String.CASE_INSENSITIVE_ORDER.compare(ChatColor.stripColor(text.name(first)), ChatColor.stripColor(text.name(second)));
            if (session.sort == EnchantSession.Sort.DESCENDING) {
                names = names.reversed();
            }
            choices.sort(names.thenComparing(Enchantment::getName));
            levels.sort(session.sort == EnchantSession.Sort.ASCENDING ? Comparator.naturalOrder() : Comparator.reverseOrder());
        }
        int count = page == MenuStage.ENCHANTMENT ? choices.size() : levels.size();
        int pages = Math.max(1, (count + layout.capacity() - 1) / layout.capacity());
        index = Math.max(0, Math.min(index, pages - 1));
        PurchaseQuote quote = page == MenuStage.CONFIRM ?
                PurchaseQuote.read(player, session, enchantment, level, prices, shelves, countShelves) : null;
        MenuHolder holder = new MenuHolder(controller, layout, session, page, enchantment, level, index, pages, quote, count);
        Map<String, String> values = values(player, session, holder);
        values.put("bookshelf-status", shelves.enabled() ?
                "Enlightened by " + ChatColor.GREEN + countShelves + ChatColor.GRAY + "/" +
                        ChatColor.GOLD + shelves.maximum() + ChatColor.GRAY + " bookshelves" :
                "Bookshelf pricing disabled");
        Inventory inventory = Bukkit.createInventory(holder, layout.size, text.title(page, values));
        holder.attach(inventory);
        for (int slot = 0; slot < layout.size; slot++) {
            inventory.setItem(slot, text.icon(layout.border(slot) ? "border" : "fill", values));
        }
        inventory.setItem(layout.cancel, text.icon("cancel", values));
        inventory.setItem(layout.info, text.icon("info", values));
        if (holder.availableChoices > 1 && (page == MenuStage.ENCHANTMENT || page == MenuStage.LEVEL)) {
            inventory.setItem(layout.sort, text.icon("sort", values));
        }
        if (page != MenuStage.ITEM) {
            inventory.setItem(layout.back, text.icon("back", values));
            ItemStack preview = session.snapshot();
            List<String> lore = preview.getItemMeta().hasLore() ?
                    preview.getItemMeta().getLore() : new ArrayList<String>();
            lore.addAll(text.lore("ui.selection-lore", values));
            MenuText.decorate(preview, text.text("ui.selection-name", values), lore);
            inventory.setItem(TableMenus.PREVIEW_SLOT, preview);
        }
        if (index > 0) {
            inventory.setItem(layout.previous, text.icon("previous", values));
        }
        if (index + 1 < pages) {
            inventory.setItem(layout.next, text.icon("next", values));
        }
        if (page == MenuStage.ITEM) {
            inventory.setItem(layout.input, null);
            inventory.setItem(layout.instructions, text.icon("instructions", values));
        } else if (page == MenuStage.ENCHANTMENT || page == MenuStage.LEVEL) {
            for (int offset = 0; offset < layout.capacity() && index * layout.capacity() + offset < count; offset++) {
                int entry = index * layout.capacity() + offset;
                int slot = layout.contentSlot(offset);
                Enchantment choice = page == MenuStage.ENCHANTMENT ? choices.get(entry) : enchantment;
                int offered = page == MenuStage.ENCHANTMENT ? 0 : levels.get(entry);
                if (page == MenuStage.ENCHANTMENT) {
                    holder.choices.put(slot, choice);
                } else {
                    holder.levels.put(slot, offered);
                }
                Map<String, String> option;
                if (page == MenuStage.ENCHANTMENT) {
                    option = new HashMap<String, String>(values);
                    option.put("enchantment", text.name(choice));
                } else {
                    option = optionValues(player, session, choice, offered, values,
                            PurchaseQuote.read(player, session, choice, offered, prices, shelves, countShelves));
                }
                inventory.setItem(slot, text.book(choice, offered, option,
                        page == MenuStage.ENCHANTMENT ? "type" : "option"));
            }
        } else {
            inventory.setItem(layout.review, text.book(enchantment, level, values, "review"));
            inventory.setItem(layout.confirm, text.icon(quote.affordable ? "confirm" : "insufficient", values));
        }
        return holder;
    }

    Map<String, String> values(Player player, EnchantSession session, MenuHolder holder) {
        Map<String, String> values = new HashMap<String, String>();
        values.put("player", player.getName() == null ? "" : player.getName());
        values.put("balance", player.getGameMode() == GameMode.CREATIVE ?
                text.text("ui.unlimited-balance", Collections.emptyMap()) : Integer.toString(balance(player)));
        values.put("page", Integer.toString(holder.index + 1));
        values.put("pages", Integer.toString(holder.pages));
        values.put("sort", session.sort.label(holder.page == MenuStage.LEVEL || holder.page == MenuStage.CONFIRM));
        if (session.hasSelection()) {
            ItemStack item = session.snapshot();
            ItemMeta meta = item.getItemMeta();
            values.put("item", meta.hasDisplayName() ? meta.getDisplayName() : item.getType().name().replace('_', ' '));
        }
        if (holder.enchantment != null) {
            values = optionValues(player, session, holder.enchantment, holder.level, values, holder.quote);
        }
        return values;
    }

    private Map<String, String> optionValues(Player player, EnchantSession session, Enchantment enchantment, int level,
            Map<String, String> values, PurchaseQuote quote) {
        Map<String, String> result = new HashMap<String, String>(values);
        result.put("enchantment", text.name(enchantment));
        result.put("level", level == 0 ? "" : Integer.toString(level));
        result.put("cost", quote == null ? "" : Integer.toString(quote.cost));
        result.put("missing", quote == null ? "" : Integer.toString(player.getGameMode() == GameMode.CREATIVE ?
                0 : Math.max(0, quote.cost - balance(player))));
        result.put("current", Integer.toString(Enchantments.level(session.snapshot(), enchantment)));
        result.put("max", Integer.toString(policy.maximum(session.snapshot().getType(), enchantment)));
        return result;
    }

    private static int balance(Player player) {
        return Experience.total(player.getLevel(), player.getExp());
    }
}
