package io.github.stealingdapenta.enchantxp.presentation;

import io.github.stealingdapenta.enchantxp.configuration.values.ConfigValues;
import io.github.stealingdapenta.enchantxp.enchanting.Enchantments;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/** Validated templates and legacy icon styles, rendered once without interpreting inserted values. */
public final class MenuText {
    private static final Pattern TOKEN = Pattern.compile("\\{([^{}]+)\\}");
    private static final Set<String> UI_TOKENS = new HashSet<String>(Arrays.asList(
            "player", "item", "enchantment", "level", "current", "max", "cost", "balance", "page", "pages", "missing"));
    private final Map<String, String> strings;
    private final Map<String, List<String>> lists;
    private final Map<String, Material> materials;
    private final Map<String, Short> data;

    private MenuText(Map<String, String> strings, Map<String, List<String>> lists,
            Map<String, Material> materials, Map<String, Short> data) {
        this.strings = Collections.unmodifiableMap(new HashMap<String, String>(strings));
        this.lists = Collections.unmodifiableMap(new HashMap<String, List<String>>(lists));
        this.materials = Collections.unmodifiableMap(new HashMap<String, Material>(materials));
        this.data = Collections.unmodifiableMap(new HashMap<String, Short>(data));
    }

    public static MenuText read(ConfigurationSection config) {
        YamlConfiguration defaults = ConfigValues.defaults();
        validateTree(config, defaults, "ui");
        validateTree(config, defaults, "messages");
        Map<String, String> strings = new HashMap<String, String>();
        Map<String, List<String>> lists = new HashMap<String, List<String>>();
        Map<String, Material> materials = new HashMap<String, Material>();
        Map<String, Short> data = new HashMap<String, Short>();
        for (String path : defaults.getKeys(true)) {
            if (!path.startsWith("ui.") && !path.startsWith("messages.")) {
                continue;
            }
            if (defaults.isConfigurationSection(path)) {
                continue;
            }
            if (path.equals("ui.rows")) {
                continue;
            }
            Object value = ConfigValues.value(config, defaults, path);
            if (path.equals("messages.success") &&
                    "&5EnchantedXP &8> &aConfirmed {enchantment} {level} for {cost} XP.".equals(value)) {
                value = "&aConfirmed {enchantment} {level} for {cost} XP.";
            }
            if (path.equals("messages.eligibility") &&
                    "{prefix}&cEnchantXP requires survival mode and enchantxp.use.".equals(value)) {
                value = "&cEnchantXP requires survival or creative mode and enchantxp.use.";
            }
            if (path.endsWith(".material")) {
                materials.put(path, ConfigValues.material(value, path));
            } else if (path.endsWith(".data")) {
                data.put(path, (short) ConfigValues.integer(value, path, 0, Short.MAX_VALUE));
            } else if (defaults.get(path) instanceof List) {
                String parent = path.substring(0, path.lastIndexOf('.'));
                if (!config.contains(path, true) && ConfigValues.section(config, parent) != null) {
                    value = Collections.emptyList();
                }
                if (!(value instanceof List)) {
                    throw new IllegalArgumentException(path + " must be a list of strings");
                }
                List<String> copy = new ArrayList<String>();
                for (Object line : (List<?>) value) {
                    copy.add(template(line, path));
                }
                lists.put(path, Collections.unmodifiableList(copy));
            } else {
                strings.put(path, template(value, path));
            }
        }
        ConfigurationSection enchants = ConfigValues.section(config, "enchantments");
        for (String name : Enchantments.names()) {
            String path = "enchantments." + name + ".name";
            Object value = enchants != null && config.contains(path, true) ? config.get(path) : defaults.get(path);
            strings.put(path, template(value, path));
        }
        return new MenuText(strings, lists, materials, data);
    }

    private static void validateTree(ConfigurationSection config, ConfigurationSection defaults, String path) {
        ConfigurationSection section = ConfigValues.section(config, path);
        if (section == null) {
            return;
        }
        ConfigurationSection expected = defaults.getConfigurationSection(path);
        for (String key : section.getKeys(false)) {
            String child = path + "." + key;
            if (!expected.contains(key, true)) {
                throw new IllegalArgumentException(child + " is unknown");
            }
            if (expected.isConfigurationSection(key)) {
                validateTree(config, defaults, child);
            }
        }
    }

