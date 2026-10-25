package io.github.stealingdapenta.enchantxp.menu;

import io.github.stealingdapenta.enchantxp.configuration.Settings;
import io.github.stealingdapenta.enchantxp.presentation.MenuStage;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static io.github.stealingdapenta.enchantxp.testsupport.YamlDocuments.yaml;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MenuRendererTest {
    @Test
    void buildSortsACopyWithoutPublishingOrChangingTheSelectedItem() throws Exception {
        ServerFixture fixture = new ServerFixture();
        Settings settings = Settings.read(yaml(ServerFixture.LEGACY_PRICING + "base-xp: 25\nmax-cost: 1000000\n"));
        EnchantSession session = fixture.session();
        session.sort = EnchantSession.Sort.DESCENDING;
        ItemStack before = fixture.items.get(0).clone();
        List<Enchantment> choices = Collections.unmodifiableList(Arrays.asList(ServerFixture.SHARPNESS, ServerFixture.SMITE));
        MenuRenderer renderer = new MenuRenderer(settings.prices(), settings.policy(), settings.text(),
                new MenuLayout(3), settings.shelves());
        MenuHolder result = renderer.build(fixture.menus, fixture.player, session, MenuStage.ENCHANTMENT,
                null, 0, 0, 0, choices, Collections.emptyList());
        assertSame(ServerFixture.SMITE, result.choices.get(10));
        assertEquals(Arrays.asList(ServerFixture.SHARPNESS, ServerFixture.SMITE), choices);
        assertEquals(before, fixture.items.get(0));
        assertEquals(before, session.snapshot());
        assertNull(session.menu);
        assertNull(fixture.menuIdentity());
        verify(fixture.player, never()).openInventory(any(Inventory.class));
        verify(fixture.player, never()).closeInventory();
    }
}
