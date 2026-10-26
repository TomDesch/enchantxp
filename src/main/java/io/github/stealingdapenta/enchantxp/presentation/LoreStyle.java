package io.github.stealingdapenta.enchantxp.presentation;

import io.github.stealingdapenta.enchantxp.configuration.values.ConfigValues;
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
import org.bukkit.configuration.ConfigurationSection;

/**
 * Explicit lore roles shared by table templates and operator pages, never inferred from prose.
 */
public final class LoreStyle {

    public static final String PATH = "lore-style";
    public static final int MAX_PREFIX_LENGTH = 16;
    private static final String ACTION_MARKER = "{action}";
    private static final int WRAP_WIDTH = 48;
    private static final Pattern MARKER = Pattern.compile("\\{(action|label|number|text)\\}");
    private static final Set<String> MARKERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("action", "label", "number", "text")));
    private final Map<String, String> roles;

    private LoreStyle(Map<String, String> roles) {
        this.roles = Collections.unmodifiableMap(new HashMap<>(roles));
    }

    /**
     * Reads explicit lore roles with independent base colors and optional packaged fallbacks.
     *
     * @throws IllegalArgumentException with a path for unknown keys, invalid codes or an oversized prefix
     */
    public static LoreStyle read(ConfigurationSection config) {
        ConfigurationSection allDefaults = ConfigValues.defaults();
        ConfigurationSection defaults = allDefaults.getConfigurationSection(PATH);
        ConfigurationSection section = ConfigValues.section(config, PATH);
        if (section != null) {
            for (String key : section.getKeys(false)) {
                if (!defaults.contains(key, true)) {
                    throw new IllegalArgumentException(PATH + "." + key + " is unknown");
                }
            }
        }
        Map<String, String> settings = new HashMap<>();
        for (String key : defaults.getKeys(false)) {
            Object value = ConfigValues.value(config, allDefaults, PATH + "." + key);
            if (!(value instanceof String)) {
                throw new IllegalArgumentException(PATH + "." + key + " must be a string");
            }
            String translated = ChatColor.translateAlternateColorCodes('&', validateSetting(key, (String) value));
            settings.put(key, key.equals("action-prefix") ? translated : independentColor(translated));
        }
        Map<String, String> roles = new HashMap<>();
        String actionPrefix = settings.get("action-prefix");
        String action = settings.get("action-color") + actionPrefix;
        if (actionPrefix.indexOf(ChatColor.COLOR_CHAR) >= 0) {
            action += settings.get("action-color");
        }
        roles.put("action", action);
        roles.put("label", settings.get("label-color"));
        roles.put("number", settings.get("number-color"));
        roles.put("text", settings.get("text-color"));
        return new LoreStyle(roles);
    }

    /**
     * Validates a known role setting without translating its ampersand codes.
     * The action prefix allows at most 16 visible characters and no placeholders or line breaks.
     *
     * @throws IllegalArgumentException for invalid prefix text or non-color role contents
     */
    public static String validateSetting(String key, String value) {
        if (key.equals("action-prefix")) {
            if (value.contains("{") || value.contains("}") || value.contains("\n") || value.contains("\r")) {
                throw new IllegalArgumentException(PATH + "." + key + " must be single-line text without placeholders");
            }
            String visible = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', value));
            if (visible.codePointCount(0, visible.length()) > MAX_PREFIX_LENGTH) {
                throw new IllegalArgumentException(PATH + "." + key + " must contain at most " + MAX_PREFIX_LENGTH + " visible characters (color codes do not count)");
            }
        } else if (!value.matches("(?i)(?:&[0-9a-fk-or])*")) {
            throw new IllegalArgumentException(PATH + "." + key + " must contain only & color or formatting codes");
        }
        return value;
    }

    private static String independentColor(String codes) {
        for (int index = 0; index + 1 < codes.length(); index += 2) {
            ChatColor color = ChatColor.getByChar(codes.charAt(index + 1));
            if (color != null && (color.isColor() || color == ChatColor.RESET)) {
                return codes;
            }
        }
        return ChatColor.RESET + codes;
    }

    static Set<String> markers() {
        return MARKERS;
    }

    /** Marks an instruction explicitly for shared action styling; does not inspect its English wording. */
    public static String action(String instruction) {
        return ACTION_MARKER + instruction;
    }

    /** Builds a label/text role pair; editor rendering keeps placeholder-like content in the value literal. */
    public static String info(String label, Object value) {
        return "{label}" + label + ": {text}" + value;
    }

    /** Builds a label/number role pair; callers supply the value and any units, without numeric coercion. */
    public static String number(String label, Object value) {
        return "{label}" + label + ": {number}" + value;
    }

    String format(String template) {
        Matcher matcher = MARKER.matcher(template);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(output, Matcher.quoteReplacement(roles.get(matcher.group(1))));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    /**
     * Returns fresh wrapped operator lore with explicit leading roles, preserving literal preview values.
     * Braces and ampersand formatting examples inside values are never interpreted; wrapping carries legacy colors.
     */
    public List<String> editorLines(List<String> templates) {
        List<String> result = new ArrayList<>();
        for (String template : templates) {
            boolean action = template.startsWith(ACTION_MARKER);
            String base = startsWithResetRole(template) ? "" : roles.get("text");
            String prefix = base + (action ? roles.get("action") : "");
            String remaining = action ? template.substring(ACTION_MARKER.length()) : editorFormat(template);
            int width = Math.max(1, WRAP_WIDTH - ChatColor.stripColor(prefix)
                                                          .length());
            while (ChatColor.stripColor(remaining)
                            .length() > width) {
                int split = wrapAt(remaining, width);
                String line = remaining.substring(0, split);
                result.add(prefix + line);
                remaining = ChatColor.getLastColors(prefix + line) + remaining.substring(split)
                                                                              .trim();
            }
            result.add(prefix + remaining);
        }
        return result;
    }

    private boolean startsWithResetRole(String template) {
        Matcher marker = MARKER.matcher(template);
        return marker.lookingAt() && roles.get(marker.group(1))
                                          .startsWith(ChatColor.RESET.toString());
    }

    private String editorFormat(String template) {
        Matcher marker = MARKER.matcher(template);
        if (!marker.lookingAt()) {
            return template;
        }
        String role = marker.group(1);
        String remainder = template.substring(marker.end());
        if (role.equals("label")) {
            int separator = remainder.indexOf(": {");
            if (separator >= 0) {
                String label = remainder.substring(0, separator + 2);
                String value = remainder.substring(separator + 2);
                Matcher valueRole = MARKER.matcher(value);
                if (valueRole.lookingAt()) {
                    return roles.get(role) + label + roles.get(valueRole.group(1)) + value.substring(valueRole.end());
                }
            }
        }
        return roles.get(role) + remainder;
    }

    private static int wrapAt(String text, int width) {
        int visible = 0;
        int lastSpace = -1;
        int index = 0;
        while (index < text.length() && visible < width) {
            char character = text.charAt(index);
            if (character == ChatColor.COLOR_CHAR && index + 1 < text.length()) {
                index += 2;
                continue;
            }
            if (character == ' ') {
                lastSpace = index;
            }
            index++;
            visible++;
        }
        return lastSpace > 0 ? lastSpace : index;
    }
}
