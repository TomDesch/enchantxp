package io.github.stealingdapenta.enchantxp;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

final class MenuSounds {
    private final Map<String, Effect> effects;
    private final boolean enabled;

    private MenuSounds(boolean enabled, Map<String, Effect> effects) {
        this.enabled = enabled;
        this.effects = effects;
    }

    static MenuSounds read(ConfigurationSection config) {
        ConfigurationSection defaults = ConfigValues.defaults();
        ConfigValues.keys(ConfigValues.section(config, "sounds"), "enabled", "click", "success", "insufficient");
        Map<String, Effect> effects = new HashMap<String, Effect>();
        for (String key : new String[]{"click", "success", "insufficient"}) {
            String path = "sounds." + key;
            ConfigValues.keys(ConfigValues.section(config, path), "sound", "volume", "pitch");
            Object name = ConfigValues.value(config, defaults, path + ".sound");
            if (!(name instanceof String)) {
                throw new IllegalArgumentException(path + ".sound must be a legacy sound name or empty string");
            }
            Sound sound;
            try {
                sound = ((String) name).isEmpty() ? null : Sound.valueOf((String) name);
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException(path + ".sound must be a legacy sound name or empty string");
            }
            effects.put(key, new Effect(sound, number(config, defaults, path + ".volume", 0, 4),
                    number(config, defaults, path + ".pitch", 0, 2)));
        }
        return new MenuSounds(ConfigValues.bool(config, defaults, "sounds.enabled"), effects);
    }

    private static float number(ConfigurationSection config, ConfigurationSection defaults, String path, float min, float max) {
        Object value = ConfigValues.value(config, defaults, path);
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(path + " must be numeric");
        }
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number < min || number > max) {
            throw new IllegalArgumentException(path + " must be finite and between " + min + " and " + max);
        }
        return (float) number;
    }

    void play(Player player, String key) {
        Effect effect = effects.get(key);
        if (enabled && effect.sound != null) {
            player.playSound(player.getLocation(), effect.sound, effect.volume, effect.pitch);
        }
    }

    private static final class Effect {
        final Sound sound;
        final float volume;
        final float pitch;

        Effect(Sound sound, float volume, float pitch) {
            this.sound = sound;
            this.volume = volume;
            this.pitch = pitch;
        }
    }
}
