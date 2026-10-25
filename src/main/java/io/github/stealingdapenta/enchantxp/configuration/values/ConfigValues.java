package io.github.stealingdapenta.enchantxp.configuration.values;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

public final class ConfigValues {

    private ConfigValues() {
    }

    public static YamlConfiguration defaults() {
        YamlConfiguration defaults = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(ConfigValues.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)) {
            defaults.load(reader);
        } catch (IOException | InvalidConfigurationException failure) {
            throw new IllegalStateException("Cannot read packaged config.yml", failure);
        }
        return defaults;
    }

    public static Object value(ConfigurationSection config, ConfigurationSection defaults, String path) {
        return config.contains(path, true) ? config.get(path) : defaults.get(path);
    }

    public static int integer(Object value, String path, int minimum, int maximum) {
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new IllegalArgumentException(path + " must be a whole integer");
        }

        long number = ((Number) value).longValue();
        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException(path + " must be between " + minimum + " and " + maximum);
        }
        return (int) number;
    }

    public static boolean bool(ConfigurationSection config, ConfigurationSection defaults, String path) {
        Object value = value(config, defaults, path);
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(path + " must be boolean");
        }
        return (Boolean) value;
    }

    public static ConfigurationSection section(ConfigurationSection config, String path) {
        if (!config.contains(path, true)) {
            return null;
        }
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            throw new IllegalArgumentException(path + " must be a section");
        }
        return section;
    }

    public static void keys(ConfigurationSection section, String... allowed) {
        if (section == null) {
            return;
        }
        Set<String> names = new HashSet<>(Arrays.asList(allowed));
        for (String key : section.getKeys(false)) {
            if (!names.contains(key)) {
                throw new IllegalArgumentException(section.getCurrentPath() + "." + key + " is unknown");
            }
        }
    }

    public static ConfigurationSection requiredSection(ConfigurationSection config, String path) {
        ConfigurationSection section = section(config, path);
        if (section == null) {
            throw new IllegalArgumentException(path + " must be a section");
        }
        return section;
    }

    public static Material material(Object value, String path) {
        if (value instanceof String) {
            try {
                Material material = Material.valueOf((String) value);
                if (material != Material.AIR && material.isItem()) {
                    return material;
                }
            } catch (IllegalArgumentException ignored) {
                // Report the configuration path rather than an enum implementation detail.
            }
        }
        throw new IllegalArgumentException(path + " must name a non-AIR legacy item material");
    }
}
