package io.github.stealingdapenta.enchantxp.administration;

import io.github.stealingdapenta.enchantxp.administration.ConfigEditor.Draft;
import io.github.stealingdapenta.enchantxp.administration.ConfigEditor.Entry;
import io.github.stealingdapenta.enchantxp.configuration.values.ConfigValues;
import io.github.stealingdapenta.enchantxp.configuration.values.NumericRule;
import io.github.stealingdapenta.enchantxp.enchanting.EnchantPolicy;
import io.github.stealingdapenta.enchantxp.enchanting.Enchantments;
import io.github.stealingdapenta.enchantxp.presentation.LoreStyle;
import io.github.stealingdapenta.enchantxp.presentation.MenuText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Page definitions and draft edits; opening, prompts and save ownership remain with the editor.
 */
final class EditorPages {

    private static final String ENCHANTMENT_PATH = "enchantments.";
    private static final String ITEM_PATH = "items.";
    private static final int MAX_LORE_LINES = 256;
    private static final int MAX_PREVIEW_LENGTH = 72;
    private static final String ELLIPSIS = "...";
    private final ConfigEditor editor;
    private final ConfigFields fields = new ConfigFields();

    EditorPages(ConfigEditor editor) {
        this.editor = editor;
    }

    List<Entry> entries(Draft draft) {
        List<Entry> result = new ArrayList<>();
        generalEntries(result, draft);
        enchantmentEntries(result, draft);
        itemEntries(result, draft);
        appearanceEntries(result, draft);
        if (draft.alphabetical.contains(draft.route) && sortable(draft, result)) {
            result.sort((first, second) -> String.CASE_INSENSITIVE_ORDER.compare(sortName(first), sortName(second)));
        }
        return result;
    }

    boolean sortable(Draft draft, List<Entry> entries) {
        String route = draft.route;
        int count = entries.size();
        if (route.equals(EditorRoutes.ITEMS) || route.startsWith(EditorRoutes.ITEM)) {
            count--;
        } else if (!route.equals(EditorRoutes.ENCHANTS) && !route.equals(EditorRoutes.MESSAGES) && !route.startsWith(EditorRoutes.PICK)) {
            return false;
        }
        return count > 1;
    }

    private String sortName(Entry entry) {
        return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', entry.name));
    }

    private void generalEntries(List<Entry> result, Draft draft) {
        String route = draft.route;
        if (route.equals(EditorRoutes.HOME)) {
            category(result, draft, "General and prices", Material.EXP_BOTTLE, "Set XP prices and menu size.", EditorRoutes.GENERAL);
            category(result, draft, "Bookshelves", Material.BOOKSHELF, "Choose how shelves affect prices.", EditorRoutes.SHELVES);
            category(result, draft, "Permissions", Material.TRIPWIRE_HOOK, "Control access to enchantment levels.", EditorRoutes.PERMISSIONS);
            category(result, draft, "Sounds", Material.NOTE_BLOCK, "Choose click, success and error sounds.", EditorRoutes.SOUNDS);
            category(result, draft, "Enchantments", Material.ENCHANTED_BOOK, "Names, prices, caps and level overrides.", EditorRoutes.ENCHANTS);
            category(result, draft, "Item limits", Material.DIAMOND_SWORD,
                     "Set the maximum enchantment level per item type, not its XP price. Example: Diamond Sword, then Sharpness, then 10. 0 disables that option. Reset inherits the enchantment cap.", EditorRoutes.ITEMS);
            category(result, draft, "Menu appearance", Material.PAINTING, "Headings, button items and lore.", EditorRoutes.UI);
            category(result, draft, "Chat messages", Material.PAPER, "Change all player and operator feedback.", EditorRoutes.MESSAGES);
        } else if (route.equals(EditorRoutes.GENERAL)) {
            addFields(result, draft, fields.general());
        } else if (route.equals(EditorRoutes.SHELVES)) {
            addFields(result, draft, fields.shelves());
        } else if (route.equals(EditorRoutes.PERMISSIONS)) {
            addFields(result, draft, Collections.singletonList(ConfigFields.bool("permissions.enabled", "Check level permissions",
                                                                                 "Off: enchantxp.use grants access subject to item and price rules. On: check the resolved exact target node, e.g. enchantxp.enchant.fortune.70. Parents enchantxp.enchant.fortune.* and enchantxp.enchant.* grant registered children. Inherited permissions and explicit negatives are resolved by Bukkit or your permissions plugin. Exact nodes default to op; ops can still be denied.")));
        } else if (route.equals(EditorRoutes.SOUNDS)) {
            addFields(result, draft, Collections.singletonList(ConfigFields.bool("sounds.enabled", "Play sounds", "Private feedback for the player using the menu.")));
            for (String category : Arrays.asList("click", "success", "insufficient")) {
                category(result, draft, soundLabel(category), Material.NOTE_BLOCK, "Sound, volume and pitch.", EditorRoutes.SOUND + category);
            }
        } else if (route.startsWith(EditorRoutes.SOUND)) {
            addFields(result, draft, fields.sounds(route.substring(EditorRoutes.SOUND.length())));
        }
    }