    public static Set<String> placeholders(String path) {
        Set<String> allowed = new HashSet<String>();
        if (path.startsWith("ui.")) {
            allowed.addAll(UI_TOKENS);
            if (path.equals("ui.icons.sort.name") || path.equals("ui.icons.sort.lore")) {
                allowed.add("sort");
            }
            if (path.equals("ui.icons.info.name") || path.equals("ui.icons.info.lore")) {
                allowed.add("bookshelf-status");
            }
            if (path.startsWith("ui.titles.") || path.startsWith("ui.selection-")) {
                allowed.removeAll(Arrays.asList("enchantment", "level", "current", "max", "cost"));
                if (path.equals("ui.titles.item")) {
                    allowed.remove("item");
                }
                if (path.equals("ui.titles.level") || path.equals("ui.titles.confirm")) {
                    allowed.addAll(Arrays.asList("enchantment", "current", "max"));
                }
                if (path.equals("ui.titles.confirm")) {
                    allowed.addAll(Arrays.asList("level", "cost"));
                }
            }
        } else if (path.startsWith("messages.") && !path.equals("messages.prefix")) {
            allowed.add("prefix");
            allowed.add("player");
            if (path.equals("messages.insufficient") || path.equals("messages.success")) {
                allowed.add("cost");
                allowed.add("balance");
            }
            if (path.equals("messages.success")) {
                allowed.add("enchantment");
                allowed.add("level");
            }
            if (path.equals("messages.reload-failure")) {
                allowed.add("path");
            }
            if (path.equals("messages.reload-usage")) {
                allowed.add("commands");
            }
            if (path.startsWith("messages.config-")) {
                allowed.add("detail");
            }
        }
        return allowed;
    }

    private static String template(Object value, String path) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        Set<String> allowed = placeholders(path);
        String text = (String) value;
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            if (!allowed.contains(matcher.group(1))) {
                throw new IllegalArgumentException(path + " contains unknown placeholder " + matcher.group());
            }
        }
        if (TOKEN.matcher(text).replaceAll("").contains("{") || TOKEN.matcher(text).replaceAll("").contains("}")) {
            throw new IllegalArgumentException(path + " contains malformed placeholder");
        }
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public String text(String path, Map<String, String> values) {
        return render(strings.get(path), values);
    }

    public String title(MenuStage page, Map<String, String> values) {
        String title = text("ui.titles." + page.name().toLowerCase(java.util.Locale.ROOT), values);
        if (title.length() > 32) {
            title = title.substring(0, 32);
        }
        if (title.endsWith(String.valueOf(ChatColor.COLOR_CHAR))) {
            title = title.substring(0, title.length() - 1);
        }
        return title;
    }

    public String name(Enchantment enchantment) {
        return text("enchantments." + enchantment.getName() + ".name", Collections.emptyMap());
    }

    public List<String> lore(String path, Map<String, String> values) {
        List<String> result = new ArrayList<String>();
        for (String line : lists.get(path)) {
            result.add(render(line, values));
        }
        return result;
    }

    public void send(CommandSender sender, String message, Map<String, String> values) {
        Map<String, String> context = new HashMap<String, String>(values);
        context.put("player", sender.getName() == null ? "" : sender.getName());
        context.put("prefix", "");
        String body = text("messages." + message, context);
        if (!ChatColor.stripColor(body).trim().isEmpty()) {
            sender.sendMessage(text("messages.prefix", Collections.emptyMap()) + body);
        }
    }

    public ItemStack icon(String key, Map<String, String> values) {
        String path = "ui.icons." + key;
        ItemStack item = new ItemStack(materials.get(path + ".material"), 1, data.get(path + ".data"));
        decorate(item, text(path + ".name", values), lore(path + ".lore", values));
        return item;
    }

    public ItemStack book(Enchantment enchantment, int level, Map<String, String> values, String stage) {
        ItemStack item = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta raw = item.getItemMeta();
        if (!(raw instanceof EnchantmentStorageMeta)) {
            throw new IllegalStateException("Server did not provide enchanted-book metadata");
        }
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) raw;
        if (level > 0 && !meta.addStoredEnchant(enchantment, level, true)) {
            throw new IllegalStateException("Server rejected the stored enchantment");
        }
        meta.setDisplayName(text("ui." + stage + "-name", values));
        meta.setLore(lore("ui." + stage + "-lore", values));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        apply(item, meta);
        return item;
    }

    public static void decorate(ItemStack item, String name, List<String> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            throw new IllegalStateException("Server did not provide item metadata");
        }
        meta.setDisplayName(name);
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        apply(item, meta);
    }

    private static void apply(ItemStack item, ItemMeta meta) {
        if (!item.setItemMeta(meta)) {
            throw new IllegalStateException("Server rejected menu metadata");
        }
    }

    private static String render(String template, Map<String, String> values) {
        Matcher matcher = TOKEN.matcher(template);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(output, Matcher.quoteReplacement(values.getOrDefault(matcher.group(1), "")));
        }
        matcher.appendTail(output);
        return output.toString();
    }
}
