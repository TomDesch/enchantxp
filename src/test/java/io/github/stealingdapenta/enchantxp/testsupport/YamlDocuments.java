package io.github.stealingdapenta.enchantxp.testsupport;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

public final class YamlDocuments {
    private YamlDocuments() {
    }

    public static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(text);
        // Sparse fixtures target the current schema; schema-error tests explicitly remove/change it.
        if (!config.contains("config-version", true)) {
            config.set("config-version", 1);
        }
        return config;
    }
}