    private static String soundLabel(String category) {
        switch (category) {
            case "success":
                return "Enchantment complete";
            case "insufficient":
                return "Not enough XP";
            default:
                return "Click";
        }
    }

    private void enchantmentEntries(List<Entry> result, Draft draft) {
        String route = draft.route;
        if (route.equals(EditorRoutes.ENCHANTS) || route.startsWith(EditorRoutes.PICK)) {
            namedEnchantments(result, draft);
        } else if (route.startsWith(EditorRoutes.ENCHANT)) {
            String name = route.substring(EditorRoutes.ENCHANT.length());
            addFields(result, draft, fields.enchantment(name));
            category(result, draft, "Level prices", Material.EXP_BOTTLE,
                     "Optional cumulative totals, not repeated charges. With base 100, level 2 is 400 and level 3 is 900. Override level 3 to 700: upgrading 2 to 3 costs 300 before shelves, not 700. Unspecified levels use base x level squared.",
                     EditorRoutes.LEVELS + name);
        } else if (route.startsWith(EditorRoutes.LEVELS)) {
            levelOverrides(result, draft, route.substring(EditorRoutes.LEVELS.length()));
        } else if (route.startsWith(EditorRoutes.LEVEL)) {
            String[] parts = route.split(":");
            addField(result, draft, ConfigFields.integer(ENCHANTMENT_PATH + parts[1] + ".levels." + parts[2], "Level " + parts[2] + " total XP", "Set the cumulative total. Other levels still use the starting price.", NumericRule.PRICE));
        }
    }

    private void namedEnchantments(List<Entry> result, Draft draft) {
        String material = draft.route.equals(EditorRoutes.ENCHANTS) ? null : draft.route.substring(EditorRoutes.PICK.length());
        for (String name : Enchantments.names()) {
            List<String> help = new ArrayList<>();
            if (material == null) {
                help.add("Set price, level cap and display name.");
                help.add(LoreStyle.action("Left-click to open."));
            } else {
                help.add(LoreStyle.info("Item", ConfigFields.label(material)));
                help.add(LoreStyle.number("Current maximum level", inheritedCap(draft, material, name)));
                help.add("This item cap replaces the enchantment cap; it does not change XP prices.");
                help.add(LoreStyle.action("Left-click to set its maximum level."));
            }
            result.add(new Entry(Material.ENCHANTED_BOOK, fields.friendly(draft.document, name), help, () -> chooseEnchantment(draft, material, name), null));
        }
    }

    private void chooseEnchantment(Draft draft, String material, String name) {
        if (material == null) {
            editor.navigate(draft, EditorRoutes.ENCHANT + name);
            return;
        }
        String path = ITEM_PATH + material + ".max-levels." + name;
        ConfigFields.Field field = capField(path, ConfigFields.label(material) + " - " + fields.friendly(draft.document, name) + " maximum level");
        editor.prompt(draft, field.prompt(), value -> {
            draft.document.set(path, field.parse(value));
            draft.route = EditorRoutes.ITEM + material;
        });
    }

