package io.github.stealingdapenta.enchantxp.enchanting;

import io.github.stealingdapenta.enchantxp.configuration.values.ConfigValues;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;

/**
 * Immutable cumulative raw-XP valuations and purchase-charge limits; neither item legality nor access is decided here.
 */
public final class Prices {

    public static final int HARD_MAX_COST = 1000000;
    private static final String BASE_XP_KEY = "base-xp";
    private static final String ENCHANTMENT_PATH = "enchantments.";
    private static final String LEVELS_KEY = "levels";
    private final int base;
    private final int maximum;
    private final Map<String, Integer> bases;
    private final Map<String, Map<Integer, Integer>> levels;

    private Prices(int base, int maximum, Map<String, Integer> bases, Map<String, Map<Integer, Integer>> levels) {
        this.base = base;
        this.maximum = maximum;
        this.bases = Collections.unmodifiableMap(new HashMap<>(bases));
        this.levels = Collections.unmodifiableMap(new HashMap<>(levels));
    }

    /**
     * Reads required prices and optional overrides; `max-cost: -1` disables only the configured charge ceiling.
     *
     * @throws IllegalArgumentException for malformed prices or cumulative totals that fail to increase through level 255
     */
    public static Prices read(ConfigurationSection config) {
        int base = boundedInteger(config.get(BASE_XP_KEY), BASE_XP_KEY);
        int maximum = ConfigValues.integer(config.get("max-cost"), "max-cost", -1, HARD_MAX_COST);
        if (maximum == 0) {
            throw new IllegalArgumentException("max-cost must be -1 (no configured ceiling) or 1..1000000");
        }
        if (maximum != -1 && base > maximum) {
            throw new IllegalArgumentException("base-xp must not exceed max-cost");
        }
        Map<String, Integer> bases = new HashMap<>();
        Map<String, Map<Integer, Integer>> levels = new HashMap<>();
        readEnchantments(config, bases, levels);
        Prices prices = new Prices(base, maximum, bases, levels);
        for (String name : Enchantments.names()) {
            prices.validateIncreasingValues(name);
        }
        return prices;
    }

    private static void readEnchantments(ConfigurationSection config, Map<String, Integer> bases, Map<String, Map<Integer, Integer>> levels) {
        ConfigurationSection enchants = ConfigValues.section(config, "enchantments");
        if (enchants == null) {
            return;
        }
        for (String name : enchants.getKeys(false)) {
            String path = ENCHANTMENT_PATH + name;
            EnchantPolicy.requireName(name, path);
            ConfigurationSection enchant = ConfigValues.requiredSection(config, path);
            ConfigValues.keys(enchant, "name", "enabled", "max-level", BASE_XP_KEY, LEVELS_KEY);
            if (enchant.contains(BASE_XP_KEY, true)) {
                bases.put(name, boundedInteger(enchant.get(BASE_XP_KEY), path + "." + BASE_XP_KEY));
            }
            levels.put(name, readLevelValues(ConfigValues.section(config, path + "." + LEVELS_KEY)));
        }
    }

    private static Map<Integer, Integer> readLevelValues(ConfigurationSection costs) {
        Map<Integer, Integer> explicit = new HashMap<>();
        if (costs != null) {
            for (String key : costs.getKeys(false)) {
                String path = costs.getCurrentPath() + "." + key;
                explicit.put(levelKey(key, path), boundedInteger(costs.get(key), path));
            }
        }
        return Collections.unmodifiableMap(explicit);
    }

    private static int levelKey(String key, String path) {
        int level;
        try {
            level = Integer.parseInt(key);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(path + " must be a level 1..255");
        }
        if (level < 1 || level > EnchantPolicy.MAX_LEVEL || !key.equals(Integer.toString(level))) {
            throw new IllegalArgumentException(path + " must be a level 1..255");
        }
        return level;
    }

