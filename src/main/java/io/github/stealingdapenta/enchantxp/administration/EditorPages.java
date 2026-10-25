package io.github.stealingdapenta.enchantxp.administration;

import io.github.stealingdapenta.enchantxp.administration.ConfigEditor.Draft;
import io.github.stealingdapenta.enchantxp.administration.ConfigEditor.Entry;
import io.github.stealingdapenta.enchantxp.configuration.values.ConfigValues;
import io.github.stealingdapenta.enchantxp.enchanting.Enchantments;
import io.github.stealingdapenta.enchantxp.presentation.MenuText;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/** Page definitions and draft edits; opening, prompts and save ownership remain with the editor. */
final class EditorPages {
    private final ConfigEditor editor;
    private final ConfigFields fields = new ConfigFields();

    EditorPages(ConfigEditor editor) {
        this.editor = editor;
    }

    List<Entry> entries(Draft draft) {
        List<Entry> result = new ArrayList<Entry>();
        generalEntries(result, draft);
        enchantmentEntries(result, draft);
        itemEntries(result, draft);
        appearanceEntries(result, draft);
        return result;
    }

    private void generalEntries(List<Entry> result, Draft draft) {
        String route = draft.route;
        if (route.equals("home")) {
            category(result, draft, "General and prices", Material.EXP_BOTTLE, "Set XP prices and menu size.", "general");
            category(result, draft, "Bookshelves", Material.BOOKSHELF, "Choose how shelves affect prices.", "shelves");
            category(result, draft, "Permissions", Material.TRIPWIRE_HOOK, "Control access to enchantment levels.", "permissions");
            category(result, draft, "Sounds", Material.NOTE_BLOCK, "Choose click, success and error sounds.", "sounds");
            category(result, draft, "Enchantments", Material.ENCHANTED_BOOK, "Names, prices, caps and level overrides.", "enchants");
            category(result, draft, "Item limits", Material.DIAMOND_SWORD, "Override caps for individual item types.", "items");
            category(result, draft, "Menu appearance", Material.PAINTING, "Headings, button items and lore.", "ui");
            category(result, draft, "Chat messages", Material.PAPER, "Change all player and operator feedback.", "messages");
        } else if (route.equals("general")) {
            addFields(result, draft, fields.general());
        } else if (route.equals("shelves")) {
            addFields(result, draft, fields.shelves());
        } else if (route.equals("permissions")) {
            addFields(result, draft, Collections.singletonList(ConfigFields.bool("permissions.enabled", "Check level permissions",
                    "Off: everyone with enchantxp.use can buy. On: check each target level.")));
        } else if (route.equals("sounds")) {
            addFields(result, draft, Collections.singletonList(ConfigFields.bool("sounds.enabled", "Play sounds",
                    "Private feedback for the player using the menu.")));
            for (String category : Arrays.asList("click", "success", "insufficient")) {
                String name = category.equals("success") ? "Enchantment complete" :
                        category.equals("insufficient") ? "Not enough XP" : "Click";
                category(result, draft, name, Material.NOTE_BLOCK, "Sound, volume and pitch.", "sound:" + category);
            }
        } else if (route.startsWith("sound:")) {
            addFields(result, draft, fields.sounds(route.substring(6)));
        }
    }

    private void enchantmentEntries(List<Entry> result, Draft draft) {
        String route = draft.route;
        if (route.equals("enchants") || route.startsWith("pick:")) {
            for (String name : Enchantments.names()) {
                String material = route.equals("enchants") ? null : route.substring(5);
                String cap = material == null ? "Set price, level cap and display name." :
                        "Uses: level " + inheritedCap(draft, material, name) + ". Add a cap to override it.";
                result.add(new Entry(Material.ENCHANTED_BOOK, fields.friendly(draft.document, name),
                        Arrays.asList(cap),
                        () -> {
                            if (material == null) {
                                editor.navigate(draft, "enchant:" + name);
                            } else {
                                String path = "items." + material + ".max-levels." + name;
                                editor.prompt(draft, "Highest level (0 disables, 1..255 sets a cap):", value -> {
                                    draft.document.set(path, capField(path, fields.friendly(draft.document, name)).parse(value));
                                    draft.route = "item:" + material;
                                });
                            }
                        }, null));
            }
        } else if (route.startsWith("enchant:")) {
            String name = route.substring(8);
            addFields(result, draft, fields.enchantment(name));
            category(result, draft, "Level prices", Material.EXP_BOTTLE,
                    "Optional cumulative totals for specific levels.", "levels:" + name);
        } else if (route.startsWith("levels:")) {
            String name = route.substring(7);
            String path = "enchantments." + name + ".levels";
            ConfigurationSection levels = draft.document.getConfigurationSection(path);
            if (levels != null) {
                List<String> keys = new ArrayList<String>(levels.getKeys(false));
                keys.sort((first, second) -> Integer.compare(Integer.parseInt(first), Integer.parseInt(second)));
                for (String level : keys) {
                    addField(result, draft, ConfigFields.integer(path + "." + level, "Level " + level + " total XP",
                            "Cumulative value, not the upgrade charge. Right click removes this override.", 1, 1000000));
                }
            }
            result.add(new Entry(Material.SLIME_BALL, "Add level price", Arrays.asList(
                    "Only the levels you add are overridden.", "Totals must rise at every level when you Apply."),
                    () -> editor.prompt(draft, "Which level (1..255)?", input -> {
                        int level = (Integer) ConfigFields.integer("", "", "", 1, 255).parse(input);
                        draft.route = "level:" + name + ":" + level;
                    }), null));
        } else if (route.startsWith("level:")) {
            String[] parts = route.split(":");
            addField(result, draft, ConfigFields.integer("enchantments." + parts[1] + ".levels." + parts[2],
                    "Level " + parts[2] + " total XP", "Set the cumulative total. Other levels still use the starting price.", 1, 1000000));
        }
    }

