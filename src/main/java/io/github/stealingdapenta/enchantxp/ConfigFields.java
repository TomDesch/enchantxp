package io.github.stealingdapenta.enchantxp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;

/** Describes existing settings without expanding an operator's sparse document. */
final class ConfigFields {
    enum Kind { BOOLEAN, INTEGER, DECIMAL, TEXT, MATERIAL, SOUND, LINES }

    static final class Field {
        final String path;
        final String name;
        final String help;
        final Kind kind;
        final double minimum;
        final double maximum;

        Field(String path, String name, String help, Kind kind, double minimum, double maximum) {
            this.path = path;
            this.name = name;
            this.help = help;
            this.kind = kind;
            this.minimum = minimum;
            this.maximum = maximum;
        }

        Object parse(String input) {
            switch (kind) {
                case INTEGER:
                    try {
                        int number = Integer.parseInt(input);
                        if (number >= minimum && number <= maximum) {
                            return number;
                        }
                    } catch (NumberFormatException ignored) {
                        // Use the same short explanation for bad digits and out-of-range numbers.
                    }
                    throw new IllegalArgumentException("Enter a whole number from " + (int) minimum + " to " + (int) maximum + ".");
                case DECIMAL:
                    try {
                        double number = Double.parseDouble(input);
                        if (Double.isFinite(number) && number >= minimum && number <= maximum) {
                            return number;
                        }
                    } catch (NumberFormatException ignored) {
                        // Report a useful range rather than a parser exception.
                    }
                    throw new IllegalArgumentException("Enter a number from " + minimum + " to " + maximum + ".");
                case MATERIAL:
                    return ConfigValues.material(input.toUpperCase(Locale.ROOT), name).name();
                case SOUND:
                    String sound = input.toUpperCase(Locale.ROOT);
                    if (!sound.isEmpty()) {
                        try {
                            Sound.valueOf(sound);
                        } catch (IllegalArgumentException failure) {
                            throw new IllegalArgumentException("Use a Minecraft 1.12.2 sound name, or <blank> for silence.");
                        }
                    }
                    return sound;
                default:
                    return input;
            }
        }
    }

    private final YamlConfiguration defaults = ConfigValues.defaults();

    List<Field> general() {
        List<Field> fields = new ArrayList<Field>();
        fields.add(integer("base-xp", "Starting price", "Higher levels cost more: price x level squared.", 1, 1000000));
        fields.add(integer("max-cost", "Purchase limit", "Hide upgrades that cost more than this much XP.", 1, 1000000));
        fields.add(integer("ui.rows", "Menu height", "3 to 6 rows. More rows show more choices.", 3, 6));
        return fields;
    }

    List<Field> shelves() {
        List<Field> fields = new ArrayList<Field>();
        fields.add(bool("bookshelf-pricing.enabled", "Bookshelf prices", "Nearby bookshelves make enchanting cheaper."));
        fields.add(integer("bookshelf-pricing.zero-shelf-penalty-percent", "No-shelf surcharge (%)", "Extra cost when no bookshelves are nearby.", 0, 1000000));
        fields.add(integer("bookshelf-pricing.per-bookshelf-reduction-percent", "Discount per shelf (%)", "Removes this many surcharge points per shelf.", 0, 1000000));
        fields.add(integer("bookshelf-pricing.max-bookshelves", "Shelf limit", "Count at most this many valid bookshelves.", 1, 15));
        return fields;
    }

    List<Field> enchantment(String name) {
        String path = "enchantments." + name + ".";
        List<Field> fields = new ArrayList<Field>();
        fields.add(text(path + "name", "Display name", "The name players see. & color codes work here."));
        fields.add(bool(path + "enabled", "Allow purchases", "Off blocks this enchantment on every item."));
        fields.add(integer(path + "base-xp", "Starting price", "Uses the global starting price unless overridden.", 1, 1000000));
        fields.add(integer(path + "max-level", "Highest level", "0 blocks purchases. Reset to use the vanilla cap.", 0, 255));
        return fields;
    }

    List<Field> sounds(String category) {
        String path = "sounds." + category + ".";
        List<Field> fields = new ArrayList<Field>();
        fields.add(new Field(path + "sound", "Sound", "A 1.12.2 sound name. <blank> turns this sound off.", Kind.SOUND, 0, 0));
        fields.add(new Field(path + "volume", "Volume", "0 is silent; 1 is normal volume.", Kind.DECIMAL, 0, 4));
        fields.add(new Field(path + "pitch", "Pitch", "1 is normal pitch; lower sounds deeper.", Kind.DECIMAL, 0, 2));
        return fields;
    }

    List<Field> leaves(String section) {
        List<Field> fields = new ArrayList<Field>();
        ConfigurationSection parent = defaults.getConfigurationSection(section);
        for (String key : parent.getKeys(false)) {
            if (parent.isConfigurationSection(key) || (section.equals("ui") && key.equals("rows"))) {
                continue;
            }
            String path = section + "." + key;
            Object value = defaults.get(path);
            if (key.equals("material")) {
                fields.add(new Field(path, "Item", "Use a legacy 1.12.2 item name.", Kind.MATERIAL, 0, 0));
            } else if (key.equals("data")) {
                fields.add(integer(path, "Item data", "Legacy item variant or durability (0 to 32767).", 0, Short.MAX_VALUE));
            } else if (value instanceof List) {
                fields.add(new Field(path, displayName(path, key), "Edit, add or remove lines. Blank lines are allowed.", Kind.LINES, 0, 0));
            } else {
                fields.add(text(path, displayName(path, key), help(path)));
            }
        }
        return fields;
    }