    /**
     * Checks calculated neighbors too: an explicit total can invalidate the following fallback level.
     */
    private void validateIncreasingValues(String name) {
        long previous = 0;
        for (int level = 1; level <= EnchantPolicy.MAX_LEVEL; level++) {
            long value = value(name, level);
            if (value <= previous) {
                Map<Integer, Integer> explicit = levels.get(name);
                int offending = explicit != null && explicit.containsKey(level) ? level : level - 1;
                throw new IllegalArgumentException(ENCHANTMENT_PATH + name + "." + LEVELS_KEY + "." + offending + " must produce strictly increasing cumulative values (level " + level + ")");
            }
            previous = value;
        }
    }

    private static int boundedInteger(Object value, String key) {
        return ConfigValues.integer(value, key, 1, HARD_MAX_COST);
    }

    /**
     * Returns the global cumulative raw-XP cost for level `1..255`, subject to purchase-charge limits.
     *
     * @throws IllegalArgumentException if the level or charge is invalid
     */
    public int cost(int level) {
        return checked(quote(null, level));
    }

    /**
     * Returns the overridden cumulative raw-XP cost for level `1..255`; a null enchantment uses global prices.
     *
     * @throws IllegalArgumentException if the level or charge is invalid
     */
    public int cost(Enchantment enchantment, int level) {
        return checked(quote(enchantment, level));
    }

    /**
     * Converts a positive raw-XP quote to an integer only after checking both configured and representable limits.
     *
     * @throws IllegalArgumentException if the charge is not accepted
     */
    public int checked(long cost) {
        if (!accepts(cost)) {
            throw new IllegalArgumentException("This choice exceeds max-cost or the representable raw-XP charge");
        }
        return (int) cost;
    }

    /** Tests a positive raw-XP charge against the integer bound and optional configured ceiling. */
    public boolean accepts(long cost) {
        return cost >= 1 && cost <= Integer.MAX_VALUE && (maximum == -1 || cost <= maximum);
    }

    /** Tests whether the global cumulative price for level `1..255` is an accepted charge. */
    public boolean offers(int level) {
        return offers(null, level);
    }

    /** Tests an overridden cumulative price; null uses global prices and invalid levels return false. */
    public boolean offers(Enchantment enchantment, int level) {
        long quote = quote(enchantment, level);
        return accepts(quote);
    }

    /**
     * Returns the raw-XP cumulative difference from a nonnegative current level to a strictly higher target at most 255, before bookshelf adjustment.
     *
     * @throws IllegalArgumentException if the levels or resulting charge are invalid
     */
    public int upgradeCost(Enchantment enchantment, int current, int target) {
        return checked(upgradeQuote(enchantment, current, target));
    }

    /** Tests the upgrade difference, not the target's full total; current zero means no existing enchantment. */
    public boolean offersUpgrade(Enchantment enchantment, int current, int target) {
        long quote = upgradeQuote(enchantment, current, target);
        return accepts(quote);
    }

    /**
     * Returns the cumulative difference, or `-1` unless current is nonnegative and target is strictly higher and at most 255.
     */
    long upgradeQuote(Enchantment enchantment, int current, int target) {
        if (current < 0 || target < 1 || target > EnchantPolicy.MAX_LEVEL || target <= current) {
            return -1;
        }
        return quote(enchantment, target) - (current == 0 ? 0 : quote(enchantment, current));
    }

    private long quote(Enchantment enchantment, int level) {
        if (level < 1 || level > EnchantPolicy.MAX_LEVEL) {
            return -1;
        }
        String name = enchantment == null ? "" : enchantment.getName();
        return value(name, level);
    }

    private long value(String name, int level) {
        Map<Integer, Integer> explicit = levels.get(name);
        if (explicit != null && explicit.containsKey(level)) {
            return explicit.get(level);
        }
        long startingPrice = bases.getOrDefault(name, base);
        long levelFactor = level;
        return Math.multiplyExact(Math.multiplyExact(startingPrice, levelFactor), levelFactor);
    }
}