    private void levelOverrides(List<Entry> result, Draft draft, String name) {
        String path = ENCHANTMENT_PATH + name + ".levels";
        ConfigurationSection levels = draft.document.getConfigurationSection(path);
        if (levels != null) {
            List<String> keys = new ArrayList<>(levels.getKeys(false));
            keys.sort(Comparator.comparingInt(Integer::parseInt));
            for (String level : keys) {
                addField(result, draft, ConfigFields.integer(path + "." + level, "Level " + level + " total XP", "Cumulative value, not the upgrade charge. Reset inherits the calculated total.", NumericRule.PRICE));
            }
        }
        result.add(new Entry(Material.SLIME_BALL, "Add level price", Arrays.asList("Only the levels you add are overridden.", "Totals must rise at every level when you Apply.", LoreStyle.action("Left-click to choose a level.")),
                             () -> editor.prompt(draft, levelChoice().prompt(), input -> {
                                 int level = (Integer) levelChoice().parse(input);
                                 draft.route = EditorRoutes.LEVEL + name + ":" + level;
                             }), null));
    }

    private void itemEntries(List<Entry> result, Draft draft) {
        String route = draft.route;
        if (route.equals(EditorRoutes.ITEMS)) {
            ConfigurationSection items = draft.document.getConfigurationSection("items");
            if (items != null) {
                for (String material : items.getKeys(false)) {
                    result.add(new Entry(Material.valueOf(material), ConfigFields.label(material),
                                         Arrays.asList("Maximum enchantment levels for this item, not XP prices.", LoreStyle.action("Left-click to choose an enchantment."), LoreStyle.action("Right-click to delete this item's overrides.")),
                                         () -> editor.navigate(draft, EditorRoutes.ITEM + material), () -> {
                        draft.document.set(ITEM_PATH + material, null);
                        editor.show(draft);
                    }));
                }
            }
            result.add(new Entry(Material.SLIME_BALL, "Add item type",
                                 Arrays.asList("Set a maximum enchantment level, not an XP price.", LoreStyle.info("Example", "Diamond Sword -> Sharpness -> 10."), "0 disables; reset inherits the enchantment cap.",
                                               "BOOK covers plain and enchanted books; an ENCHANTED_BOOK cap takes priority.", LoreStyle.action("Left-click to enter an item type.")), () -> editor.prompt(draft,
                                                                                                                                                                                                           "Maximum enchantment level per item, not XP price: enter an item type such as DIAMOND_SWORD, then choose Sharpness and enter 10. 0 disables; reset inherits the enchantment cap. Blank is not allowed.",
                                                                                                                                                                                                           input -> {
                                                                                                                                                                                                               String material = ConfigValues.material(
                                                                                                                                                                                                                                                     input.toUpperCase(Locale.ROOT),
                                                                                                                                                                                                                                                     "Item type")
                                                                                                                                                                                                                                             .name();
                                                                                                                                                                                                               draft.route = EditorRoutes.PICK + material;
                                                                                                                                                                                                           }), null));
        } else if (route.startsWith(EditorRoutes.ITEM)) {
            String material = route.substring(EditorRoutes.ITEM.length());
            String path = ITEM_PATH + material + ".max-levels";
            ConfigurationSection caps = draft.document.getConfigurationSection(path);
            if (caps != null) {
                for (String name : caps.getKeys(false)) {
                    addField(result, draft, capField(path + "." + name, fields.friendly(draft.document, name)));
                }
            }
            category(result, draft, "Add enchantment cap", Material.SLIME_BALL,
                     "Set the maximum enchantment level for " + ConfigFields.label(material) + ". This replaces the enchantment cap, not its XP price. For example, Sharpness -> 10. 0 disables; reset inherits the enchantment cap.",
                     EditorRoutes.PICK + material);
        }
    }