    private void itemEntries(List<Entry> result, Draft draft) {
        String route = draft.route;
        if (route.equals("items")) {
            ConfigurationSection items = draft.document.getConfigurationSection("items");
            if (items != null) {
                for (String material : items.getKeys(false)) {
                    result.add(new Entry(Material.valueOf(material), ConfigFields.label(material), Arrays.asList(
                            "Set caps for this item type.", "Right click removes this item's overrides."),
                            () -> editor.navigate(draft, "item:" + material),
                            () -> { draft.document.set("items." + material, null); editor.show(draft); }));
                }
            }
            result.add(new Entry(Material.SLIME_BALL, "Add item type", Arrays.asList(
                    "Use a legacy item name such as DIAMOND_SWORD.", "BOOK also covers ENCHANTED_BOOK unless it has its own cap."),
                    () -> editor.prompt(draft, "Which legacy item type (for example DIAMOND_SWORD)?", input -> {
                        String material = ConfigValues.material(input.toUpperCase(Locale.ROOT), "Item type").name();
                        draft.route = "pick:" + material;
                    }), null));
        } else if (route.startsWith("item:")) {
            String material = route.substring(5);
            String path = "items." + material + ".max-levels";
            ConfigurationSection caps = draft.document.getConfigurationSection(path);
            if (caps != null) {
                for (String name : caps.getKeys(false)) {
                    addField(result, draft, capField(path + "." + name, fields.friendly(draft.document, name)));
                }
            }
            category(result, draft, "Add enchantment cap", Material.SLIME_BALL,
                    "Only added caps override the enchantment or vanilla cap.", "pick:" + material);
        }
    }

    private void appearanceEntries(List<Entry> result, Draft draft) {
        String route = draft.route;
        if (route.equals("ui")) {
            addFields(result, draft, fields.leaves("ui"));
            category(result, draft, "Headings", Material.SIGN, "Titles for each enchanting step.", "titles");
            category(result, draft, "Buttons and decorations", Material.ITEM_FRAME, "Item, variant, display name and lore.", "icons");
            result.add(new Entry(Material.BUCKET, "Use default appearance", Arrays.asList(
                    "Remove all menu appearance overrides, including height.",
                    "Use this to restore default stage lore too."),
                    () -> { draft.document.set("ui", null); editor.show(draft); }, null));
        } else if (route.equals("titles")) {
            addFields(result, draft, fields.leaves("ui.titles"));
        } else if (route.equals("icons")) {
            for (String icon : ConfigValues.defaults().getConfigurationSection("ui.icons").getKeys(false)) {
                category(result, draft, ConfigFields.label(icon), Material.ITEM_FRAME, "Edit this menu decoration.", "icon:" + icon);
            }
        } else if (route.startsWith("icon:")) {
            String path = "ui.icons." + route.substring(5);
            addFields(result, draft, fields.leaves(path));
            result.add(new Entry(Material.BUCKET, "Use default icon", Arrays.asList(
                    "Remove this whole icon's overrides.", "Its default item, name and lore will be used."),
                    () -> { draft.document.set(path, null); editor.show(draft); }, null));
        } else if (route.equals("messages")) {
            addFields(result, draft, fields.leaves("messages"));
        } else if (route.startsWith("lines:")) {
            String path = route.substring(6);
            List<String> lines = lines(draft, path);
            for (int i = 0; i < lines.size(); i++) {
                final int index = i;
                result.add(new Entry(Material.PAPER, "Line " + (i + 1), Arrays.asList(
                        preview(lines.get(i)), "Left click to edit. Right click to delete."),
                        () -> editor.prompt(draft, "New text for line " + (index + 1) + " (& colors work):", input -> {
                            List<String> changed = lines(draft, path);
                            changed.set(index, input);
                            draft.document.set(path, changed);
                        }),
                        () -> {
                            List<String> changed = lines(draft, path);
                            changed.remove(index);
                            draft.document.set(path, changed);
                            editor.show(draft);
                        }));
            }
            result.add(new Entry(Material.SLIME_BALL, "Add line", Arrays.asList("Use <blank> to add a blank line."),
                    () -> editor.prompt(draft, "Text for the new line (& colors work):", input -> {
                        List<String> changed = lines(draft, path);
                        if (changed.size() >= 256) {
                            throw new IllegalArgumentException("Keep lore to 256 lines or fewer.");
                        }
                        changed.add(input);
                        draft.document.set(path, changed);
                    }), null));
            result.add(new Entry(Material.BUCKET, "Clear all lines", Arrays.asList("Keep an explicit empty list."),
                    () -> { draft.document.set(path, new ArrayList<String>()); editor.show(draft); }, null));
        }
    }

