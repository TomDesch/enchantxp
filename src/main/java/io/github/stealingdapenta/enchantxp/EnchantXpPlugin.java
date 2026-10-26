package io.github.stealingdapenta.enchantxp;

import io.github.stealingdapenta.enchantxp.administration.ReloadCommand;
import io.github.stealingdapenta.enchantxp.configuration.Settings;
import io.github.stealingdapenta.enchantxp.menu.TableMenus;
import java.io.File;
import java.io.IOException;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bukkit lifecycle wiring; controller state is created only after configuration validation.
 */
public final class EnchantXpPlugin extends JavaPlugin {

    private TableMenus menus;
    private ReloadCommand reload;

    /** Loads defaults only for a missing file, validates settings and registers controllers; invalid input disables the plugin. */
    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            File file = new File(getDataFolder(), "config.yml");
            Settings settings = readSettings(file);
            registerMenus(settings);
            registerConfiguration(settings, file);
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException failure) {
            getLogger().severe("EnchantXP disabled: invalid or unreadable config.yml: " + failure.getMessage());
            getServer().getPluginManager()
                       .disablePlugin(this);
        }
    }

    private Settings readSettings(File file) throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.load(file);
        return Settings.read(config);
    }

    private void registerMenus(Settings settings) {
        menus = new TableMenus(this, settings);
        getServer().getPluginManager()
                   .registerEvents(menus, this);
    }

    private void registerConfiguration(Settings settings, File file) {
        reload = new ReloadCommand(this, menus, settings, file);
        getServer().getPluginManager()
                   .registerEvents(reload.editorListener(), this);
        PluginCommand command = getCommand("enchantxp");
        if (command == null) {
            throw new IllegalStateException("Missing enchantxp command registration");
        }
        command.setExecutor(reload);
        command.setTabCompleter(reload);
    }

    /** Fences configuration workers before closing menus and releasing owned permissions on the server thread. */
    @Override
    public void onDisable() {
        stopConfiguration();
        stopMenus();
    }

    private void stopConfiguration() {
        if (reload != null) {
            reload.disable();
            reload = null;
        }
    }

    private void stopMenus() {
        if (menus != null) {
            menus.shutdown();
            menus = null;
        }
    }
}
