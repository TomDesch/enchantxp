package io.github.stealingdapenta.enchantxp.menu;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnchantAccessTest {
    @Test
    void eligibleRequiresConnectedLivingPermittedSurvivalOrCreativePlayer() {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(EnchantAccess.USE_PERMISSION)).thenReturn(true);
        for (GameMode mode : GameMode.values()) {
            when(player.getGameMode()).thenReturn(mode);
            assertEquals(mode == GameMode.SURVIVAL || mode == GameMode.CREATIVE, EnchantAccess.eligible(player));
        }
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.hasPermission(EnchantAccess.USE_PERMISSION)).thenReturn(false);
        assertFalse(EnchantAccess.eligible(player));
        when(player.hasPermission(EnchantAccess.USE_PERMISSION)).thenReturn(true);
        when(player.isDead()).thenReturn(true);
        assertFalse(EnchantAccess.eligible(player));
        when(player.isDead()).thenReturn(false);
        when(player.isOnline()).thenReturn(false);
        assertFalse(EnchantAccess.eligible(player));
    }
}
