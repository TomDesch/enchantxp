package io.github.stealingdapenta.enchantxp.enchanting;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;

/** Shared admission rule, rechecked by the purchase workflow before charging. */
public final class EnchantAccess {
    public static final String USE_PERMISSION = "enchantxp.use";

    private EnchantAccess() {
    }

    public static boolean eligible(Player player) {
        return player.isOnline() && !player.isDead() &&
                (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.CREATIVE) &&
                player.hasPermission(USE_PERMISSION);
    }
}
