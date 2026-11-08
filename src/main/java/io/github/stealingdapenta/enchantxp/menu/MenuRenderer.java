package io.github.stealingdapenta.enchantxp.menu;

import io.github.stealingdapenta.enchantxp.enchanting.BookshelfPricing;
import io.github.stealingdapenta.enchantxp.enchanting.EnchantPolicy;
import io.github.stealingdapenta.enchantxp.enchanting.Enchantments;
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

/**
 * Builds unpublished views; the controller alone owns session publication and event fences.
 */
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

    MenuHolder build(TableMenus controller, Player player, EnchantSession session, MenuHolder.Route route, int countShelves, List<Enchantment> availableChoices, List<Integer> availableLevels) {
        List<Enchantment> choices = new ArrayList<>(availableChoices);
        List<Integer> levels = new ArrayList<>(availableLevels);
        sortChoices(session.sort, choices, levels);
        int count = route.stage == MenuStage.ENCHANTMENT ? choices.size() : levels.size();
        MenuHolder.Pagination pagination = new MenuHolder.Pagination(route.index, count, layout.capacity());
        PurchaseQuote quote = route.stage == MenuStage.CONFIRM ? PurchaseQuote.read(player, session, route.enchantment, route.level, prices, shelves, countShelves) : null;
        MenuHolder holder = new MenuHolder(controller, layout, session, route, pagination, quote);
        Map<String, String> values = values(player, session, holder);
        values.put("bookshelf-status", bookshelfStatus(countShelves));
        Inventory inventory = Bukkit.createInventory(holder, layout.size, text.title(route.stage, values));
        holder.attach(inventory);
        renderFrame(inventory, values);
        renderNavigation(session, holder, values);
        renderStage(player, session, holder, values, choices, levels, countShelves);
        return holder;
    }

    private void sortChoices(EnchantSession.Sort sort, List<Enchantment> choices, List<Integer> levels) {
        if (sort == EnchantSession.Sort.DEFAULT) {
            return;
        }
        Comparator<Enchantment> names = (first, second) -> String.CASE_INSENSITIVE_ORDER.compare(ChatColor.stripColor(text.name(first)), ChatColor.stripColor(text.name(second)));
        if (sort == EnchantSession.Sort.DESCENDING) {
            names = names.reversed();
        }
        choices.sort(names.thenComparing(Enchantment::getName));
        levels.sort(sort == EnchantSession.Sort.ASCENDING ? Comparator.naturalOrder() : Comparator.reverseOrder());
    }

    private String bookshelfStatus(int count) {
        if (!shelves.enabled()) {
            return "Bookshelf pricing disabled";
        }
        return "Enlightened by " + ChatColor.GREEN + count + ChatColor.GRAY + "/" + ChatColor.GOLD + shelves.maximum() + ChatColor.GRAY + " bookshelves";
    }

    private void renderFrame(Inventory inventory, Map<String, String> values) {
        for (int slot = 0; slot < layout.size; slot++) {
            inventory.setItem(slot, text.icon(layout.border(slot) ? "border" : "fill", values));
        }
        inventory.setItem(layout.cancel, text.icon("cancel", values));
        inventory.setItem(layout.info, text.icon("info", values));
    }

    private void renderNavigation(EnchantSession session, MenuHolder holder, Map<String, String> values) {
        Inventory inventory = holder.getInventory();
        if (holder.availableChoices > 1 && (holder.page == MenuStage.ENCHANTMENT || holder.page == MenuStage.LEVEL)) {
            inventory.setItem(layout.sort, text.icon("sort", values));
        }
        if (holder.page != MenuStage.ITEM) {
            inventory.setItem(layout.back, text.icon("back", values));
            inventory.setItem(TableMenus.PREVIEW_SLOT, selectionPreview(session, values));
        }
        if (holder.index > 0) {
            inventory.setItem(layout.previous, text.icon("previous", values));
        }
        if (holder.index + 1 < holder.pages) {
            inventory.setItem(layout.next, text.icon("next", values));
        }
    }

    private ItemStack selectionPreview(EnchantSession session, Map<String, String> values) {
        ItemStack preview = session.snapshot();
        ItemMeta meta = preview.getItemMeta();
        if (meta == null) {
            throw new IllegalStateException("Server did not provide item metadata");
        }
        List<String> lore = meta.hasLore() ? meta.getLore() : new ArrayList<>();
        lore.addAll(text.lore("ui.selection-lore", values));
        MenuText.decorate(preview, meta, text.text("ui.selection-name", values), lore);
        return preview;
    }

    private void renderStage(Player player, EnchantSession session, MenuHolder holder, Map<String, String> values, List<Enchantment> choices, List<Integer> levels, int countShelves) {
        Inventory inventory = holder.getInventory();
        if (holder.page == MenuStage.ITEM) {
            inventory.setItem(layout.input, null);
            inventory.setItem(layout.instructions, text.icon("instructions", values));
        } else if (holder.page == MenuStage.ENCHANTMENT || holder.page == MenuStage.LEVEL) {
            renderChoices(player, session, holder, values, choices, levels, countShelves);
        } else {
            inventory.setItem(layout.review, text.book(holder.enchantment, holder.level, values, "review"));
            inventory.setItem(layout.confirm, text.icon(holder.quote.affordable ? "confirm" : "insufficient", values));
        }
    }

    private void renderChoices(Player player, EnchantSession session, MenuHolder holder, Map<String, String> values, List<Enchantment> choices, List<Integer> levels, int countShelves) {
        for (int offset = 0; offset < layout.capacity() && holder.index * layout.capacity() + offset < holder.availableChoices; offset++) {
            int entry = holder.index * layout.capacity() + offset;
            int slot = layout.contentSlot(offset);
            if (holder.page == MenuStage.ENCHANTMENT) {
                renderEnchantment(holder, slot, choices.get(entry), values);
            } else {
                renderLevel(player, session, holder, slot, levels.get(entry), values, countShelves);
            }
        }
    }

    private void renderEnchantment(MenuHolder holder, int slot, Enchantment enchantment, Map<String, String> values) {
        holder.choices.put(slot, enchantment);
        Map<String, String> option = new HashMap<>(values);
        option.put("enchantment", text.name(enchantment));
        holder.getInventory()
              .setItem(slot, text.book(enchantment, 0, option, "type"));
    }

    private void renderLevel(Player player, EnchantSession session, MenuHolder holder, int slot, int level, Map<String, String> values, int countShelves) {
        holder.levels.put(slot, level);
        PurchaseQuote quote = PurchaseQuote.read(player, session, holder.enchantment, level, prices, shelves, countShelves);
        Map<String, String> option = optionValues(player, session, holder.enchantment, level, values, quote);
        holder.getInventory()
              .setItem(slot, text.book(holder.enchantment, level, option, "option"));
    }

    Map<String, String> values(Player player, EnchantSession session, MenuHolder holder) {
        Map<String, String> values = new HashMap<>();
        values.put("player", player.getName() == null ? "" : player.getName());
        values.put("balance", player.getGameMode() == GameMode.CREATIVE ? text.text("ui.unlimited-balance", Collections.emptyMap()) : Integer.toString(balance(player)));
        values.put("page", Integer.toString(holder.index + 1));
        values.put("pages", Integer.toString(holder.pages));
        values.put("sort", session.sort.label(holder.page == MenuStage.LEVEL || holder.page == MenuStage.CONFIRM));
        if (session.hasSelection()) {
            ItemStack item = session.snapshot();
            ItemMeta meta = item.getItemMeta();
            values.put("item", meta.hasDisplayName() ? meta.getDisplayName() : item.getType()
                                                                                   .name()
                                                                                   .replace('_', ' '));
        }
        if (holder.enchantment != null) {
            values = optionValues(player, session, holder.enchantment, holder.level, values, holder.quote);
        }
        return values;
    }

    private Map<String, String> optionValues(Player player, EnchantSession session, Enchantment enchantment, int level, Map<String, String> values, PurchaseQuote quote) {
        Map<String, String> result = new HashMap<>(values);
        result.put("enchantment", text.name(enchantment));
        result.put("level", level == 0 ? "" : Integer.toString(level));
        result.put("cost", quote == null ? "" : Integer.toString(quote.cost));
        result.put("missing", missingXp(player, quote));
        result.put("current", Integer.toString(Enchantments.level(session.snapshot(), enchantment)));
        result.put("max", Integer.toString(policy.maximum(session.snapshot()
                                                                 .getType(), enchantment)));
        return result;
    }

    private static String missingXp(Player player, PurchaseQuote quote) {
        if (quote == null) {
            return "";
        }
        int missing = 0;
        if (player.getGameMode() != GameMode.CREATIVE) {
            missing = Math.max(0, quote.cost - balance(player));
        }
        return Integer.toString(missing);
    }

    private static int balance(Player player) {
        return Experience.total(player.getLevel(), player.getExp());
    }
}