    private ConfigFields.Field capField(String path, String name) {
        return ConfigFields.integer(path, name, "0 disables. Reset inherits the enchantment or vanilla cap.", 0, 255);
    }

    private Object inheritedCap(Draft draft, String material, String name) {
        if (Boolean.FALSE.equals(fields.effective(draft.document, "enchantments." + name + ".enabled"))) {
            return 0;
        }
        boolean book = material.equals("BOOK") || material.equals("ENCHANTED_BOOK");
        String path = "items." + (book ? "ENCHANTED_BOOK" : material) + ".max-levels." + name;
        if (draft.document.contains(path, true)) {
            return draft.document.get(path);
        }
        path = "items.BOOK.max-levels." + name;
        if (book && draft.document.contains(path, true)) {
            return draft.document.get(path);
        }
        return fields.effective(draft.document, "enchantments." + name + ".max-level");
    }

    private void category(List<Entry> entries, Draft draft, String name, Material material, String help, String route) {
        entries.add(new Entry(material, name, Arrays.asList(help, "Left click to open."), () -> editor.navigate(draft, route), null));
    }

    private void addFields(List<Entry> entries, Draft draft, List<ConfigFields.Field> values) {
        for (ConfigFields.Field field : values) {
            addField(entries, draft, field);
        }
    }

    private void addField(List<Entry> entries, Draft draft, ConfigFields.Field field) {
        Object value = fields.effective(draft.document, field.path);
        boolean explicit = draft.document.contains(field.path, true);
        List<String> lore = new ArrayList<String>();
        lore.add((explicit ? "Set to: " : "Uses: ") + preview(value instanceof List ? ((List<?>) value).size() + " lines" : String.valueOf(value)));
        lore.add(field.help);
        if (field.path.startsWith("messages.")) {
            lore.add("& colors work here. Use <blank> to hide this text.");
        }
        if (field.path.startsWith("ui.") && !draft.document.isConfigurationSection("ui")) {
            lore.add("Adding menu overrides leaves omitted stage lore empty.");
        }
        if (field.kind == ConfigFields.Kind.TEXT || field.kind == ConfigFields.Kind.LINES) {
            List<String> tokens = new ArrayList<String>(MenuText.placeholders(field.path));
            tokens.remove("prefix");
            Collections.sort(tokens);
            if (!tokens.isEmpty()) {
                List<String> examples = new ArrayList<String>();
                for (String token : tokens) {
                    examples.add("{" + token + "}");
                }
                lore.add("Placeholders: " + String.join(", ", examples));
            }
        }
        if (field.kind == ConfigFields.Kind.LINES) {
            lore.add("Omitted lore is empty in a configured section.");
        }
        if (field.path.startsWith("items.")) {
            lore.add("ENCHANTED_BOOK caps win over BOOK caps.");
        }
        lore.add(field.kind == ConfigFields.Kind.BOOLEAN ? "Left click to toggle." : "Left click to edit.");
        lore.add("Right click to reset or remove the override.");
        Material material = field.kind == ConfigFields.Kind.BOOLEAN ? Material.LEVER :
                field.kind == ConfigFields.Kind.INTEGER || field.kind == ConfigFields.Kind.DECIMAL ? Material.EXP_BOTTLE :
                field.kind == ConfigFields.Kind.SOUND ? Material.NOTE_BLOCK : Material.PAPER;
        entries.add(new Entry(material, field.name, lore, () -> {
            if (field.kind == ConfigFields.Kind.BOOLEAN) {
                draft.document.set(field.path, !Boolean.TRUE.equals(fields.effective(draft.document, field.path)));
                editor.show(draft);
            } else if (field.kind == ConfigFields.Kind.LINES) {
                editor.navigate(draft, "lines:" + field.path);
            } else {
                editor.prompt(draft, field.name + ": " + field.help, input -> draft.document.set(field.path, field.parse(input)));
            }
        }, () -> {
            // These two required prices have no inherited value.
            if (field.path.equals("base-xp") || field.path.equals("max-cost")) {
                draft.document.set(field.path, ConfigValues.defaults().get(field.path));
            } else {
                draft.document.set(field.path, null);
            }
            editor.show(draft);
        }));
    }

    private List<String> lines(Draft draft, String path) {
        List<String> result = new ArrayList<String>();
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
        return text.length() > 72 ? text.substring(0, 69) + "..." : text;
    }
}
