package io.github.stealingdapenta.enchantxp.menu;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/** Shared admission rule, rechecked by the purchase workflow before charging. */
final class EnchantAccess {
    static final String USE_PERMISSION = "enchantxp.use";

    private EnchantAccess() {
    }

    static boolean eligible(Player player) {
        return player.isOnline() && !player.isDead() &&
                (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.CREATIVE) &&
                player.hasPermission(USE_PERMISSION);
    }
}
