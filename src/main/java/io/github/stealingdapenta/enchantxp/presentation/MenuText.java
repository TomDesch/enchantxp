package io.github.stealingdapenta.enchantxp.presentation;

import io.github.stealingdapenta.enchantxp.configuration.values.ConfigValues;
import io.github.stealingdapenta.enchantxp.configuration.values.NumericRule;
import io.github.stealingdapenta.enchantxp.enchanting.Enchantments;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Validated templates and legacy icon styles, rendered once without interpreting inserted values.
 */
public final class MenuText {

    private static final String UI_PATH = "ui.";
    private static final String MESSAGE_PATH = "messages.";
    private static final String ENCHANTMENT_PATH = "enchantments.";
    private static final String TITLE_PATH = "ui.titles.";
    private static final String PREFIX_PATH = "messages.prefix";
    private static final String NAME_SUFFIX = ".name";
    private static final int MAX_TITLE_LENGTH = 32;
    private static final Pattern TOKEN = Pattern.compile("\\{([^{}]+)}");
    private static final Set<String> UI_TOKENS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("player", "item", "enchantment", "level", "current", "max", "cost", "balance", "page", "pages", "missing")));
    private final Map<String, String> strings;
    private final Map<String, List<String>> lists;
    private final Map<String, Material> materials;
    private final Map<String, Short> data;
    private final LoreStyle loreStyle;

    private MenuText(Map<String, String> strings, Map<String, List<String>> lists, Map<String, Material> materials, Map<String, Short> data, LoreStyle loreStyle) {
        this.strings = Collections.unmodifiableMap(new HashMap<>(strings));
        this.lists = Collections.unmodifiableMap(new HashMap<>(lists));
        this.materials = Collections.unmodifiableMap(new HashMap<>(materials));
        this.data = Collections.unmodifiableMap(new HashMap<>(data));
        this.loreStyle = loreStyle;
    }

    /**
     * Validates optional UI/message trees and copies templates with packaged fallbacks.
     * Omitted lore under an explicit UI section stays empty; inserted values are never parsed as templates.
     *
     * @throws IllegalArgumentException with the path for invalid types, materials or placeholders
     */
    public static MenuText read(ConfigurationSection config) {
        YamlConfiguration defaults = ConfigValues.defaults();
        validateTree(config, defaults, "ui");
        validateTree(config, defaults, "messages");
        Map<String, String> strings = new HashMap<>();
        Map<String, List<String>> lists = new HashMap<>();
        Map<String, Material> materials = new HashMap<>();
        Map<String, Short> data = new HashMap<>();
        for (String path : defaults.getKeys(true)) {
            if (isTemplatePath(defaults, path)) {
                readTemplate(config, defaults, path, strings, lists, materials, data);
            }
        }
        readNames(config, defaults, strings);
        return new MenuText(strings, lists, materials, data, LoreStyle.read(config));
    }

    private static boolean isTemplatePath(ConfigurationSection defaults, String path) {
        return (path.startsWith(UI_PATH) || path.startsWith(MESSAGE_PATH)) && !defaults.isConfigurationSection(path) && !path.equals("ui.rows");
    }

    private static void readTemplate(ConfigurationSection config, ConfigurationSection defaults, String path, Map<String, String> strings, Map<String, List<String>> lists, Map<String, Material> materials, Map<String, Short> data) {
        Object value = legacyTemplate(path, ConfigValues.value(config, defaults, path), defaults);
        if (path.endsWith(".material")) {
            materials.put(path, ConfigValues.material(value, path));
        } else if (path.endsWith(".data")) {
            data.put(path, (short) NumericRule.ITEM_DATA.readInteger(value, path));
        } else if (defaults.get(path) instanceof List) {
            lists.put(path, readLore(config, path, value));
        } else {
            strings.put(path, template(value, path));
        }
    }

    private static Object legacyTemplate(String path, Object value, ConfigurationSection defaults) {
        if (path.equals("messages.success") && "&5EnchantedXP &8> &aConfirmed {enchantment} {level} for {cost} XP.".equals(value)) {
            return "&aConfirmed {enchantment} {level} for {cost} XP.";
        }
        if (path.equals("messages.eligibility") && "{prefix}&cEnchantXP requires survival mode and enchantxp.use.".equals(value)) {
            return "&cEnchantXP requires survival or creative mode and enchantxp.use.";
        }
        if (path.equals("messages.unavailable") && "&eThe remaining upgrades cost more than the server allows.".equals(value)) {
            return defaults.get(path);
        }
        return value;
    }

    private static List<String> readLore(ConfigurationSection config, String path, Object value) {
        String parent = path.substring(0, path.lastIndexOf('.'));
        if (!config.contains(path, true) && ConfigValues.section(config, parent) != null) {
            value = Collections.emptyList();
        }
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(path + " must be a list of strings");
        }
        List<String> copy = ((List<?>) value).stream()
                                            .map(line -> template(line, path))
                                            .collect(Collectors.toList());
        return Collections.unmodifiableList(copy);
    }

    private static void readNames(ConfigurationSection config, ConfigurationSection defaults, Map<String, String> strings) {
        ConfigurationSection enchants = ConfigValues.section(config, "enchantments");
        for (String name : Enchantments.names()) {
            String path = ENCHANTMENT_PATH + name + NAME_SUFFIX;
            Object value = enchants != null && config.contains(path, true) ? config.get(path) : defaults.get(path);
            strings.put(path, template(value, path));
        }
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

    /** Returns a fresh mutable set of tokens accepted at a configuration path, or an empty set for literal text. */
    public static Set<String> placeholders(String path) {
        Set<String> allowed = new HashSet<>();
        if (path.startsWith(UI_PATH)) {
            uiPlaceholders(path, allowed);
        } else if (path.startsWith(MESSAGE_PATH) && !path.equals(PREFIX_PATH)) {
            messagePlaceholders(path, allowed);
        }
        return allowed;
    }

    private static void uiPlaceholders(String path, Set<String> allowed) {
        allowed.addAll(UI_TOKENS);
        if (path.endsWith("-lore") || path.endsWith(".lore")) {
            allowed.addAll(LoreStyle.markers());
        }
        if (path.equals("ui.icons.sort.name") || path.equals("ui.icons.sort.lore")) {
            allowed.add("sort");
        }
        if (path.equals("ui.icons.info.name") || path.equals("ui.icons.info.lore")) {
            allowed.add("bookshelf-status");
        }
        if (path.startsWith(TITLE_PATH) || path.startsWith("ui.selection-")) {
            titlePlaceholders(path, allowed);
        }
    }

    private static void titlePlaceholders(String path, Set<String> allowed) {
        allowed.remove("enchantment");
        allowed.remove("level");
        allowed.remove("current");
        allowed.remove("max");
        allowed.remove("cost");
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

    private static void messagePlaceholders(String path, Set<String> allowed) {
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
        String literal = TOKEN.matcher(text)
                              .replaceAll("");
        if (literal.contains("{") || literal.contains("}")) {
            throw new IllegalArgumentException(path + " contains malformed placeholder");
        }
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /** Renders a known scalar template once; missing context values become empty and inserted braces stay literal. */
    public String text(String path, Map<String, String> values) {
        return render(strings.get(path), values);
    }

    /** Renders a stage title within the legacy 32-character limit, never leaving a dangling color introducer. */
    public String title(MenuStage page, Map<String, String> values) {
        String title = text(TITLE_PATH + page.name()
                                             .toLowerCase(Locale.ROOT), values);
        if (title.length() > MAX_TITLE_LENGTH) {
            title = title.substring(0, MAX_TITLE_LENGTH);
        }
        if (title.endsWith(String.valueOf(ChatColor.COLOR_CHAR))) {
            title = title.substring(0, title.length() - 1);
        }
        return title;
    }

    /** Returns the configured display name for a supported catalogue enchantment, with no player context. */
    public String name(Enchantment enchantment) {
        return text(ENCHANTMENT_PATH + enchantment.getName() + NAME_SUFFIX, Collections.emptyMap());
    }

    /** Returns fresh ordered lore with explicit shared roles applied before single-pass context substitution. */
    public List<String> lore(String path, Map<String, String> values) {
        return lists.get(path)
                    .stream()
                    .map(line -> render(loreStyle.format(line), values))
                    .collect(Collectors.toList());
    }

    /**
     * Sends a known message key as separate nonblank lines, each prefixed once with inherited line colors.
     * Player and prefix context are owned here; the caller's map is not changed. Use on the server thread.
     */
    public void send(CommandSender sender, String message, Map<String, String> values) {
        Map<String, String> context = new HashMap<>(values);
        context.put("player", sender.getName() == null ? "" : sender.getName());
        context.put("prefix", "");
        String body = text(MESSAGE_PATH + message, context);
        String prefix = text(PREFIX_PATH, Collections.emptyMap());
        String continuation = "";
        for (String line : body.split("\\r?\\n", -1)) {
            String colored = continuation + line;
            if (!ChatColor.stripColor(colored)
                          .trim()
                          .isEmpty()) {
                sender.sendMessage(prefix + colored);
            }
            continuation = ChatColor.getLastColors(colored);
        }
    }

    /**
     * Builds a fresh legacy decoration from a known icon key on the server thread.
     *
     * @throws IllegalStateException if Bukkit cannot supply or apply item metadata
     */
    public ItemStack icon(String key, Map<String, String> values) {
        String path = "ui.icons." + key;
        ItemStack item = new ItemStack(materials.get(path + ".material"), 1, data.get(path + ".data"));
        decorate(item, text(path + NAME_SUFFIX, values), lore(path + ".lore", values));
        return item;
    }

    /**
     * Builds a fresh display book for a known template stage; positive levels add a stored preview enchantment.
     * This is presentation only, not purchase validation. Use on the server thread.
     *
     * @throws IllegalStateException if Bukkit rejects the stored enchantment or metadata
     */
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

    /**
     * Updates only name, lore and hidden-attribute flags on a caller-owned display item, on the server thread.
     *
     * @throws IllegalStateException if Bukkit cannot supply or apply metadata
     */
    public static void decorate(ItemStack item, String name, List<String> lore) {
        decorate(item, item.getItemMeta(), name, lore);
    }

    /**
     * Decorates and applies the caller's already retrieved display metadata in one checked update.
     * Other metadata mutations must be complete before this call. Use on the server thread.
     *
     * @throws IllegalStateException if metadata is null or Bukkit rejects its application
     */
    public static void decorate(ItemStack item, ItemMeta meta, String name, List<String> lore) {
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
