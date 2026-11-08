package io.github.stealingdapenta.enchantxp.configuration;

import io.github.stealingdapenta.enchantxp.configuration.values.ConfigValues;
import io.github.stealingdapenta.enchantxp.configuration.values.NumericRule;
import io.github.stealingdapenta.enchantxp.enchanting.BookshelfPricing;
import io.github.stealingdapenta.enchantxp.enchanting.EnchantPermissions;
import io.github.stealingdapenta.enchantxp.enchanting.EnchantPolicy;
import io.github.stealingdapenta.enchantxp.enchanting.Prices;
import io.github.stealingdapenta.enchantxp.presentation.MenuSounds;
import io.github.stealingdapenta.enchantxp.presentation.MenuText;
import org.bukkit.configuration.ConfigurationSection;

/**
 * One validated immutable configuration snapshot; reading never rewrites the operator's document.
 */
public final class Settings {

    private static final int CONFIG_VERSION = 1;
    private final Prices prices;
    private final EnchantPolicy policy;
    private final MenuText text;
    private final int rows;
    private final BookshelfPricing shelves;
    private final EnchantPermissions permissions;
    private final MenuSounds sounds;

    private Settings(Prices prices, EnchantPolicy policy, MenuText text, int rows, BookshelfPricing shelves, EnchantPermissions permissions, MenuSounds sounds) {
        this.prices = prices;
        this.policy = policy;
        this.text = text;
        this.rows = rows;
        this.shelves = shelves;
        this.permissions = permissions;
        this.sounds = sounds;
    }

    /**
     * Reads schema 1 with packaged fallbacks for optional settings, without publishing Bukkit permissions or menus.
     *
     * @throws IllegalArgumentException for an unsupported schema or invalid setting, with its configuration path
     */
    public static Settings read(ConfigurationSection config) {
        if (!(config.get("config-version") instanceof Integer) || ((Integer) config.get("config-version")) != CONFIG_VERSION) {
            throw new IllegalArgumentException("config-version must be the integer " + CONFIG_VERSION + ". Preserve your file and follow the README schema upgrade policy before retrying.");
        }
        Prices prices = Prices.read(config);
        EnchantPolicy policy = EnchantPolicy.read(config);
        int rows = NumericRule.ROWS.readInteger(ConfigValues.value(config, ConfigValues.defaults(), "ui.rows"), "ui.rows");
        return new Settings(prices, policy, MenuText.read(config), rows, BookshelfPricing.read(config), EnchantPermissions.read(config), MenuSounds.read(config));
    }

    /** Returns cumulative raw-XP valuations and charge limits for this snapshot. */
    public Prices prices() {
        return prices;
    }

    /** Returns purchase caps and enabled flags; existing item metadata is not mutated. */
    public EnchantPolicy policy() {
        return policy;
    }

    /** Returns validated templates and shared lore formatting for this snapshot. */
    public MenuText text() {
        return text;
    }

    /** Returns menu height in chest rows, inclusively between 3 and 6. */
    public int rows() {
        return rows;
    }

    /** Returns the bounded legacy shelf scan and price-adjustment rules. */
    public BookshelfPricing shelves() {
        return shelves;
    }

    /** Returns exact target-level permission policy, separate from table admission. */
    public EnchantPermissions permissions() {
        return permissions;
    }

    /** Returns private player feedback sounds, including explicitly silent effects. */
    public MenuSounds sounds() {
        return sounds;
    }
}
