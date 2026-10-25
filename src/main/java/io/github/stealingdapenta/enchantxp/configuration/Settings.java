package io.github.stealingdapenta.enchantxp.configuration;

import io.github.stealingdapenta.enchantxp.configuration.values.ConfigValues;
import io.github.stealingdapenta.enchantxp.enchanting.BookshelfPricing;
import io.github.stealingdapenta.enchantxp.enchanting.EnchantPermissions;
import io.github.stealingdapenta.enchantxp.enchanting.EnchantPolicy;
import io.github.stealingdapenta.enchantxp.enchanting.Prices;
import io.github.stealingdapenta.enchantxp.presentation.MenuSounds;
import io.github.stealingdapenta.enchantxp.presentation.MenuText;
import org.bukkit.configuration.ConfigurationSection;

public final class Settings {
    private static final int CONFIG_VERSION = 1;
    private final Prices prices;
    private final EnchantPolicy policy;
    private final MenuText text;
    private final int rows;
    private final BookshelfPricing shelves;
    private final EnchantPermissions permissions;
    private final MenuSounds sounds;

    private Settings(Prices prices, EnchantPolicy policy, MenuText text, int rows,
            BookshelfPricing shelves, EnchantPermissions permissions, MenuSounds sounds) {
        this.prices = prices;
        this.policy = policy;
        this.text = text;
        this.rows = rows;
        this.shelves = shelves;
        this.permissions = permissions;
        this.sounds = sounds;
    }

    public static Settings read(ConfigurationSection config) {
        if (!(config.get("config-version") instanceof Integer) ||
                ((Integer) config.get("config-version")) != CONFIG_VERSION) {
            throw new IllegalArgumentException("config-version must be the integer " + CONFIG_VERSION +
                    ". Preserve your file and follow the README schema upgrade policy before retrying.");
        }
        Prices prices = Prices.read(config);
        EnchantPolicy policy = EnchantPolicy.read(config);
        int rows = ConfigValues.integer(ConfigValues.value(config, ConfigValues.defaults(), "ui.rows"),
                "ui.rows", 3, 6);
        return new Settings(prices, policy, MenuText.read(config), rows,
                BookshelfPricing.read(config), EnchantPermissions.read(config), MenuSounds.read(config));
    }

    public Prices prices() { return prices; }
    public EnchantPolicy policy() { return policy; }
    public MenuText text() { return text; }
    public int rows() { return rows; }
    public BookshelfPricing shelves() { return shelves; }
    public EnchantPermissions permissions() { return permissions; }
    public MenuSounds sounds() { return sounds; }
}
