package io.github.stealingdapenta.enchantxp.enchanting;

import io.github.stealingdapenta.enchantxp.configuration.values.ConfigValues;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;

public final class Prices {
    public static final int HARD_MAX_COST = 1000000;
    private final int base;
    private final int maximum;
    private final Map<String, Integer> bases;
    private final Map<String, Map<Integer, Integer>> levels;

    private Prices(int base, int maximum, Map<String, Integer> bases, Map<String, Map<Integer, Integer>> levels) {
        this.base = base;
        this.maximum = maximum;
        this.bases = Collections.unmodifiableMap(new HashMap<String, Integer>(bases));
        this.levels = Collections.unmodifiableMap(new HashMap<String, Map<Integer, Integer>>(levels));
    }

    public static Prices read(ConfigurationSection config) {
        int base = boundedInteger(config.get("base-xp"), "base-xp");
        int maximum = boundedInteger(config.get("max-cost"), "max-cost");
        if (base > maximum) {
            throw new IllegalArgumentException("base-xp must not exceed max-cost");
        }
        Map<String, Integer> bases = new HashMap<String, Integer>();
        Map<String, Map<Integer, Integer>> levels = new HashMap<String, Map<Integer, Integer>>();
        ConfigurationSection enchants = ConfigValues.section(config, "enchantments");
        if (enchants != null) {
            for (String name : enchants.getKeys(false)) {
                String path = "enchantments." + name;
                EnchantPolicy.requireName(name, path);
                ConfigurationSection enchant = ConfigValues.section(config, path);
                ConfigValues.keys(enchant, "name", "enabled", "max-level", "base-xp", "levels");
                if (enchant.contains("base-xp", true)) {
                    bases.put(name, boundedInteger(enchant.get("base-xp"), path + ".base-xp"));
                }
                ConfigurationSection costs = ConfigValues.section(config, path + ".levels");
                Map<Integer, Integer> explicit = new HashMap<Integer, Integer>();
                if (costs != null) {
                    for (String key : costs.getKeys(false)) {
                        int level;
                        try {
                            level = Integer.parseInt(key);
                        } catch (NumberFormatException failure) {
                            throw new IllegalArgumentException(costs.getCurrentPath() + "." + key + " must be a level 1..255");
                        }
                        if (level < 1 || level > EnchantPolicy.MAX_LEVEL || !key.equals(Integer.toString(level))) {
                            throw new IllegalArgumentException(costs.getCurrentPath() + "." + key + " must be a level 1..255");
                        }
                        explicit.put(level, boundedInteger(costs.get(key), costs.getCurrentPath() + "." + key));
                    }
                }
                levels.put(name, Collections.unmodifiableMap(explicit));
            }
        }
        Prices prices = new Prices(base, maximum, bases, levels);
        for (String name : Enchantments.names()) {
            long previous = 0;
            for (int level = 1; level <= EnchantPolicy.MAX_LEVEL; level++) {
                long value = prices.value(name, level);
                if (value <= previous) {
                    Map<Integer, Integer> explicit = levels.get(name);
                    int offending = explicit != null && explicit.containsKey(level) ? level : level - 1;
                    throw new IllegalArgumentException("enchantments." + name + ".levels." + offending +
                            " must produce strictly increasing cumulative values (level " + level + ")");
                }
                previous = value;
            }
        }
        return prices;
    }

    private static int boundedInteger(Object value, String key) {
        return ConfigValues.integer(value, key, 1, HARD_MAX_COST);
    }

    public int cost(int level) {
        return checked(quote(null, level));
    }

    public int cost(Enchantment enchantment, int level) {
        return checked(quote(enchantment, level));
    }

    public int checked(long cost) {
        if (!accepts(cost)) {
            throw new IllegalArgumentException("This choice exceeds max-cost");
        }
        return (int) cost;
    }

    public boolean accepts(long cost) {
        return cost >= 1 && cost <= maximum;
    }

    public boolean offers(int level) {
        return offers(null, level);
    }

    public boolean offers(Enchantment enchantment, int level) {
        long quote = quote(enchantment, level);
        return quote >= 1 && quote <= maximum;
    }

    public int upgradeCost(Enchantment enchantment, int current, int target) {
        return checked(upgradeQuote(enchantment, current, target));
    }

    public boolean offersUpgrade(Enchantment enchantment, int current, int target) {
        long quote = upgradeQuote(enchantment, current, target);
        return quote > 0 && quote <= maximum;
    }

    long upgradeQuote(Enchantment enchantment, int current, int target) {
        if (current < 0 || current > EnchantPolicy.MAX_LEVEL ||
                target < 1 || target > EnchantPolicy.MAX_LEVEL || target <= current) {
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
        return Math.multiplyExact(Math.multiplyExact((long) bases.getOrDefault(name, base), level), level);
    }
}
