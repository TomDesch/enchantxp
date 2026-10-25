package io.github.stealingdapenta.enchantxp;

import java.io.File;
import java.io.IOException;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class EnchantXpPlugin extends JavaPlugin {
    private TableMenus menus;
    private ReloadCommand reload;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            YamlConfiguration config = new YamlConfiguration();
            config.load(new File(getDataFolder(), "config.yml"));
            Settings settings = Settings.read(config);
            menus = new TableMenus(this, settings);
            getServer().getPluginManager().registerEvents(menus, this);
            reload = new ReloadCommand(this, menus, settings, new File(getDataFolder(), "config.yml"));
            getServer().getPluginManager().registerEvents(reload.editor(), this);
            PluginCommand command = getCommand("enchantxp");
            if (command == null) {
                throw new IllegalStateException("Missing enchantxp command registration");
            }
            command.setExecutor(reload);
            command.setTabCompleter(reload);
        } catch (IOException | InvalidConfigurationException | IllegalArgumentException failure) {
            getLogger().severe("EnchantXP disabled: invalid or unreadable config.yml: " + failure.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (reload != null) {
            reload.disable();
            reload = null;
        }
        if (menus != null) {
            menus.shutdown();
            menus = null;
        }
    }
}
