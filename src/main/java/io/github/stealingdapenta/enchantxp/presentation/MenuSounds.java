package io.github.stealingdapenta.enchantxp.presentation;

import io.github.stealingdapenta.enchantxp.configuration.values.ConfigValues;
import io.github.stealingdapenta.enchantxp.configuration.values.NumericRule;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Immutable legacy feedback effects, sent privately rather than broadcast into the world.
 */
public final class MenuSounds {

    private static final String SOUND_ERROR = " must be a legacy sound name or empty string";
    private final Map<String, Effect> effects;
    private final boolean enabled;

    private MenuSounds(boolean enabled, Map<String, Effect> effects) {
        this.enabled = enabled;
        this.effects = Collections.unmodifiableMap(new HashMap<>(effects));
    }

    /**
     * Reads optional legacy effects; an empty sound name means silence, volume is `0..4`, pitch is `0..2`.
     *
     * @throws IllegalArgumentException with the path for unknown keys, names or invalid numeric settings
     */
    public static MenuSounds read(ConfigurationSection config) {
        ConfigurationSection defaults = ConfigValues.defaults();
        ConfigValues.keys(ConfigValues.section(config, "sounds"), "enabled", "click", "success", "insufficient");
        Map<String, Effect> effects = new HashMap<>();
        for (String key : new String[]{"click", "success", "insufficient"}) {
            effects.put(key, readEffect(config, defaults, "sounds." + key));
        }
        return new MenuSounds(ConfigValues.bool(config, defaults, "sounds.enabled"), effects);
    }

    private static Effect readEffect(ConfigurationSection config, ConfigurationSection defaults, String path) {
        ConfigValues.keys(ConfigValues.section(config, path), "sound", "volume", "pitch");
        Sound sound = sound(ConfigValues.value(config, defaults, path + ".sound"), path + ".sound");
        return new Effect(sound, number(config, defaults, path + ".volume", NumericRule.VOLUME), number(config, defaults, path + ".pitch", NumericRule.PITCH));
    }

    private static Sound sound(Object name, String path) {
        if (!(name instanceof String)) {
            throw new IllegalArgumentException(path + SOUND_ERROR);
        }
        try {
            return ((String) name).isEmpty() ? null : Sound.valueOf((String) name);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(path + SOUND_ERROR);
        }
    }

    private static float number(ConfigurationSection config, ConfigurationSection defaults, String path, NumericRule rule) {
        return (float) rule.readDecimal(ConfigValues.value(config, defaults, path), path);
    }

    /** Plays a known effect key (`click`, `success`, `insufficient`) privately on the server thread, unless silent or disabled. */
    public void play(Player player, String key) {
        Effect effect = effects.get(key);
        if (enabled && effect.sound != null) {
            player.playSound(player.getLocation(), effect.sound, effect.volume, effect.pitch);
        }
    }

    private static final class Effect {

        private final Sound sound;
        private final float volume;
        private final float pitch;

        private Effect(Sound sound, float volume, float pitch) {
            this.sound = sound;
            this.volume = volume;
            this.pitch = pitch;
        }
    }
}
