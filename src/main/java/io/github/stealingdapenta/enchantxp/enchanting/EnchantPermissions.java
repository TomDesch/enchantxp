package io.github.stealingdapenta.enchantxp.enchanting;

import io.github.stealingdapenta.enchantxp.configuration.values.ConfigValues;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

/**
 * Queries only the resolved exact leaf; wildcard and negative precedence belong to the server.
 */
public final class EnchantPermissions {

    private static final String ROOT = "enchantxp.enchant.";
    private static final Map<String, String> SLUGS;

    static {
        String[] names = {"PROTECTION_ENVIRONMENTAL", "PROTECTION_FIRE", "PROTECTION_FALL", "PROTECTION_EXPLOSIONS", "PROTECTION_PROJECTILE", "OXYGEN", "WATER_WORKER", "THORNS", "DEPTH_STRIDER", "FROST_WALKER", "BINDING_CURSE", "DAMAGE_ALL",
                          "DAMAGE_UNDEAD", "DAMAGE_ARTHROPODS", "KNOCKBACK", "FIRE_ASPECT", "LOOT_BONUS_MOBS", "SWEEPING_EDGE", "DIG_SPEED", "SILK_TOUCH", "DURABILITY", "LOOT_BONUS_BLOCKS", "ARROW_DAMAGE", "ARROW_KNOCKBACK", "ARROW_FIRE",
                          "ARROW_INFINITE", "LUCK", "LURE", "MENDING", "VANISHING_CURSE"};
        String[] slugs = {"protection", "fire_protection", "feather_falling", "blast_protection", "projectile_protection", "respiration", "aqua_affinity", "thorns", "depth_strider", "frost_walker", "binding_curse", "sharpness", "smite",
                          "bane_of_arthropods", "knockback", "fire_aspect", "looting", "sweeping_edge", "efficiency", "silk_touch", "unbreaking", "fortune", "power", "punch", "flame", "infinity", "luck_of_the_sea", "lure", "mending",
                          "vanishing_curse"};
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (int i = 0; i < names.length; i++) {
            values.put(names[i], slugs[i]);
        }
        SLUGS = Collections.unmodifiableMap(values);
    }

    private final boolean enabled;

    private EnchantPermissions(boolean enabled) {
        this.enabled = enabled;
    }

    public static EnchantPermissions read(ConfigurationSection config) {
        ConfigValues.keys(ConfigValues.section(config, "permissions"), "enabled");
        return new EnchantPermissions(ConfigValues.bool(config, ConfigValues.defaults(), "permissions.enabled"));
    }

    public static EnchantPermissions disabled() {
        return new EnchantPermissions(false);
    }

    public boolean allows(Player player, Enchantment enchantment, int level) {
        return !enabled || player.hasPermission(leaf(enchantment, level));
    }

    static String leaf(Enchantment enchantment, int level) {
        return ROOT + SLUGS.get(enchantment.getName()) + "." + level;
    }

    /**
     * Retained owned nodes grow on valid cap raises; lowering/off only changes purchase policy.
     */
    public static final class Registry {

        private final PluginManager manager;
        private final Map<String, Permission> owned = new LinkedHashMap<>();

        public Registry(PluginManager manager) {
            this.manager = manager;
        }

        public void prepare(EnchantPermissions settings, EnchantPolicy policy) {
            if (!settings.enabled) {
                return;
            }
            validate(settings);
            Map<String, Map<String, Boolean>> desired = desiredPermissions(policy);
            validate(settings);
            validateDesiredNames(desired.keySet());
            publishPermissions(desired);
        }

        private Map<String, Map<String, Boolean>> desiredPermissions(EnchantPolicy policy) {
            Map<String, Map<String, Boolean>> desired = new LinkedHashMap<>();
            Map<String, Boolean> root = new LinkedHashMap<>();
            desired.put(ROOT + "*", root);
            for (Map.Entry<String, String> entry : SLUGS.entrySet()) {
                Enchantment enchantment = Enchantment.getByName(entry.getKey());
                String parent = ROOT + entry.getValue() + ".*";
                root.put(parent, true);
                Map<String, Boolean> children = new LinkedHashMap<>();
                desired.put(parent, children);
                if (enchantment != null) {
                    int maximum = policy.registryMaximum(enchantment);
                    for (int level = enchantment.getStartLevel(); level <= maximum; level++) {
                        String leaf = leaf(enchantment, level);
                        children.put(leaf, true);
                        desired.put(leaf, Collections.emptyMap());
                    }
                }
            }
            return desired;
        }

