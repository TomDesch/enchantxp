package io.github.stealingdapenta.enchantxp.enchanting;

import io.github.stealingdapenta.enchantxp.configuration.values.ConfigValues;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;

/**
 * Immutable purchase limits; disabling purchases never strips existing vanilla enchants.
 */
public final class EnchantPolicy {

    public static final int MAX_LEVEL = 255;
    private static final String MAX_LEVEL_KEY = "max-level";
    private static final String ENABLED_KEY = "enabled";
    private final Map<String, Integer> limits;
    private final Map<String, Boolean> enabled;
    private final Map<Material, Map<String, Integer>> items;

    private EnchantPolicy(Map<String, Integer> limits, Map<String, Boolean> enabled, Map<Material, Map<String, Integer>> items) {
        this.limits = Collections.unmodifiableMap(new HashMap<>(limits));
        this.enabled = Collections.unmodifiableMap(new HashMap<>(enabled));
        Map<Material, Map<String, Integer>> itemCopy = new EnumMap<>(Material.class);
        itemCopy.putAll(items);
        this.items = Collections.unmodifiableMap(itemCopy);
    }

    public static EnchantPolicy vanilla() {
        return new EnchantPolicy(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
    }

    public static EnchantPolicy read(ConfigurationSection config) {
        Map<String, Integer> limits = new HashMap<>();
        Map<String, Boolean> enabled = new HashMap<>();
        readEnchantments(config, limits, enabled);
        return new EnchantPolicy(limits, enabled, readItems(config));
    }

    private static void readEnchantments(ConfigurationSection config, Map<String, Integer> limits, Map<String, Boolean> enabled) {
        ConfigurationSection enchants = ConfigValues.section(config, "enchantments");
        if (enchants != null) {
            for (String name : enchants.getKeys(false)) {
                requireName(name, "enchantments." + name);
                ConfigurationSection enchant = ConfigValues.requiredSection(config, "enchantments." + name);
                ConfigValues.keys(enchant, "name", ENABLED_KEY, MAX_LEVEL_KEY, "base-xp", "levels");
                if (enchant.contains(ENABLED_KEY, true)) {
                    Object value = enchant.get(ENABLED_KEY);
                    if (!(value instanceof Boolean)) {
                        throw new IllegalArgumentException(enchant.getCurrentPath() + "." + ENABLED_KEY + " must be boolean");
                    }
                    enabled.put(name, (Boolean) value);
                }
                if (enchant.contains(MAX_LEVEL_KEY, true)) {
                    limits.put(name, cap(enchant.get(MAX_LEVEL_KEY), name, enchant.getCurrentPath() + "." + MAX_LEVEL_KEY));
                }
            }
        }
    }

    private static Map<Material, Map<String, Integer>> readItems(ConfigurationSection config) {
        Map<Material, Map<String, Integer>> items = new EnumMap<>(Material.class);
        ConfigurationSection configuredItems = ConfigValues.section(config, "items");
        if (configuredItems != null) {
            for (String materialName : configuredItems.getKeys(false)) {
                String path = "items." + materialName;
                Material material = ConfigValues.material(materialName, path);
                ConfigurationSection item = ConfigValues.requiredSection(config, path);
                ConfigValues.keys(item, "max-levels");
                ConfigurationSection caps = ConfigValues.section(config, path + ".max-levels");
                Map<String, Integer> values = new HashMap<>();
                if (caps != null) {
                    for (String name : caps.getKeys(false)) {
                        requireName(name, caps.getCurrentPath() + "." + name);
                        values.put(name, cap(caps.get(name), name, caps.getCurrentPath() + "." + name));
                    }
                }
                items.put(material, Collections.unmodifiableMap(values));
            }
        }
        return items;
    }

    public int maximum(Material material, Enchantment enchantment) {
        if (Boolean.FALSE.equals(enabled.get(enchantment.getName()))) {
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
        if (!Enchantments.names()
                         .contains(name)) {
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
