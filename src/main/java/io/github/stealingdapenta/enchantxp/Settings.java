package io.github.stealingdapenta.enchantxp;

import org.bukkit.configuration.ConfigurationSection;

final class Settings {
    final Prices prices;
    final EnchantPolicy policy;
    final MenuText text;
    final MenuLayout layout;
    final BookshelfPricing shelves;
    final EnchantPermissions permissions;
    final MenuSounds sounds;

    private Settings(Prices prices, EnchantPolicy policy, MenuText text, MenuLayout layout,
            BookshelfPricing shelves, EnchantPermissions permissions, MenuSounds sounds) {
        this.prices = prices;
        this.policy = policy;
        this.text = text;
        this.layout = layout;
        this.shelves = shelves;
        this.permissions = permissions;
        this.sounds = sounds;
    }

    static Settings read(ConfigurationSection config) {
        Prices prices = Prices.read(config);
        EnchantPolicy policy = EnchantPolicy.read(config);
        int rows = ConfigValues.integer(ConfigValues.value(config, ConfigValues.defaults(), "ui.rows"),
                "ui.rows", 3, 6);
        return new Settings(prices, policy, MenuText.read(config), new MenuLayout(rows),
                BookshelfPricing.read(config), EnchantPermissions.read(config), MenuSounds.read(config));
    }

    static Settings withPrices(Prices prices) {
        return new Settings(prices, EnchantPolicy.vanilla(), MenuText.read(ConfigValues.defaults()), new MenuLayout(6),
                BookshelfPricing.disabled(), EnchantPermissions.disabled(), MenuSounds.read(ConfigValues.defaults()));
    }
}