        private void validateDesiredNames(Set<String> names) {
            for (String name : names) {
                Permission current = manager.getPermission(name);
                if (current != null && owned.get(name) != current) {
                    throw new IllegalArgumentException("permissions.enabled conflicts with foreign permission " + name);
                }
            }
        }

        private void publishPermissions(Map<String, Map<String, Boolean>> desired) {
            Map<Permission, Map<String, Boolean>> previous = new HashMap<>();
            List<Permission> added = new ArrayList<>();
            try {
                for (Map.Entry<String, Map<String, Boolean>> entry : desired.entrySet()) {
                    Permission permission = registerOwned(entry.getKey(), added);
                    growChildren(permission, entry.getValue(), previous);
                }
            } catch (RuntimeException failure) {
                rollback(previous, added);
                throw failure;
            }
        }

        private Permission registerOwned(String name, List<Permission> added) {
            Permission permission = owned.get(name);
            if (permission == null) {
                permission = new Permission(name, name.endsWith(".*") ? PermissionDefault.FALSE : PermissionDefault.OP);
                // Track before add: a manager may publish the permission and then throw.
                added.add(permission);
                manager.addPermission(permission);
                owned.put(name, permission);
            }
            return permission;
        }

        private void growChildren(Permission permission, Map<String, Boolean> children, Map<Permission, Map<String, Boolean>> previous) {
            if (!permission.getChildren()
                           .entrySet()
                           .containsAll(children.entrySet())) {
                previous.put(permission, new LinkedHashMap<>(permission.getChildren()));
                permission.getChildren()
                          .putAll(children);
                permission.recalculatePermissibles();
            }
        }

        private void rollback(Map<Permission, Map<String, Boolean>> previous, List<Permission> added) {
            for (Map.Entry<Permission, Map<String, Boolean>> entry : previous.entrySet()) {
                entry.getKey()
                     .getChildren()
                     .clear();
                entry.getKey()
                     .getChildren()
                     .putAll(entry.getValue());
            }
            remove(added);
            for (Permission permission : previous.keySet()) {
                permission.recalculatePermissibles();
            }
        }

        public void shutdown() {
            remove(new ArrayList<Permission>(owned.values()));
        }

        public void validate(EnchantPermissions settings) {
            if (!settings.enabled) {
                return;
            }
            for (Permission permission : manager.getPermissions()) {
                if (permission.getName()
                              .startsWith(ROOT) && owned.get(permission.getName()) != permission) {
                    throw new IllegalArgumentException("permissions.enabled conflicts with foreign permission " + permission.getName());
                }
            }
            for (Permission permission : owned.values()) {
                if (manager.getPermission(permission.getName()) != permission) {
                    throw new IllegalArgumentException("Owned permission was replaced or removed: " + permission.getName());
                }
            }
        }

        private void remove(List<Permission> permissions) {
            Set<Permissible> subscribers = new HashSet<Permissible>();
            for (Permission permission : permissions) {
                subscribers.addAll(permission.getPermissibles());
                subscribers.addAll(manager.getDefaultPermSubscriptions(true));
                subscribers.addAll(manager.getDefaultPermSubscriptions(false));
                if (manager.getPermission(permission.getName()) == null && (manager.getDefaultPermissions(true)
                                                                                   .contains(permission) || manager.getDefaultPermissions(false)
                                                                                                                   .contains(permission))) {
                    // Reattach only our own orphan long enough for the legacy API to clear its defaults.
                    manager.addPermission(permission);
                }
                if (manager.getPermission(permission.getName()) != permission) {
                    if (manager.getPermission(permission.getName()) != null) {
                        // A replacement may leave this owned instance in legacy default sets.
                        permission.getChildren()
                                  .clear();
                        permission.setDefault(PermissionDefault.FALSE);
                    }
                    owned.remove(permission.getName(), permission);
                    continue;
                }
                permission.getChildren()
                          .clear();
                // Legacy removePermission only removes the map entry. Clear default sets while registered.
                permission.setDefault(PermissionDefault.FALSE);
                manager.removePermission(permission);
                owned.remove(permission.getName(), permission);
            }
            for (Permissible subscriber : subscribers) {
                subscriber.recalculatePermissions();
            }
        }
    }
}
