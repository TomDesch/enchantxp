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

/** Explicit lore roles shared by table templates and operator pages, never inferred from prose. */
public final class LoreStyle {
    public static final String PATH = "lore-style";
    private static final String ACTION_MARKER = "{action}";
    private static final int WRAP_WIDTH = 48;
    private static final Pattern MARKER = Pattern.compile("\\{(action|label|number|text)\\}");
    private static final Set<String> MARKERS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("action", "label", "number", "text")));
    private final Map<String, String> roles;

    private LoreStyle(Map<String, String> roles) {
        this.roles = Collections.unmodifiableMap(new HashMap<>(roles));
    }

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
            settings.put(key, ChatColor.translateAlternateColorCodes('&', validateSetting(key, (String) value)));
        }
        Map<String, String> roles = new HashMap<>();
        roles.put("action", settings.get("action-color") + settings.get("action-prefix"));
        roles.put("label", settings.get("label-color"));
        roles.put("number", settings.get("number-color"));
        roles.put("text", settings.get("text-color"));
        return new LoreStyle(roles);
    }

    public static String validateSetting(String key, String value) {
        if (key.equals("action-prefix")) {
            if (value.contains("{") || value.contains("}") || value.contains("\n") || value.contains("\r")) {
                throw new IllegalArgumentException(PATH + "." + key + " must be single-line text without placeholders");
            }
        } else if (!value.matches("(?i)(?:&[0-9a-fk-or])*")) {
            throw new IllegalArgumentException(PATH + "." + key + " must contain only & color or formatting codes");
        }
        return value;
    }

    static Set<String> markers() {
        return MARKERS;
    }

    public static String action(String instruction) {
        return ACTION_MARKER + instruction;
    }

    public static String info(String label, Object value) {
        return "{label}" + label + ": {text}" + value;
    }

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

    /** Operator previews remain literal, including braces and ampersand formatting examples. */
    public List<String> editorLines(List<String> templates) {
        List<String> result = new ArrayList<>();
        for (String template : templates) {
            boolean action = template.startsWith(ACTION_MARKER);
            String prefix = roles.get("text") + (action ? roles.get("action") : "");
            String remaining = action ? template.substring(ACTION_MARKER.length()) : editorFormat(template);
            int width = Math.max(1, WRAP_WIDTH - ChatColor.stripColor(prefix).length());
            while (ChatColor.stripColor(remaining).length() > width) {
                int split = wrapAt(remaining, width);
                String line = remaining.substring(0, split);
                result.add(prefix + line);
                remaining = ChatColor.getLastColors(prefix + line) + remaining.substring(split).trim();
            }
            result.add(prefix + remaining);
        }
        return result;
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
