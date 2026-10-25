package io.github.stealingdapenta.enchantxp.enchanting;

import io.github.stealingdapenta.enchantxp.configuration.values.ConfigValues;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;

/** Immutable purchase limits; disabling purchases never strips existing vanilla enchants. */
public final class EnchantPolicy {
    public static final int MAX_LEVEL = 255;
    private final Map<String, Integer> limits;
    private final Map<String, Boolean> enabled;
    private final Map<Material, Map<String, Integer>> items;

    private EnchantPolicy(Map<String, Integer> limits, Map<String, Boolean> enabled,
            Map<Material, Map<String, Integer>> items) {
        this.limits = Collections.unmodifiableMap(new HashMap<String, Integer>(limits));
        this.enabled = Collections.unmodifiableMap(new HashMap<String, Boolean>(enabled));
        this.items = Collections.unmodifiableMap(new HashMap<Material, Map<String, Integer>>(items));
    }

    public static EnchantPolicy vanilla() {
        return new EnchantPolicy(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
    }

    public static EnchantPolicy read(ConfigurationSection config) {
        Map<String, Integer> limits = new HashMap<String, Integer>();
        Map<String, Boolean> enabled = new HashMap<String, Boolean>();
        ConfigurationSection enchants = ConfigValues.section(config, "enchantments");
        if (enchants != null) {
            for (String name : enchants.getKeys(false)) {
                requireName(name, "enchantments." + name);
                ConfigurationSection enchant = ConfigValues.section(config, "enchantments." + name);
                ConfigValues.keys(enchant, "name", "enabled", "max-level", "base-xp", "levels");
                if (enchant.contains("enabled", true)) {
                    Object value = enchant.get("enabled");
                    if (!(value instanceof Boolean)) {
                        throw new IllegalArgumentException(enchant.getCurrentPath() + ".enabled must be boolean");
                    }
                    enabled.put(name, (Boolean) value);
                }
                if (enchant.contains("max-level", true)) {
                    limits.put(name, cap(enchant.get("max-level"), name, enchant.getCurrentPath() + ".max-level"));
                }
            }
        }
        Map<Material, Map<String, Integer>> items = new HashMap<Material, Map<String, Integer>>();
        ConfigurationSection configuredItems = ConfigValues.section(config, "items");
        if (configuredItems != null) {
            for (String materialName : configuredItems.getKeys(false)) {
                String path = "items." + materialName;
                Material material = ConfigValues.material(materialName, path);
                ConfigurationSection item = ConfigValues.section(config, path);
                ConfigValues.keys(item, "max-levels");
                ConfigurationSection caps = ConfigValues.section(config, path + ".max-levels");
                Map<String, Integer> values = new HashMap<String, Integer>();
                if (caps != null) {
                    for (String name : caps.getKeys(false)) {
                        requireName(name, caps.getCurrentPath() + "." + name);
                        values.put(name, cap(caps.get(name), name, caps.getCurrentPath() + "." + name));
                    }
                }
                items.put(material, Collections.unmodifiableMap(values));
            }
        }
        return new EnchantPolicy(limits, enabled, items);
    }

    public int maximum(Material material, Enchantment enchantment) {
        if (!enabled.getOrDefault(enchantment.getName(), true)) {
            return 0;
        }

        // A plain-book purchase always publishes an enchanted book, so use its effective limits.
        if (material == Material.BOOK) {
            material = Material.ENCHANTED_BOOK;
        }
        Map<String, Integer> overrides = items.get(material);
        if (overrides != null && overrides.containsKey(enchantment.getName())) {
            return overrides.get(enchantment.getName());
        }
        if (material == Material.ENCHANTED_BOOK) {
            Map<String, Integer> books = items.get(Material.BOOK);
            if (books != null && books.containsKey(enchantment.getName())) {
                return books.get(enchantment.getName());
            }
        }
        return limits.getOrDefault(enchantment.getName(), enchantment.getMaxLevel());
    }

    int registryMaximum(Enchantment enchantment) {
        int maximum = maximum(Material.ENCHANTED_BOOK, enchantment);
        maximum = Math.max(maximum, maximum(Material.AIR, enchantment));
        for (Material material : items.keySet()) {
            maximum = Math.max(maximum, maximum(material, enchantment));
        }
        return maximum;
    }

    static void requireName(String name, String path) {
        if (!Enchantments.names().contains(name)) {
            throw new IllegalArgumentException(path + " is not a vanilla 1.12.2 enchantment name");
        }
    }

    private static int cap(Object value, String name, String path) {
        int cap = ConfigValues.integer(value, path, 0, MAX_LEVEL);
        Enchantment enchantment = Enchantment.getByName(name);
        int start = enchantment == null ? 1 : enchantment.getStartLevel();
        if (cap != 0 && cap < start) {
            throw new IllegalArgumentException(path + " must be 0 or at least " + start);
        }
        return cap;
    }
}