    private void appearanceEntries(List<Entry> result, Draft draft) {
        String route = draft.route;
        if (route.equals(EditorRoutes.UI)) {
            addFields(result, draft, fields.leaves("ui"));
            category(result, draft, "Lore formatting", Material.SIGN, "Shared instruction prefix and role colors for operator and table lore.", EditorRoutes.LORE_STYLE);
            category(result, draft, "Headings", Material.SIGN, "Titles for each enchanting step.", EditorRoutes.TITLES);
            category(result, draft, "Buttons and decorations", Material.ITEM_FRAME, "Item, variant, display name and lore.", EditorRoutes.ICONS);
            result.add(new Entry(Material.BUCKET, "Use default appearance",
                                 Arrays.asList("Remove all menu appearance overrides, including height.", "Restore shipped lore, shared prefix and role colors.", LoreStyle.action("Left-click to restore default appearance.")), () -> {
                draft.document.set("ui", null);
                draft.document.set(LoreStyle.PATH, null);
                editor.show(draft);
            }, null));
        } else if (route.equals(EditorRoutes.LORE_STYLE)) {
            addFields(result, draft, fields.leaves(LoreStyle.PATH));
        } else if (route.equals(EditorRoutes.TITLES)) {
            addFields(result, draft, fields.leaves("ui.titles"));
        } else if (route.equals(EditorRoutes.ICONS)) {
            for (String icon : ConfigValues.defaults()
                                           .getConfigurationSection("ui.icons")
                                           .getKeys(false)) {
                category(result, draft, ConfigFields.label(icon), Material.ITEM_FRAME, "Edit this menu decoration.", EditorRoutes.ICON + icon);
            }
        } else if (route.startsWith(EditorRoutes.ICON)) {
            String path = "ui.icons." + route.substring(EditorRoutes.ICON.length());
            addFields(result, draft, fields.leaves(path));
            result.add(new Entry(Material.BUCKET, "Use default icon", Arrays.asList("Remove this whole icon's overrides.", "Its default item, name and lore will be used.", LoreStyle.action("Left-click to restore the default icon.")), () -> {
                draft.document.set(path, null);
                editor.show(draft);
            }, null));
        } else if (route.equals(EditorRoutes.MESSAGES)) {
            addFields(result, draft, fields.leaves("messages"));
        } else if (route.startsWith(EditorRoutes.LINES)) {
            lineEntries(result, draft, route.substring(EditorRoutes.LINES.length()));
        }
    }

    private void lineEntries(List<Entry> result, Draft draft, String path) {
        List<String> lines = lines(draft, path);
        for (int i = 0; i < lines.size(); i++) {
            final int index = i;
            result.add(new Entry(Material.PAPER, "Line " + (i + 1), Arrays.asList(LoreStyle.info("Text", preview(lines.get(i))), LoreStyle.action("Left-click to edit."), LoreStyle.action("Right-click to delete.")),
                                 () -> editor.prompt(draft, ConfigFields.textPrompt("New text for line " + (index + 1), true), input -> {
                                     List<String> changed = lines(draft, path);
                                     changed.set(index, input);
                                     draft.document.set(path, changed);
                                 }), () -> {
                List<String> changed = lines(draft, path);
                changed.remove(index);
                draft.document.set(path, changed);
                editor.show(draft);
            }));
        }
        result.add(new Entry(Material.SLIME_BALL, "Add line", Arrays.asList("The text <blank> adds a blank line.", LoreStyle.action("Left-click to add a line.")),
                             () -> editor.prompt(draft, ConfigFields.textPrompt("Text for the new line", true), input -> {
                                 List<String> changed = lines(draft, path);
                                 if (changed.size() >= MAX_LORE_LINES) {
                                     throw new IllegalArgumentException("Keep lore to 256 lines or fewer.");
                                 }
                                 changed.add(input);
                                 draft.document.set(path, changed);
                             }), null));
        result.add(new Entry(Material.BUCKET, "Clear all lines", Arrays.asList("Keep an explicit empty list.", LoreStyle.action("Left-click to delete all lines.")), () -> {
            draft.document.set(path, new ArrayList<>());
            editor.show(draft);
        }, null));
    }

