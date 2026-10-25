package io.github.stealingdapenta.enchantxp.menu;

import io.github.stealingdapenta.enchantxp.configuration.Settings;
import io.github.stealingdapenta.enchantxp.enchanting.EnchantPermissions;
import io.github.stealingdapenta.enchantxp.enchanting.Enchantments;
import io.github.stealingdapenta.enchantxp.enchanting.Experience;
import io.github.stealingdapenta.enchantxp.presentation.MenuStage;
import static io.github.stealingdapenta.enchantxp.testsupport.YamlDocuments.yaml;

import java.util.HashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import org.bukkit.Bukkit;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.permissions.ServerOperator;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EnchantPermissionsTest {
    private static final String ENABLED = ServerFixture.LEGACY_PRICING +
            "base-xp: 1\nmax-cost: 1000000\npermissions:\n  enabled: true\n";

    @Test
    void disabledFeatureRegistersNothingAndNeverQueriesLeafOrBypassesUseOrCaps() throws Exception {
        ServerFixture fixture = new ServerFixture();
        fixture.confirmPage();
        verify(fixture.player, never()).hasPermission(startsWith("enchantxp.enchant."));
        assertTrue(fixture.manager.getPermissions().isEmpty());
        fixture.click(fixture.menu().layout.confirm, ClickType.LEFT);
        fixture.tick();
        assertEquals(1, fixture.writes);
        assertFalse(Enchantments.legal(fixture.items.get(0), ServerFixture.SHARPNESS, 6));
        when(fixture.player.hasPermission(EnchantAccess.USE_PERMISSION)).thenReturn(false);
        assertFalse(EnchantAccess.eligible(fixture.player));
    }

    @Test
    void exactTargetLevelIsNonCumulativeAndDeniedTypesDisappear() throws Exception {
        ServerFixture fixture = new ServerFixture(ENABLED);
        when(fixture.player.hasPermission("enchantxp.enchant.sharpness.4")).thenReturn(true);
        fixture.open();
        assertEquals(1, fixture.menu().choices.size());
        assertTrue(fixture.menu().choices.containsValue(ServerFixture.SHARPNESS));
        fixture.click(fixture.menu().choices.keySet().iterator().next(), ClickType.LEFT);
        fixture.tick();
        assertEquals(Collections.singleton(4), new HashSet<Integer>(fixture.menu().levels.values()));
        fixture.click(fixture.menu().levels.keySet().iterator().next(), ClickType.LEFT);
        fixture.tick();
        fixture.click(fixture.menu().layout.confirm, ClickType.LEFT);
        fixture.tick();
        assertEquals(4, Enchantments.level(fixture.items.get(0), ServerFixture.SHARPNESS));
        assertEquals(984, Experience.total(fixture.level, fixture.progress));
        verify(fixture.player, atLeast(2)).hasPermission("enchantxp.enchant.sharpness.4");
        verify(fixture.player, never()).isOp();
    }

    @Test
    void allHiddenAccessLeavesItemSelectionUsableWithoutClaimingAnything() throws Exception {
        ServerFixture fixture = new ServerFixture(ENABLED);
        fixture.open();
        assertEquals(MenuStage.ITEM, fixture.menu().page);
        assertEquals(0, fixture.writes);
        when(fixture.player.hasPermission("enchantxp.enchant.unbreaking.1")).thenReturn(true);
        fixture.select(0, ClickType.LEFT);
        fixture.tick();
        assertEquals(MenuStage.ENCHANTMENT, fixture.menu().page);
        assertEquals(1, fixture.menu().choices.size());
    }

    @Test
    void realBukkitParentsGrantLeafDecisionsAndExactNegativeCanDenyOpAndWildcard() throws Exception {
        ServerFixture fixture = new ServerFixture(ENABLED);
        PermissibleBase base = permissible(fixture, false);
        assertFalse(base.hasPermission("enchantxp.enchant.sharpness.4"));
        PermissionAttachment parent = base.addAttachment(fixture.plugin, "enchantxp.enchant.sharpness.*", true);
        assertTrue(base.hasPermission("enchantxp.enchant.sharpness.4"));
        assertFalse(base.hasPermission("enchantxp.enchant.unbreaking.1"));
        base.removeAttachment(parent);
        base.addAttachment(fixture.plugin, "enchantxp.enchant.*", true);
        assertTrue(base.hasPermission("enchantxp.enchant.unbreaking.1"));
        base.addAttachment(fixture.plugin, "enchantxp.enchant.sharpness.4", false);
        assertFalse(base.hasPermission("enchantxp.enchant.sharpness.4"));
        assertTrue(base.hasPermission("enchantxp.enchant.sharpness.3"));
        fixture.open();
        int slot = fixture.menu().choices.entrySet().stream()
                .filter(entry -> entry.getValue() == ServerFixture.SHARPNESS).findFirst().get().getKey();
        fixture.click(slot, ClickType.LEFT);
        fixture.tick();
        assertFalse(fixture.menu().levels.containsValue(4));
        assertTrue(fixture.menu().levels.containsValue(5));
        PermissibleBase op = permissible(fixture, true);
        assertTrue(op.hasPermission("enchantxp.enchant.sharpness.4"));
        op.addAttachment(fixture.plugin, "enchantxp.enchant.sharpness.4", false);
        assertFalse(op.hasPermission("enchantxp.enchant.sharpness.4"));
        assertEquals(PermissionDefault.OP, fixture.manager.getPermission("enchantxp.enchant.sharpness.4").getDefault());
    }

    @Test
    void capRaisesGrowOwnedNodesAndRefreshExistingParentAttachmentsWithoutRelog() throws Exception {
        ServerFixture fixture = new ServerFixture(ENABLED);
        assertTrue(fixture.manager.getPermissions().size() < 200);
        PermissibleBase base = permissible(fixture, false);
        base.addAttachment(fixture.plugin, "enchantxp.enchant.*", true);
        assertFalse(base.hasPermission("enchantxp.enchant.sharpness.12"));
        fixture.menus.replace(Settings.read(yaml(ENABLED +
                "items:\n  DIAMOND_SWORD:\n    max-levels:\n      DAMAGE_ALL: 12\n")));
        assertTrue(base.hasPermission("enchantxp.enchant.sharpness.12"));
        Permission retained = fixture.manager.getPermission("enchantxp.enchant.sharpness.12");
        fixture.menus.replace(Settings.read(yaml(ENABLED)));
        assertSame(retained, fixture.manager.getPermission("enchantxp.enchant.sharpness.12"));
        fixture.open();
        fixture.click(fixture.menu().choices.entrySet().stream()
                .filter(entry -> entry.getValue() == ServerFixture.SHARPNESS).findFirst().get().getKey(), ClickType.LEFT);
        fixture.tick();
        assertFalse(fixture.menu().levels.containsValue(12));
        fixture.menus.replace(Settings.read(yaml(ENABLED.replace("enabled: true", "enabled: false"))));
        assertSame(retained, fixture.manager.getPermission(retained.getName()));
        fixture.menus.shutdown();
        assertTrue(fixture.manager.getPermissions().isEmpty());
        assertTrue(fixture.manager.getDefaultPermissions(true).isEmpty());
        assertTrue(fixture.manager.getDefaultPermissions(false).isEmpty());
        assertFalse(base.isPermissionSet("enchantxp.enchant.sharpness.12"));
    }

    @Test
    void firstEnableRefreshesWildcardAttachmentsThatPredatePermissionRegistration() throws Exception {
        ServerFixture fixture = new ServerFixture();
        PermissibleBase base = permissible(fixture, false);
        base.addAttachment(fixture.plugin, "enchantxp.enchant.*", true);
        assertTrue(base.isPermissionSet("enchantxp.enchant.*"));
        assertFalse(base.hasPermission("enchantxp.enchant.sharpness.4"));
        fixture.menus.replace(Settings.read(yaml(ENABLED)));
        assertTrue(base.hasPermission("enchantxp.enchant.sharpness.4"));
        fixture.open();
        assertTrue(fixture.menu().choices.containsValue(ServerFixture.SHARPNESS));
    }

    @Test
    void foreignNamespaceConflictFailsBeforeMenuInvalidationAndNeverMutatesForeignPermission() throws Exception {
        ServerFixture fixture = new ServerFixture();
        fixture.confirmPage();
        MenuHolder original = fixture.menu();
        Permission foreign = new Permission("enchantxp.enchant.sharpness.1", PermissionDefault.TRUE);
        foreign.getChildren().put("foreign.child", true);
        fixture.manager.addPermission(foreign);
        fixture.click(original.layout.confirm, ClickType.LEFT);
        assertThrows(IllegalArgumentException.class, () -> fixture.menus.replace(Settings.read(yaml(ENABLED))));
        assertSame(original, fixture.menu());
        assertEquals(1, fixture.manager.getPermissions().size());
        assertEquals(PermissionDefault.TRUE, foreign.getDefault());
        assertEquals(Boolean.TRUE, foreign.getChildren().get("foreign.child"));
        fixture.tick();
        assertEquals(1, fixture.writes);
        assertSame(foreign, fixture.manager.getPermission(foreign.getName()));
    }

    @Test
    void registrationFailureRollsBackNewNodesParentMapsAndResolvedDecisions() throws Exception {
        ServerFixture fixture = new ServerFixture();
        PluginManager manager = spy(fixture.manager);
        when(Bukkit.getServer().getPluginManager()).thenReturn(manager);
        EnchantPermissions.Registry registry = new EnchantPermissions.Registry(manager);
        Settings original = Settings.read(yaml(ENABLED));
        registry.prepare(original.permissions(), original.policy());
        Set<Permission> before = new HashSet<Permission>(manager.getPermissions());
        Map<String, Boolean> children = new HashMap<String, Boolean>(
                manager.getPermission("enchantxp.enchant.sharpness.*").getChildren());
        PermissibleBase base = permissible(fixture, false);
        base.addAttachment(fixture.plugin, "enchantxp.enchant.sharpness.*", true);
        doAnswer(call -> {
            Permission permission = call.getArgument(0);
            call.callRealMethod();
            if (permission.getName().equals("enchantxp.enchant.sharpness.8")) {
                throw new IllegalArgumentException("publication failed after add");
            }
            return null;
        }).when(manager).addPermission(any(Permission.class));
        Settings raised = Settings.read(yaml(ENABLED +
                "items:\n  DIAMOND_SWORD:\n    max-levels:\n      DAMAGE_ALL: 12\n"));
        assertThrows(IllegalArgumentException.class, () -> registry.prepare(raised.permissions(), raised.policy()));
        assertEquals(before, manager.getPermissions());
        assertEquals(children, manager.getPermission("enchantxp.enchant.sharpness.*").getChildren());
        assertFalse(base.isPermissionSet("enchantxp.enchant.sharpness.8"));
        assertTrue(base.hasPermission("enchantxp.enchant.sharpness.5"));
        assertTrue(manager.getDefaultPermissions(true).stream().noneMatch(p -> p.getName().endsWith(".8")));
        registry.shutdown();
        assertTrue(manager.getPermissions().isEmpty());
    }

    @Test
    void revokedLeafRefreshesLevelsWithoutChargingAndLaterGrantCanRetry() throws Exception {
        ServerFixture fixture = new ServerFixture(ENABLED);
        when(fixture.player.hasPermission("enchantxp.enchant.sharpness.2")).thenReturn(true);
        fixture.open();
        fixture.click(fixture.menu().choices.keySet().iterator().next(), ClickType.LEFT);
        fixture.tick();
        fixture.click(fixture.menu().levels.keySet().iterator().next(), ClickType.LEFT);
        fixture.tick();
        when(fixture.player.hasPermission("enchantxp.enchant.sharpness.2")).thenReturn(false);
        fixture.click(fixture.menu().layout.confirm, ClickType.LEFT);
        fixture.tick();
        assertEquals(MenuStage.LEVEL, fixture.menu().page);
        assertTrue(fixture.menu().levels.isEmpty());
        assertEquals(0, fixture.writes);
        when(fixture.player.hasPermission("enchantxp.enchant.sharpness.2")).thenReturn(true);
        fixture.click(fixture.menu().layout.sort, ClickType.LEFT);
        assertTrue(fixture.tasks.isEmpty());
        fixture.click(fixture.menu().layout.back, ClickType.LEFT);
        fixture.tick();
        fixture.click(fixture.menu().choices.keySet().iterator().next(), ClickType.LEFT);
        fixture.tick();
        assertEquals(Collections.singleton(2), new HashSet<Integer>(fixture.menu().levels.values()));
    }

    @Test
    void shutdownPreservesForeignReplacementInstanceAndCleansOwnedDefaultGhost() throws Exception {
        ServerFixture fixture = new ServerFixture(ENABLED);
        String name = "enchantxp.enchant.sharpness.2";
        Permission old = fixture.manager.getPermission(name);
        fixture.manager.removePermission(old);
        Permission foreign = new Permission(name, PermissionDefault.TRUE);
        foreign.getChildren().put("foreign.retained", true);
        fixture.manager.addPermission(foreign);
        assertTrue(fixture.manager.getDefaultPermissions(true).contains(old));
        fixture.menus.shutdown();
        assertSame(foreign, fixture.manager.getPermission(name));
        assertEquals(PermissionDefault.TRUE, foreign.getDefault());
        assertEquals(Collections.singletonMap("foreign.retained", true), foreign.getChildren());
        assertFalse(fixture.manager.getDefaultPermissions(true).contains(old));
        assertEquals(Collections.singleton(foreign), fixture.manager.getPermissions());
    }

    @Test
    void shutdownCleansDefaultGhostLeftByExternallyRemovedOwnedLeaf() throws Exception {
        ServerFixture fixture = new ServerFixture(ENABLED);
        Permission owned = fixture.manager.getPermission("enchantxp.enchant.sharpness.2");
        fixture.manager.removePermission(owned);
        assertTrue(fixture.manager.getDefaultPermissions(true).contains(owned));
        fixture.menus.shutdown();
        assertTrue(fixture.manager.getDefaultPermissions(true).isEmpty());
        assertTrue(fixture.manager.getPermissions().isEmpty());
    }

    private static PermissibleBase permissible(ServerFixture fixture, boolean op) {
        when(fixture.plugin.isEnabled()).thenReturn(true);
        ServerOperator operator = mock(ServerOperator.class);
        when(operator.isOp()).thenReturn(op);
        PermissibleBase base = new PermissibleBase(operator);
        base.addAttachment(fixture.plugin, EnchantAccess.USE_PERMISSION, true);
        when(fixture.player.hasPermission(anyString())).thenAnswer(call -> base.hasPermission((String) call.getArgument(0)));
        return base;
    }
}
