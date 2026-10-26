package io.github.stealingdapenta.enchantxp.enchanting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Vanilla 1.12.2 catalogue and non-mutating item legality checks; custom enchantment effects are outside this contract.
 */
public final class Enchantments {

    private static final Map<String, String> CATALOGUE;
    private static final Set<String> VANILLA;

    static {
        Map<String, String> catalogue = new LinkedHashMap<>();
        catalogue.put("PROTECTION_ENVIRONMENTAL", "protection");
        catalogue.put("PROTECTION_FIRE", "fire_protection");
        catalogue.put("PROTECTION_FALL", "feather_falling");
        catalogue.put("PROTECTION_EXPLOSIONS", "blast_protection");
        catalogue.put("PROTECTION_PROJECTILE", "projectile_protection");
        catalogue.put("OXYGEN", "respiration");
        catalogue.put("WATER_WORKER", "aqua_affinity");
        catalogue.put("THORNS", "thorns");
        catalogue.put("DEPTH_STRIDER", "depth_strider");
        catalogue.put("FROST_WALKER", "frost_walker");
        catalogue.put("BINDING_CURSE", "binding_curse");
        catalogue.put("DAMAGE_ALL", "sharpness");
        catalogue.put("DAMAGE_UNDEAD", "smite");
        catalogue.put("DAMAGE_ARTHROPODS", "bane_of_arthropods");
        catalogue.put("KNOCKBACK", "knockback");
        catalogue.put("FIRE_ASPECT", "fire_aspect");
        catalogue.put("LOOT_BONUS_MOBS", "looting");
        catalogue.put("SWEEPING_EDGE", "sweeping_edge");
        catalogue.put("DIG_SPEED", "efficiency");
        catalogue.put("SILK_TOUCH", "silk_touch");
        catalogue.put("DURABILITY", "unbreaking");
        catalogue.put("LOOT_BONUS_BLOCKS", "fortune");
        catalogue.put("ARROW_DAMAGE", "power");
        catalogue.put("ARROW_KNOCKBACK", "punch");
        catalogue.put("ARROW_FIRE", "flame");
        catalogue.put("ARROW_INFINITE", "infinity");
        catalogue.put("LUCK", "luck_of_the_sea");
        catalogue.put("LURE", "lure");
        catalogue.put("MENDING", "mending");
        catalogue.put("VANISHING_CURSE", "vanishing_curse");
        CATALOGUE = Collections.unmodifiableMap(catalogue);
        VANILLA = CATALOGUE.keySet();
    }

    static Map<String, String> permissionSlugs() {
        return CATALOGUE;
    }

    private Enchantments() {
    }

    /** Tests a nullable input stack against vanilla caps, book metadata and pairwise enchantment consistency. */
    public static boolean supported(ItemStack item) {
        return supported(item, EnchantPolicy.vanilla());
    }

    /**
     * Tests stack shape, applicability and existing metadata against vanilla or configured effective caps.
     * Null, AIR, nonpositive stacks, stacked equipment and unsupported book forms return false.
     */
    public static boolean supported(ItemStack item, EnchantPolicy policy) {
        return supportedStack(item) && applicable(item) && consistentEnchantments(item, policy);
    }

    private static boolean supportedStack(ItemStack item) {
        if (item == null || item.getAmount() < 1 || item.getAmount() > (item.getType() == Material.BOOK ? Material.BOOK.getMaxStackSize() : 1) || item.getType() == Material.AIR || item.getType() == Material.BOOK_AND_QUILL
                || item.getType() == Material.WRITTEN_BOOK) {
            return false;
        }
        if (item.getType() == Material.ENCHANTED_BOOK && !(item.getItemMeta() instanceof EnchantmentStorageMeta)) {
            return false;
        }
        // Books store enchantments separately; ordinary enchants on books are not a supported input.
        return !book(item) || item.getEnchantments()
                                  .isEmpty();
    }

    private static boolean applicable(ItemStack item) {
        boolean applicable = book(item);
        for (String name : VANILLA) {
            Enchantment candidate = Enchantment.getByName(name);
            applicable |= vanilla(candidate) && candidate.canEnchantItem(item);
        }
        return applicable;
    }