    private ConfigFields.Field capField(String path, String name) {
        return ConfigFields.integer(path, name,
                                    "Maximum enchantment level for this item, not XP price. For example, Diamond Sword -> Sharpness -> 10. 0 disables this option. This replaces the enchantment cap. Reset inherits the applicable fallback cap. BOOK covers both book types unless ENCHANTED_BOOK has its own cap. Global disabled status, legality, price and permissions still apply.",
                                    NumericRule.LEVEL_CAP);
    }

    private ConfigFields.Field levelChoice() {
        return ConfigFields.integer(ENCHANTMENT_PATH + "NAME.levels", "Level to override", "70 is valid; " + NumericRule.LEVEL.maximumInteger() + " is the hard bound. A configured max-cost can hide expensive options.", NumericRule.LEVEL);
    }

    private Object inheritedCap(Draft draft, String material, String name) {
        if (Boolean.FALSE.equals(fields.effective(draft.document, ENCHANTMENT_PATH + name + ".enabled"))) {
            return 0;
        }
        boolean book = material.equals("BOOK") || material.equals("ENCHANTED_BOOK");
        String path = ITEM_PATH + (book ? "ENCHANTED_BOOK" : material) + ".max-levels." + name;
        if (draft.document.contains(path, true)) {
            return draft.document.get(path);
        }
        path = "items.BOOK.max-levels." + name;
        if (book && draft.document.contains(path, true)) {
            return draft.document.get(path);
        }
        return fields.effective(draft.document, ENCHANTMENT_PATH + name + ".max-level");
    }

    private void category(List<Entry> entries, Draft draft, String name, Material material, String help, String route) {
        List<String> lore = new ArrayList<>(Arrays.asList(help.split("(?<=\\.)\\s+")));
        lore.add(LoreStyle.action("Left-click to open."));
        entries.add(new Entry(material, name, lore, () -> editor.navigate(draft, route), null));
    }

    private void addFields(List<Entry> entries, Draft draft, List<ConfigFields.Field> values) {
        for (ConfigFields.Field field : values) {
            addField(entries, draft, field);
        }
    }

    private void addField(List<Entry> entries, Draft draft, ConfigFields.Field field) {
        Object value = fields.effective(draft.document, field.path);
        List<String> lore = fieldLore(draft, field, value);
        entries.add(new Entry(fieldMaterial(field.kind), field.name, lore, () -> editField(draft, field), () -> resetField(draft, field), field.kind == ConfigFields.Kind.BOOLEAN && Boolean.TRUE.equals(value)));
    }

    private List<String> fieldLore(Draft draft, ConfigFields.Field field, Object value) {
        boolean explicit = draft.document.contains(field.path, true);
        List<String> lore = new ArrayList<>();
        lore.add(valueLine(field, value));
        lore.add(LoreStyle.info("Source", explicit ? "explicit override." : "inherited fallback."));
        lore.addAll(Arrays.asList(field.help.split("(?<=\\.)\\s+")));
        if (field.path.startsWith("messages.")) {
            lore.add("& colors work here.");
            lore.add(LoreStyle.action("Use <blank> to hide this text."));
        }
        if (field.path.startsWith("ui.") && !draft.document.isConfigurationSection("ui")) {
            lore.add("Adding menu overrides leaves omitted stage lore empty.");
        }
        if (field.kind == ConfigFields.Kind.TEXT || field.kind == ConfigFields.Kind.LINES) {
            addPlaceholderHelp(lore, field.path);
        }
        if (field.kind == ConfigFields.Kind.LINES) {
            lore.add("Omitted lore is empty in a configured section.");
        }
        if (field.path.startsWith(ITEM_PATH)) {
            lore.add("ENCHANTED_BOOK caps win over BOOK caps.");
        }
        lore.add(resetHelp(field));
        lore.add(LoreStyle.action(field.kind == ConfigFields.Kind.BOOLEAN ? "Left-click to toggle." : "Left-click to edit."));
        lore.add(LoreStyle.action("Right-click to reset to default."));
        return lore;
    }

