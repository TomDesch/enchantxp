package io.github.stealingdapenta.enchantxp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

public final class Enchantments {
    private static final Set<String> VANILLA = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "PROTECTION_ENVIRONMENTAL", "PROTECTION_FIRE", "PROTECTION_FALL", "PROTECTION_EXPLOSIONS",
            "PROTECTION_PROJECTILE", "OXYGEN", "WATER_WORKER", "THORNS", "DEPTH_STRIDER", "FROST_WALKER",
            "BINDING_CURSE", "DAMAGE_ALL", "DAMAGE_UNDEAD", "DAMAGE_ARTHROPODS", "KNOCKBACK", "FIRE_ASPECT",
            "LOOT_BONUS_MOBS", "SWEEPING_EDGE", "DIG_SPEED", "SILK_TOUCH", "DURABILITY", "LOOT_BONUS_BLOCKS",
            "ARROW_DAMAGE", "ARROW_KNOCKBACK", "ARROW_FIRE", "ARROW_INFINITE", "LUCK", "LURE", "MENDING", "VANISHING_CURSE")));

    private Enchantments() {
    }

    public static boolean supported(ItemStack item) {
        return supported(item, EnchantPolicy.vanilla());
    }

    public static boolean supported(ItemStack item, EnchantPolicy policy) {
        if (item == null || item.getAmount() < 1 ||
                item.getAmount() > (item.getType() == Material.BOOK ? Material.BOOK.getMaxStackSize() : 1) ||
                item.getType() == Material.AIR ||
                item.getType() == Material.BOOK_AND_QUILL || item.getType() == Material.WRITTEN_BOOK) {
            return false;
        }
        if (item.getType() == Material.ENCHANTED_BOOK && !(item.getItemMeta() instanceof EnchantmentStorageMeta)) {
            return false;
        }
        // Books store enchantments separately; ordinary enchants on books are not a supported input.
        if (book(item) && !item.getEnchantments().isEmpty()) {
            return false;
        }
        boolean applicable = book(item);
        for (String name : VANILLA) {
            Enchantment candidate = Enchantment.getByName(name);
            applicable |= vanilla(candidate) && candidate.canEnchantItem(item);
        }
        if (!applicable) {
            return false;
        }
        for (Enchantment existing : current(item).keySet()) {
            int level = level(item, existing);
            if (!vanilla(existing) || (!book(item) && !existing.canEnchantItem(item)) ||
                    level < existing.getStartLevel() ||
                    level > EnchantPolicy.MAX_LEVEL ||
                    level > Math.max(existing.getMaxLevel(), policy.maximum(item.getType(), existing))) {
                return false;
            }
            for (Enchantment other : current(item).keySet()) {
                if (!existing.equals(other) && conflicts(existing, other)) {
                    return false;
                }
            }
        }
        return true;
    }

    public static List<Enchantment> choices(ItemStack item, Prices prices) {
        return choices(item, prices, EnchantPolicy.vanilla());
    }

    public static List<Enchantment> choices(ItemStack item, Prices prices, EnchantPolicy policy) {
        if (!supported(item, policy)) {
            return Collections.emptyList();
        }
        List<Enchantment> result = new ArrayList<Enchantment>();
        for (String name : VANILLA) {
            Enchantment enchantment = Enchantment.getByName(name);
            if (enchantment == null) {
                continue;
            }
            if (!levels(item, enchantment, prices, policy).isEmpty()) {
                result.add(enchantment);
            }
        }
        Collections.sort(result, (first, second) -> first.getName().compareTo(second.getName()));
        return Collections.unmodifiableList(result);
    }

    public static boolean legal(ItemStack item, Enchantment enchantment, int level) {
        return legal(item, enchantment, level, EnchantPolicy.vanilla());
    }

    public static boolean legal(ItemStack item, Enchantment enchantment, int level, EnchantPolicy policy) {
        if (!supported(item, policy) || !vanilla(enchantment) || level < enchantment.getStartLevel() ||
                level > policy.maximum(item.getType(), enchantment) || level > EnchantPolicy.MAX_LEVEL ||
                level <= level(item, enchantment) ||
                (!book(item) && !enchantment.canEnchantItem(item))) {
            return false;
        }
        for (Enchantment existing : current(item).keySet()) {
            if (!existing.equals(enchantment) && conflicts(existing, enchantment)) {
                return false;
            }
        }
        return true;
    }

    public static List<Integer> levels(ItemStack item, Enchantment enchantment, Prices prices, EnchantPolicy policy) {
        if (!supported(item, policy) || !vanilla(enchantment)) {
            return Collections.emptyList();
        }
        List<Integer> levels = new ArrayList<Integer>();
        int maximum = Math.min(EnchantPolicy.MAX_LEVEL, policy.maximum(item.getType(), enchantment));
        for (int level = enchantment.getStartLevel(); level <= maximum; level++) {
            if (prices.offersUpgrade(enchantment, level(item, enchantment), level) && legal(item, enchantment, level, policy)) {
                levels.add(level);
            }
        }
        return Collections.unmodifiableList(levels);
    }

    static Set<String> names() {
        return VANILLA;
    }

    public static boolean book(ItemStack item) {
        return item.getType() == Material.BOOK || item.getType() == Material.ENCHANTED_BOOK;
    }

    public static Map<Enchantment, Integer> current(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return item.getType() == Material.ENCHANTED_BOOK && meta instanceof EnchantmentStorageMeta ?
                ((EnchantmentStorageMeta) meta).getStoredEnchants() : item.getEnchantments();
    }

    public static int level(ItemStack item, Enchantment enchantment) {
        return current(item).getOrDefault(enchantment, 0);
    }

    public static boolean hasLegalUpgrade(ItemStack item, EnchantPolicy policy) {
        if (!supported(item, policy)) {
            return false;
        }
        for (String name : VANILLA) {
            Enchantment enchantment = Enchantment.getByName(name);
            if (vanilla(enchantment) && legal(item, enchantment,
                    Math.max(enchantment.getStartLevel(), level(item, enchantment) + 1), policy)) {
                return true;
            }
        }
        return false;
    }

    private static boolean vanilla(Enchantment enchantment) {
        return enchantment != null && VANILLA.contains(enchantment.getName()) &&
                Enchantment.getByName(enchantment.getName()) == enchantment;
    }

    private static boolean conflicts(Enchantment first, Enchantment second) {
        return first.conflictsWith(second) || second.conflictsWith(first);
    }
}