    private static boolean consistentEnchantments(ItemStack item, EnchantPolicy policy) {
        Map<Enchantment, Integer> existingLevels = current(item);
        for (Map.Entry<Enchantment, Integer> entry : existingLevels.entrySet()) {
            if (!validExisting(item, entry.getKey(), entry.getValue(), policy)) {
                return false;
            }
            for (Enchantment other : existingLevels.keySet()) {
                if (!entry.getKey()
                          .equals(other) && conflicts(entry.getKey(), other)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean validExisting(ItemStack item, Enchantment existing, int level, EnchantPolicy policy) {
        return vanilla(existing) && (book(item) || existing.canEnchantItem(item)) && level >= existing.getStartLevel() && level <= EnchantPolicy.MAX_LEVEL && level <= Math.max(existing.getMaxLevel(), policy.maximum(item.getType(), existing));
    }

    /** Returns immutable name-sorted enchantments with affordable legal upgrades under vanilla caps. */
    public static List<Enchantment> choices(ItemStack item, Prices prices) {
        return choices(item, prices, EnchantPolicy.vanilla());
    }

    /**
     * Returns immutable name-sorted choices passing item, policy and base-price checks, but not permissions or shelves.
     * Unsupported inputs return an empty list.
     */
    public static List<Enchantment> choices(ItemStack item, Prices prices, EnchantPolicy policy) {
        if (!supported(item, policy)) {
            return Collections.emptyList();
        }
        List<Enchantment> result = new ArrayList<>();
        for (String name : VANILLA) {
            Enchantment enchantment = Enchantment.getByName(name);
            if (enchantment == null) {
                continue;
            }
            if (!levels(item, enchantment, prices, policy).isEmpty()) {
                result.add(enchantment);
            }
        }
        result.sort(Comparator.comparing(Enchantment::getName));
        return Collections.unmodifiableList(result);
    }

    /** Tests a strictly higher target under vanilla caps without changing the item or checking its price. */
    public static boolean legal(ItemStack item, Enchantment enchantment, int level) {
        return legal(item, enchantment, level, EnchantPolicy.vanilla());
    }

    /**
     * Tests a canonical catalogue enchantment, strict upgrade, effective cap, applicability and symmetric conflicts.
     * Price, permissions and player context are deliberately separate checks.
     */
    public static boolean legal(ItemStack item, Enchantment enchantment, int level, EnchantPolicy policy) {
        if (!supported(item, policy) || !vanilla(enchantment) || level < enchantment.getStartLevel() || level > policy.maximum(item.getType(), enchantment) || level > EnchantPolicy.MAX_LEVEL || level <= level(item, enchantment) || (!book(item)
                && !enchantment.canEnchantItem(item))) {
            return false;
        }
        for (Enchantment existing : current(item).keySet()) {
            if (!existing.equals(enchantment) && conflicts(existing, enchantment)) {
                return false;
            }
        }
        return true;
    }

    /** Returns immutable ascending legal target levels with accepted base-price differences, bounded by 255. */
    public static List<Integer> levels(ItemStack item, Enchantment enchantment, Prices prices, EnchantPolicy policy) {
        if (!supported(item, policy) || !vanilla(enchantment)) {
            return Collections.emptyList();
        }
        List<Integer> levels = new ArrayList<>();
        int maximum = Math.min(EnchantPolicy.MAX_LEVEL, policy.maximum(item.getType(), enchantment));
        for (int level = enchantment.getStartLevel(); level <= maximum; level++) {
            if (prices.offersUpgrade(enchantment, level(item, enchantment), level) && legal(item, enchantment, level, policy)) {
                levels.add(level);
            }
        }
        return Collections.unmodifiableList(levels);
    }

    /** Returns the immutable supported vanilla name set shared with the stable permission-slug catalogue. */
    public static Set<String> names() {
        return VANILLA;
    }

    /** Tests whether a nonnull item is a plain or enchanted book, independent of stack legality. */
    public static boolean book(ItemStack item) {
        return item.getType() == Material.BOOK || item.getType() == Material.ENCHANTED_BOOK;
    }

    /**
     * Reads stored enchantments for an enchanted book, otherwise ordinary item enchantments.
     * Callers must supply a nonnull item and must not mutate the returned Bukkit-owned snapshot.
     */
    public static Map<Enchantment, Integer> current(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return item.getType() == Material.ENCHANTED_BOOK && meta instanceof EnchantmentStorageMeta ? ((EnchantmentStorageMeta) meta).getStoredEnchants() : item.getEnchantments();
    }

    /** Returns an item's current stored/ordinary level, or zero when that enchantment is absent. */
    public static int level(ItemStack item, Enchantment enchantment) {
        return current(item).getOrDefault(enchantment, 0);
    }

    /** Tests whether any canonical enchantment can advance legally, ignoring price, shelves and player access. */
    public static boolean hasLegalUpgrade(ItemStack item, EnchantPolicy policy) {
        if (!supported(item, policy)) {
            return false;
        }
        for (String name : VANILLA) {
            Enchantment enchantment = Enchantment.getByName(name);
            if (vanilla(enchantment) && legal(item, enchantment, Math.max(enchantment.getStartLevel(), level(item, enchantment) + 1), policy)) {
                return true;
            }
        }
        return false;
    }

    private static boolean vanilla(Enchantment enchantment) {
        return enchantment != null && VANILLA.contains(enchantment.getName()) && Enchantment.getByName(enchantment.getName()) == enchantment;
    }

    private static boolean conflicts(Enchantment first, Enchantment second) {
        return first.conflictsWith(second) || second.conflictsWith(first);
    }
}