    Object effective(YamlConfiguration document, String path) {
        if (document.contains(path, true)) {
            return document.get(path);
        }
        if (path.startsWith("enchantments.")) {
            if (path.contains(".levels.")) {
                String[] parts = path.split("\\.");
                Object base = document.get("enchantments." + parts[1] + ".base-xp", document.get("base-xp"));
                int level = Integer.parseInt(parts[3]);
                return ((Number) base).longValue() * level * level;
            }
            if (path.endsWith(".base-xp")) {
                return document.get("base-xp");
            }
            if (path.endsWith(".enabled")) {
                return true;
            }
            if (path.endsWith(".max-level")) {
                Enchantment enchantment = Enchantment.getByName(path.split("\\.")[1]);
                return enchantment == null ? 1 : enchantment.getMaxLevel();
            }
        }
        Object value = defaults.get(path);
        if (value instanceof List) {
            String parent = path.substring(0, path.lastIndexOf('.'));
            if (document.isConfigurationSection(parent)) {
                return Collections.emptyList();
            }
        }
        return value;
    }

    String friendly(YamlConfiguration document, String name) {
        Object value = effective(document, "enchantments." + name + ".name");
        return value instanceof String ? (String) value : label(name.toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    static Field integer(String path, String name, String help, int min, int max) {
        return new Field(path, name, help, Kind.INTEGER, min, max);
    }

    static Field bool(String path, String name, String help) {
        return new Field(path, name, help, Kind.BOOLEAN, 0, 0);
    }

    static Field text(String path, String name, String help) {
        return new Field(path, name, help, Kind.TEXT, 0, 0);
    }

    static String label(String key) {
        String words = key.replace('-', ' ').replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String displayName(String path, String key) {
        if (path.startsWith("messages.")) {
            return messageCopy(key)[0];
        }
        if (path.startsWith("ui.") && !path.startsWith("ui.icons.")) {
            if (key.startsWith("type-")) {
                return label(key.replace("type-", "enchantment-choice-"));
            }
            if (key.startsWith("option-")) {
                return label(key.replace("option-", "level-choice-"));
            }
            if (key.startsWith("review-")) {
                return label(key.replace("review-", "confirmation-choice-"));
            }
            if (key.startsWith("selection-")) {
                return label(key.replace("selection-", "selected-item-"));
            }
        }
        return label(key);
    }

    private static String help(String path) {
        if (path.startsWith("messages.")) {
            return messageCopy(path.substring("messages.".length()))[1];
        }
        if (path.startsWith("ui.titles.")) {
            return "Menu heading. & colors work; limited to 32 characters.";
        }
        if (path.endsWith("unlimited-balance")) {
            return "Shown instead of an XP balance in Creative mode.";
        }
        return "Text players see in the menu. & color codes work here.";
    }

    private static String[] messageCopy(String key) {
        switch (key) {
            case "prefix": return copy("Chat prefix", "Added once before every nonblank chat message.");
            case "eligibility": return copy("Cannot use the table", "When a player lacks access or uses the wrong game mode.");
            case "context": return copy("Enchanting cancelled", "When the selected item, table or open menu changes.");
            case "unsupported": return copy("Item cannot be enchanted", "When a player chooses an unsupported item or stack.");
            case "no-space": return copy("Inventory full", "When an enchanted book needs an empty inventory slot.");
            case "no-upgrades": return copy("No more upgrades", "When the item has no upgrades within its allowed limits.");
            case "unavailable": return copy("Upgrades cost too much", "When all remaining upgrades exceed the purchase limit.");
            case "insufficient": return copy("Not enough XP", "When a player cannot afford the selected upgrade.");
            case "invalid-balance": return copy("XP balance unavailable", "When the player's XP balance cannot be read safely.");
            case "stale": return copy("Purchase cancelled", "When the item, balance or session changes before payment.");
            case "success": return copy("Enchantment complete", "When a purchase finishes successfully.");
            case "quote-changed": return copy("Review a changed purchase", "When price, game mode or access changes during confirmation.");
            case "failure": return copy("Enchanting error", "When a purchase fails and a server operator should check it.");
            case "reload-denied": return copy("Reload access denied", "When the sender lacks permission to reload settings.");
            case "reload-usage": return copy("Command help", "When the command has missing or incorrect arguments.");
            case "reload-busy": return copy("Settings busy", "When another settings read, save or reload is still running.");
            case "reload-stopped": return copy("Plugin stopped", "When a settings command arrives after EnchantXP has stopped.");
            case "reload-success": return copy("Reload complete", "When config.yml has been read and applied successfully.");
            case "reload-failure": return copy("Reload failed", "When config.yml cannot be read, validated or applied.");
            case "config-denied": return copy("Editor access denied", "When the sender lacks permission to edit settings.");
            case "config-player-only": return copy("Open the editor in-game", "When the console tries to open the player settings menu.");
            case "config-prompt": return copy("Settings input prompt", "When the editor asks for a value through private chat.");
            case "config-error": return copy("Settings problem", "When input or a draft cannot be accepted.");
            case "config-saving": return copy("Saving settings", "After Apply is accepted, while the file is being saved.");
            case "config-saved": return copy("Settings saved", "After the file is saved and the new settings are applied.");
            case "config-saved-not-applied": return copy("Saved, waiting for reload", "When the file was saved but its settings could not be applied.");
            case "config-discarded": return copy("Draft discarded", "When an unsaved settings draft is closed or discarded.");
            case "config-expired": return copy("Draft expired", "When the draft's 15-minute editing window ends.");
            default: return copy(label(key), "Chat feedback for this settings action.");
        }
    }

    private static String[] copy(String name, String help) {
        return new String[]{name, help};
    }
}
