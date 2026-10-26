package io.github.stealingdapenta.enchantxp.enchanting;

import io.github.stealingdapenta.enchantxp.configuration.values.ConfigValues;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;

/**
 * Legacy enchanting power scan. No query may cause a neighboring chunk to load.
 */
public final class BookshelfPricing {

    private final boolean enabled;
    private final int penalty;
    private final int reduction;
    private final int maximum;

    private BookshelfPricing(boolean enabled, int penalty, int reduction, int maximum) {
        this.enabled = enabled;
        this.penalty = penalty;
        this.reduction = reduction;
        this.maximum = maximum;
    }

    /**
     * Reads bounded percentages and a shelf maximum in `1..15`, using shipped optional defaults.
     *
     * @throws IllegalArgumentException with a path for malformed or out-of-range settings
     */
    public static BookshelfPricing read(ConfigurationSection config) {
        ConfigurationSection defaults = ConfigValues.defaults();
        ConfigurationSection section = ConfigValues.section(config, "bookshelf-pricing");
        ConfigValues.keys(section, "enabled", "zero-shelf-penalty-percent", "per-bookshelf-reduction-percent", "max-bookshelves");
        return new BookshelfPricing(ConfigValues.bool(config, defaults, "bookshelf-pricing.enabled"), integer(config, defaults, "zero-shelf-penalty-percent", 0, 1000000), integer(config, defaults, "per-bookshelf-reduction-percent", 0, 1000000),
                                    integer(config, defaults, "max-bookshelves", 1, 15));
    }

    /** Returns identity pricing with scans disabled and the vanilla shelf maximum of 15. */
    public static BookshelfPricing disabled() {
        return new BookshelfPricing(false, 0, 0, 15);
    }

    /** Reports whether quotes use shelf surcharges and scans inspect the world. */
    public boolean enabled() {
        return enabled;
    }

    /** Returns the configured shelf-count cap, no greater than vanilla's 15. */
    public int maximum() {
        return maximum;
    }

    private static int integer(ConfigurationSection config, ConfigurationSection defaults, String key, int min, int max) {
        String path = "bookshelf-pricing." + key;
        return ConfigValues.integer(ConfigValues.value(config, defaults, path), path, min, max);
    }

    /**
     * Applies the remaining percentage surcharge to positive raw XP, rounding upward to a whole point.
     * Shelf counts clamp to `0..maximum`; invalid or out-of-domain base differences return `-1`.
     * This does not enforce the configured purchase ceiling.
     */
    public long modify(long baseDifference, int shelves) {
        // Maximum base valuation is 1000000 * 255^2; the bounded percentage product fits long.
        if (baseDifference <= 0 || baseDifference > (long) Prices.HARD_MAX_COST * EnchantPolicy.MAX_LEVEL * EnchantPolicy.MAX_LEVEL) {
            return -1;
        }
        long extra = enabled ? Math.max(0L, penalty - (long) Math.min(Math.max(0, shelves), maximum) * reduction) : 0;
        return (baseDifference * (100L + extra) + 99L) / 100L;
    }

    /** Returns the shelf-adjusted cumulative upgrade difference in raw XP, or `-1` for an invalid upgrade. */
    public long quote(Prices prices, Enchantment enchantment, int current, int target, int count) {
        return modify(prices.upgradeQuote(enchantment, current, target), count);
    }

    /**
     * Counts reachable 1.12.2 shelves through two-block-high air gaps without loading chunks.
     * Returns zero without inspecting the world when disabled.
     *
     * @throws IllegalStateException if an enabled scan runs off the server thread
     */
    public int count(Location table) {
        if (!enabled) {
            return 0;
        }
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("EnchantXP bookshelf scans require the server thread");
        }
        World world = table.getWorld();
        int x = table.getBlockX();
        int y = table.getBlockY();
        int z = table.getBlockZ();
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if ((dx == 0 && dz == 0) || !is(world, x + dx, y, z + dz, Material.AIR) || !is(world, x + dx, y + 1, z + dz, Material.AIR)) {
                    continue;
                }
                count += shelves(world, x + dx * 2, y, z + dz * 2);
                if (dx != 0 && dz != 0) {
                    count += shelves(world, x + dx * 2, y, z + dz);
                    count += shelves(world, x + dx, y, z + dz * 2);
                }
            }
        }
        return Math.min(count, maximum);
    }

    private static int shelves(World world, int x, int y, int z) {
        return (is(world, x, y, z, Material.BOOKSHELF) ? 1 : 0) + (is(world, x, y + 1, z, Material.BOOKSHELF) ? 1 : 0);
    }

    private static boolean is(World world, int x, int y, int z, Material material) {
        return y >= 0 && y <= 255 && world.isChunkLoaded(x >> 4, z >> 4) && world.getBlockAt(x, y, z)
                                                                                 .getType() == material;
    }
}