    private static void addPlaceholderHelp(List<String> lore, String path) {
        List<String> tokens = new ArrayList<>(MenuText.placeholders(path));
        Collections.sort(tokens);
        if (!tokens.isEmpty()) {
            lore.add("{label}Placeholders:");
            for (String token : tokens) {
                lore.add("- {" + token + "}");
            }
        }
    }

    private static Material fieldMaterial(ConfigFields.Kind kind) {
        switch (kind) {
            case BOOLEAN:
                return Material.LEVER;
            case INTEGER:
            case DECIMAL:
                return Material.EXP_BOTTLE;
            case SOUND:
                return Material.NOTE_BLOCK;
            default:
                return Material.PAPER;
        }
    }

    private void editField(Draft draft, ConfigFields.Field field) {
        if (field.kind == ConfigFields.Kind.BOOLEAN) {
            draft.document.set(field.path, !Boolean.TRUE.equals(fields.effective(draft.document, field.path)));
            editor.show(draft);
        } else if (field.kind == ConfigFields.Kind.LINES) {
            editor.navigate(draft, EditorRoutes.LINES + field.path);
        } else {
            editor.prompt(draft, field.prompt(), input -> draft.document.set(field.path, field.parse(input)));
        }
    }

    private void resetField(Draft draft, ConfigFields.Field field) {
        // These two required prices have no inherited value.
        if (field.path.equals("base-xp") || field.path.equals("max-cost")) {
            draft.document.set(field.path, ConfigValues.defaults()
                                                       .get(field.path));
        } else if (isUiLore(field)) {
            draft.document.set(field.path, new ArrayList<>(ConfigValues.defaults().getStringList(field.path)));
        } else {
            draft.document.set(field.path, null);
        }
        editor.show(draft);
    }

    private static boolean isUiLore(ConfigFields.Field field) {
        return field.kind == ConfigFields.Kind.LINES && field.path.startsWith("ui.");
    }

    private static String resetHelp(ConfigFields.Field field) {
        if (isUiLore(field)) {
            return "Reset restores a copy of the shipped lore.";
        }
        if (field.path.startsWith("items.ENCHANTED_BOOK.")) {
            return "Reset removes this item override; BOOK then the enchantment or vanilla cap applies.";
        }
        if (field.path.startsWith(ITEM_PATH)) {
            return "Reset removes this item override and inherits the enchantment or vanilla cap.";
        }
        if (field.path.startsWith(ENCHANTMENT_PATH)) {
            if (field.path.contains(".levels.")) {
                return "Reset removes this override and calculates the total from the current starting price.";
            }
            if (field.path.endsWith(".base-xp")) {
                return "Reset removes this override and uses the global starting price.";
            }
            if (field.path.endsWith(".max-level")) {
                return "Reset removes this override and uses the vanilla level cap.";
            }
            if (field.path.endsWith(".enabled")) {
                return "Reset removes this override and enables purchases, subject to the other rules.";
            }
        }
        return "Reset restores the shipped setting.";
    }

    private String valueLine(ConfigFields.Field field, Object value) {
        if (field.kind == ConfigFields.Kind.BOOLEAN) {
            return Boolean.TRUE.equals(value) ? ChatColor.GREEN + "Enabled" : ChatColor.RED + "Disabled";
        }
        if (field.kind == ConfigFields.Kind.INTEGER || field.kind == ConfigFields.Kind.DECIMAL) {
            return LoreStyle.number("Value", field.display(value));
        }
        return LoreStyle.info("Value", preview(value instanceof List ? ((List<?>) value).size() + " lines" : String.valueOf(value)));
    }

    private List<String> lines(Draft draft, String path) {
        List<String> result = new ArrayList<>();
        Object value = fields.effective(draft.document, path);
        if (value instanceof List) {
            for (Object line : (List<?>) value) {
                result.add((String) line);
            }
        }
        return result;
    }

    private static String preview(String text) {
        if (text.isEmpty()) {
            return "(blank)";
        }
        return text.length() > MAX_PREVIEW_LENGTH ? text.substring(0, MAX_PREVIEW_LENGTH - ELLIPSIS.length()) + ELLIPSIS : text;
    }
}
