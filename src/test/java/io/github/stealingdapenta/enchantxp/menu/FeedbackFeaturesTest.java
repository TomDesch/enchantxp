package io.github.stealingdapenta.enchantxp.menu;

import io.github.stealingdapenta.enchantxp.configuration.Settings;
import io.github.stealingdapenta.enchantxp.configuration.values.ConfigValues;
import io.github.stealingdapenta.enchantxp.enchanting.BookshelfPricing;
import io.github.stealingdapenta.enchantxp.enchanting.Enchantments;
import io.github.stealingdapenta.enchantxp.enchanting.Experience;
import io.github.stealingdapenta.enchantxp.presentation.MenuStage;
import static io.github.stealingdapenta.enchantxp.testsupport.YamlDocuments.yaml;

import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FeedbackFeaturesTest {
    @Test
    void selectionAcknowledgesBeforeShelfAndPermissionWorkWithOneImmediateDefaultSound() throws Exception {
        ServerFixture fixture = new ServerFixture("base-xp: 25\nmax-cost: 1000000\npermissions:\n  enabled: true\n");
        when(fixture.player.hasPermission("enchantxp.enchant.sharpness.1")).thenReturn(true);
        fixture.openTable();
        MenuHolder original = fixture.menu();
        clearInvocations(fixture.player, fixture.world, fixture.scheduler);
        for (ClickType click : new ClickType[]{ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT}) {
            fixture.select(0, click);
        }
        assertSame(original, fixture.menu());
        verify(fixture.player, times(1)).playSound(any(Location.class), eq(Sound.UI_BUTTON_CLICK), eq(0.5f), eq(1.0f));
        verify(fixture.player, never()).hasPermission(startsWith("enchantxp.enchant."));
        verify(fixture.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        InOrder order = inOrder(fixture.player, fixture.scheduler, fixture.world);
        order.verify(fixture.player).playSound(any(Location.class), eq(Sound.UI_BUTTON_CLICK), eq(0.5f), eq(1.0f));
        order.verify(fixture.scheduler).runTask(eq(fixture.plugin), any(Runnable.class));
        fixture.tick();
        assertEquals(MenuStage.ENCHANTMENT, fixture.menu().page);
        order.verify(fixture.world, atLeastOnce()).getBlockAt(anyInt(), anyInt(), anyInt());
        order.verify(fixture.player).openInventory(any(Inventory.class));
        verify(fixture.player, times(1)).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
        assertEquals(0, fixture.writes);
    }

    @Test
    void explicitLegacyClickPitchIsPreserved() throws Exception {
        ServerFixture fixture = new ServerFixture(ServerFixture.LEGACY_PRICING +
                "base-xp: 25\nmax-cost: 1000000\nsounds:\n  click:\n    pitch: 0.5\n");
        fixture.openTable();
        fixture.select(0, ClickType.LEFT);
        verify(fixture.player).playSound(any(Location.class), eq(Sound.UI_BUTTON_CLICK), eq(0.5f), eq(0.5f));
    }

    @Test
    void permissionFilteredZeroOneAndMultipleChoicesControlSortOnBothLists() throws Exception {
        for (boolean secondType : new boolean[]{false, true}) {
            ServerFixture fixture = new ServerFixture(ServerFixture.LEGACY_PRICING +
                    "base-xp: 1\nmax-cost: 1000000\npermissions:\n  enabled: true\n");
            when(fixture.player.hasPermission("enchantxp.enchant.sharpness.4")).thenReturn(true);
            when(fixture.player.hasPermission("enchantxp.enchant.unbreaking.1")).thenReturn(secondType);
            fixture.open();
            assertEquals(secondType ? 2 : 1, fixture.menu().choices.size());
            assertSortAvailability(fixture, secondType);
            int sharpness = fixture.menu().choices.entrySet().stream()
                    .filter(entry -> entry.getValue() == ServerFixture.SHARPNESS).findFirst().get().getKey();
            fixture.click(sharpness, ClickType.LEFT);
            fixture.tick();
            assertEquals(Collections.singleton(4), new HashSet<Integer>(fixture.menu().levels.values()));
            assertSortAvailability(fixture, false);
            fixture.click(fixture.menu().levels.keySet().iterator().next(), ClickType.LEFT);
            fixture.tick();
            when(fixture.player.hasPermission("enchantxp.enchant.sharpness.4")).thenReturn(false);
            fixture.click(fixture.menu().layout.back, ClickType.LEFT);
            fixture.tick();
            assertTrue(fixture.menu().levels.isEmpty());
            assertSortAvailability(fixture, false);
            fixture.click(fixture.menu().layout.back, ClickType.LEFT);
            fixture.tick();
            assertEquals(secondType ? 1 : 0, fixture.menu().choices.size());
            assertSortAvailability(fixture, false);
            assertEquals(0, fixture.writes);
        }
    }

    @Test
    void capsPricesAndBookshelfQuotesCanEachLeaveOnlyOneSortableListChoice() throws Exception {
        for (String config : new String[]{
                ServerFixture.LEGACY_PRICING + "base-xp: 25\nmax-cost: 1000000\nenchantments:\n" +
                        "  DAMAGE_ALL:\n    max-level: 1\n  DAMAGE_UNDEAD:\n    enabled: false\n  DURABILITY:\n    max-level: 0\n",
                ServerFixture.LEGACY_PRICING + "base-xp: 25\nmax-cost: 25\nenchantments:\n" +
                        "  DAMAGE_UNDEAD:\n    base-xp: 100\n  DURABILITY:\n    base-xp: 100\n",
                "base-xp: 10\nmax-cost: 25\nenchantments:\n" +
                        "  DAMAGE_UNDEAD:\n    base-xp: 20\n  DURABILITY:\n    base-xp: 20\n"}) {
            ServerFixture fixture = new ServerFixture(config);
            fixture.open();
            assertEquals(1, fixture.menu().choices.size());
            assertSortAvailability(fixture, false);
            fixture.click(fixture.menu().choices.keySet().iterator().next(), ClickType.LEFT);
            fixture.tick();
            assertEquals(1, fixture.menu().levels.size());
            assertSortAvailability(fixture, false);
        }
    }

    @Test
    void singletonLastPagesStillSortTheEntireEnchantmentAndLevelLists() throws Exception {
        ServerFixture fixture = new ServerFixture(ServerFixture.LEGACY_PRICING +
                "base-xp: 1\nmax-cost: 1000000\nui:\n  rows: 3\npermissions:\n  enabled: true\n" +
                "enchantments:\n  DAMAGE_ALL:\n    max-level: 8\n");
        fixture.items.put(0, new ItemStack(Material.BOOK));
        for (String slug : Arrays.asList("power", "flame", "infinity", "punch",
                "binding_curse", "sharpness", "bane_of_arthropods", "smite")) {
            when(fixture.player.hasPermission("enchantxp.enchant." + slug + ".1"))
                    .thenReturn(true);
        }
        for (int level = 1; level <= 8; level++) {
            when(fixture.player.hasPermission("enchantxp.enchant.sharpness." + level)).thenReturn(true);
        }
        // DAMAGE_ALL is among the first eight canonical names.
        fixture.open();
        fixture.click(fixture.menu().layout.next, ClickType.LEFT);
        fixture.tick();
        assertEquals(1, fixture.menu().choices.size());
        assertSortAvailability(fixture, true);
        fixture.click(fixture.menu().layout.sort, ClickType.LEFT);
        fixture.tick();
        assertEquals(0, fixture.menu().index);
        while (!fixture.menu().choices.containsValue(ServerFixture.SHARPNESS)) {
            fixture.click(fixture.menu().layout.next, ClickType.LEFT);
            fixture.tick();
        }
        fixture.click(fixture.menu().choices.entrySet().stream()
                .filter(entry -> entry.getValue() == ServerFixture.SHARPNESS).findFirst().get().getKey(), ClickType.LEFT);
        fixture.tick();
        fixture.click(fixture.menu().layout.next, ClickType.LEFT);
        fixture.tick();
        assertEquals(1, fixture.menu().levels.size());
        assertSortAvailability(fixture, true);
        fixture.click(fixture.menu().layout.sort, ClickType.LEFT);
        fixture.tick();
        assertEquals(0, fixture.menu().index);
        assertEquals(8, fixture.menu().levels.get(fixture.menu().layout.contentSlot(0)));
    }

    private void assertSortAvailability(ServerFixture fixture, boolean expected) {
        assertEquals(expected ? Material.HOPPER : Material.STAINED_GLASS_PANE,
                fixture.view.getTopInventory().getItem(fixture.menu().layout.sort).getType());
        if (!expected) {
            MenuHolder original = fixture.menu();
            clearInvocations(fixture.player);
            fixture.click(original.layout.sort, ClickType.LEFT);
            assertTrue(fixture.tasks.isEmpty());
            assertSame(original, fixture.menu());
            verify(fixture.player, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
        }
    }

    @Test
    void acceptedClickSoundsImmediatelyBeforeDeferredNavigationAndNeverTwice() throws Exception {
        ServerFixture fixture = new ServerFixture();
        fixture.openTable();
        MenuHolder old = fixture.menu();
        clearInvocations(fixture.player);
        fixture.select(0, ClickType.LEFT);
        assertSame(old, fixture.menu());
        verify(fixture.player, times(1)).playSound(any(Location.class), eq(Sound.UI_BUTTON_CLICK), anyFloat(), anyFloat());
        fixture.select(0, ClickType.LEFT);
        fixture.click(0, ClickType.LEFT);
        verify(fixture.player, times(1)).playSound(any(Location.class), eq(Sound.UI_BUTTON_CLICK), anyFloat(), anyFloat());
        fixture.tick();
        assertEquals(MenuStage.ENCHANTMENT, fixture.menu().page);
        verify(fixture.player, times(1)).playSound(any(Location.class), eq(Sound.UI_BUTTON_CLICK), anyFloat(), anyFloat());
        for (int slot : new int[]{fixture.menu().choices.keySet().iterator().next(), TableMenus.SORT_SLOT, TableMenus.BACK_SLOT}) {
            old = fixture.menu();
            clearInvocations(fixture.player);
            fixture.click(slot, ClickType.LEFT);
            fixture.click(slot, ClickType.LEFT);
            assertSame(old, fixture.menu());
            verify(fixture.player, times(1)).playSound(any(Location.class), eq(Sound.UI_BUTTON_CLICK), anyFloat(), anyFloat());
            fixture.tick();
            verify(fixture.player, times(1)).playSound(any(Location.class), eq(Sound.UI_BUTTON_CLICK), anyFloat(), anyFloat());
        }
    }

    @Test
    void immediateBackAndCancelStillFenceConfirmationAndConfirmationHasOnlyOutcomeSound() throws Exception {
        for (boolean cancel : new boolean[]{false, true}) {
            ServerFixture fixture = new ServerFixture();
            fixture.confirmPage();
            MenuHolder old = fixture.menu();
            clearInvocations(fixture.player);
            fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
            verify(fixture.player, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
            fixture.click(cancel ? TableMenus.CANCEL_SLOT : TableMenus.BACK_SLOT, ClickType.LEFT);
            verify(fixture.player, times(1)).playSound(any(Location.class), eq(Sound.UI_BUTTON_CLICK), anyFloat(), anyFloat());
            assertSame(old, fixture.menu());
            fixture.tick();
            assertEquals(0, fixture.writes);
            verify(fixture.player, times(1)).playSound(any(Location.class), eq(Sound.UI_BUTTON_CLICK), anyFloat(), anyFloat());
            verify(fixture.player, never()).playSound(any(Location.class), eq(Sound.BLOCK_ENCHANTMENT_TABLE_USE), anyFloat(), anyFloat());
        }

    }

    @Test
    void clickSoundApiFailureCannotLetAnAlreadyQueuedConfirmationEscapeCancellation() throws Exception {
        ServerFixture fixture = new ServerFixture();
        fixture.confirmPage();
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        doThrow(new IllegalStateException("Sound API failed")).when(fixture.player)
                .playSound(any(Location.class), eq(Sound.UI_BUTTON_CLICK), anyFloat(), anyFloat());
        assertDoesNotThrow(() -> fixture.click(TableMenus.BACK_SLOT, ClickType.LEFT));
        fixture.tick();
        assertEquals(0, fixture.writes);
        assertEquals(MenuStage.LEVEL, fixture.menu().page);
    }

    @Test
    void itemAndConfirmationSortSlotsAreInertFillers() throws Exception {
        ServerFixture fixture = new ServerFixture();
        fixture.openTable();
        assertEquals(Material.STAINED_GLASS_PANE, fixture.view.getTopInventory()
                .getItem(fixture.menu().layout.sort).getType());
        fixture.click(fixture.menu().layout.sort, ClickType.LEFT);
        assertTrue(fixture.tasks.isEmpty());
        fixture.confirmPage();
        fixture.click(fixture.menu().layout.sort, ClickType.LEFT);
        assertTrue(fixture.tasks.isEmpty());
        assertEquals(Material.STAINED_GLASS_PANE, fixture.view.getTopInventory()
                .getItem(fixture.menu().layout.sort).getType());
    }

    @Test
    void creativeInvalidXpCanPurchaseWithoutReadingOrWritingXp() throws Exception {
        for (Material material : new Material[]{Material.DIAMOND_SWORD, Material.ENCHANTED_BOOK, Material.BOOK}) {
            ServerFixture fixture = new ServerFixture();
            when(fixture.player.getGameMode()).thenReturn(GameMode.CREATIVE);
            when(fixture.player.getLevel()).thenThrow(new AssertionError("Creative must not read XP"));
            when(fixture.player.getExp()).thenThrow(new AssertionError("Creative must not read XP"));
            fixture.items.put(0, new ItemStack(material, material == Material.BOOK ? 64 : 1));
            fixture.confirmPage();
            assertEquals(0, fixture.menu().quote.cost);
            assertEquals("Balance: Unlimited XP", ChatColor.stripColor(fixture.view.getTopInventory()
                    .getItem(fixture.menu().layout.info).getItemMeta().getDisplayName()));
            fixture.click(fixture.menu().layout.confirm, ClickType.LEFT);
            fixture.tick();
            assertEquals(2, Enchantments.level(fixture.items.get(material == Material.BOOK ? 1 : 0), ServerFixture.SHARPNESS));
            if (material == Material.BOOK) { assertEquals(63, fixture.items.get(0).getAmount()); }
            verify(fixture.player, never()).setLevel(anyInt());
            verify(fixture.player, never()).setExp(anyFloat());
            verify(fixture.player, never()).setTotalExperience(anyInt());
        }
    }

    @Test
    void insufficientBarrierStaysUsableAndNeedsExplicitAffordableConfirmation() throws Exception {
        ServerFixture fixture = new ServerFixture();
        fixture.balance(0);
        fixture.confirmPage();
        int slot = fixture.menu().layout.confirm;
        assertEquals(Material.BARRIER, fixture.view.getTopInventory().getItem(slot).getType());
        assertEquals("Can't Confirm", ChatColor.stripColor(fixture.view.getTopInventory().getItem(slot).getItemMeta().getDisplayName()));
        String lore = ChatColor.stripColor(String.join("\n", fixture.view.getTopInventory().getItem(slot).getItemMeta().getLore()));
        assertTrue(lore.contains("Sharpness 2"));
        assertTrue(lore.contains("Price: 100 raw XP"));
        assertTrue(lore.contains("Missing: 100 raw XP"));
        fixture.click(slot, ClickType.LEFT);
        fixture.tick();
        assertNotNull(fixture.menu());
        assertEquals(0, fixture.writes);
        verify(fixture.player).playSound(any(Location.class), eq(Sound.ENTITY_VILLAGER_NO), anyFloat(), anyFloat());
        fixture.balance(1000);
        fixture.click(slot, ClickType.LEFT);
        fixture.tick();
        assertEquals(Material.EMERALD, fixture.view.getTopInventory().getItem(slot).getType());
        assertEquals(0, fixture.writes);
        fixture.click(slot, ClickType.LEFT);
        fixture.tick();
        assertEquals(1, fixture.writes);
    }

    @Test
    void prefixIsAutomaticAndLegacyDefaultIsMigratedOnlyInMemory() throws Exception {
        ServerFixture fixture = new ServerFixture();
        Settings settings = Settings.read(yaml("base-xp: 25\nmax-cost: 1000000\n" +
                "messages:\n  prefix: 'Prefix '\n  context: 'Body'\n" +
                "  success: '&5EnchantedXP &8> &aConfirmed {enchantment} {level} for {cost} XP.'\n"));
        settings.text().send(fixture.player, "context", Collections.emptyMap());
        verify(fixture.player).sendMessage("Prefix Body");
        settings.text().send(fixture.player, "success", Collections.emptyMap());
        verify(fixture.player).sendMessage("Prefix " + ChatColor.GREEN + "Confirmed   for  XP.");
    }

    @Test
    void modeChangesRefreshDisplayedQuoteAndNeverChargeTheFirstClick() throws Exception {
        for (GameMode initial : new GameMode[]{GameMode.SURVIVAL, GameMode.CREATIVE}) {
            ServerFixture fixture = new ServerFixture();
            when(fixture.player.getGameMode()).thenReturn(initial);
            fixture.confirmPage();
            GameMode next = initial == GameMode.CREATIVE ? GameMode.SURVIVAL : GameMode.CREATIVE;
            when(fixture.player.getGameMode()).thenReturn(next);
            fixture.click(fixture.menu().layout.confirm, ClickType.LEFT);
            fixture.tick();
            assertEquals(next, fixture.menu().quote.mode);
            assertEquals(next == GameMode.CREATIVE ? 0 : 100, fixture.menu().quote.cost);
            assertEquals(0, fixture.writes);
            verify(fixture.player, never()).setLevel(anyInt());
            fixture.click(fixture.menu().layout.confirm, ClickType.LEFT);
            fixture.tick();
            assertEquals(1, fixture.writes);
            assertEquals(next == GameMode.CREATIVE ? 1000 : 900, Experience.total(fixture.level, fixture.progress));
        }
    }

    @Test
    void creativeRollbackDoesNotReadOrWriteXpEvenWhenPublishingMutatesThenThrows() throws Exception {
        for (Material material : new Material[]{Material.DIAMOND_SWORD, Material.BOOK}) {
            ServerFixture fixture = new ServerFixture();
            when(fixture.player.getGameMode()).thenReturn(GameMode.CREATIVE);
            when(fixture.player.getLevel()).thenThrow(new AssertionError("No creative XP read"));
            when(fixture.player.getExp()).thenThrow(new AssertionError("No creative XP read"));
            fixture.items.put(0, new ItemStack(material, material == Material.BOOK ? 2 : 1));
            ItemStack original = fixture.items.get(0).clone();
            AtomicBoolean once = new AtomicBoolean(true);
            doAnswer(call -> {
                fixture.items.put(call.getArgument(0), call.getArgument(1));
                if (once.getAndSet(false)) {
                    throw new IllegalStateException("Failed after item mutation");
                }
                return null;
            }).when(fixture.inventory).setItem(anyInt(), nullable(ItemStack.class));
            assertThrows(IllegalStateException.class, () -> new EnchantTransaction(fixture.prices).commit(
                    fixture.player, fixture.session(), ServerFixture.SHARPNESS, 2, () -> true));
            assertEquals(original, fixture.items.get(0));
            assertNull(fixture.items.get(1));
            verify(fixture.player, never()).setLevel(anyInt());
            verify(fixture.player, never()).setExp(anyFloat());
            verify(fixture.player, never()).setTotalExperience(anyInt());
        }
    }

    @Test
    void survivalXpFailureRefundsEvenIfTheFailingSetterChangesToCreative() throws Exception {
        ServerFixture fixture = new ServerFixture();
        AtomicBoolean once = new AtomicBoolean(true);
        doAnswer(call -> {
            fixture.level = call.getArgument(0);
            if (once.getAndSet(false)) {
                when(fixture.player.getGameMode()).thenReturn(GameMode.CREATIVE);
                throw new IllegalStateException("XP setter changed mode and failed");
            }
            return null;
        }).when(fixture.player).setLevel(anyInt());
        assertThrows(IllegalStateException.class, () -> new EnchantTransaction(fixture.prices).commit(
                fixture.player, fixture.session(), ServerFixture.SHARPNESS, 2, () -> true));
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
        assertEquals(1000, fixture.totalScore);
        assertEquals(0, Enchantments.level(fixture.items.get(0), ServerFixture.SHARPNESS));
    }

    @Test
    void metadataPreparationModeChangeIsDetectedBeforeClaimOrAnyXpMutation() throws Exception {
        ServerFixture fixture = new ServerFixture();
        EnchantSession session = fixture.session();
        PurchaseQuote displayed = PurchaseQuote.read(fixture.player, session, ServerFixture.SHARPNESS, 2,
                fixture.prices, BookshelfPricing.disabled());
        AtomicInteger calls = new AtomicInteger();
        assertThrows(EnchantTransaction.QuoteChangedException.class, () ->
                new EnchantTransaction(fixture.prices).commit(fixture.player, session, ServerFixture.SHARPNESS, 2,
                        () -> {
                            if (calls.incrementAndGet() == 2) {
                                when(fixture.player.getGameMode()).thenReturn(GameMode.CREATIVE);
                            }
                            return true;
                        }, displayed));
        assertTrue(session.available());
        assertEquals(0, fixture.writes);
        verify(fixture.player, never()).setLevel(anyInt());
    }

    @Test
    void creativeStillRejectsUseDenialsAdventureSpectatorCapsConflictsAndInvalidPrices() throws Exception {
        ServerFixture fixture = new ServerFixture();
        when(fixture.player.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(fixture.player.getLevel()).thenThrow(new AssertionError("No creative XP read"));
        when(fixture.player.getExp()).thenThrow(new AssertionError("No creative XP read"));
        assertFalse(new EnchantTransaction(fixture.prices).commit(fixture.player, fixture.session(),
                ServerFixture.SHARPNESS, 6, () -> true));
        when(fixture.player.hasPermission(EnchantAccess.USE_PERMISSION)).thenReturn(false);
        assertFalse(new EnchantTransaction(fixture.prices).commit(fixture.player, fixture.session(),
                ServerFixture.SHARPNESS, 2, () -> true));
        for (GameMode denied : new GameMode[]{GameMode.ADVENTURE, GameMode.SPECTATOR}) {
            when(fixture.player.getGameMode()).thenReturn(denied);
            assertFalse(EnchantAccess.eligible(fixture.player));
        }
        assertEquals(0, fixture.writes);
        ServerFixture capped = new ServerFixture("base-xp: 25\nmax-cost: 25\n");
        when(capped.player.getGameMode()).thenReturn(GameMode.CREATIVE);
        capped.open();
        assertEquals(MenuStage.ITEM, capped.menu().page);
    }

    @Test
    void configuredSoundsAreExclusivePrivateAndIndependentOfBlankChat() throws Exception {
        ServerFixture fixture = new ServerFixture(ServerFixture.LEGACY_PRICING +
                "base-xp: 25\nmax-cost: 1000000\nmessages:\n  success: ''\n  insufficient: ''\n" +
                "sounds:\n  click:\n    pitch: 0.25\n    volume: 0.75\n");
        fixture.confirmPage();
        verify(fixture.player, atLeastOnce()).playSound(any(Location.class), eq(Sound.UI_BUTTON_CLICK), eq(0.75f), eq(0.25f));
        clearInvocations(fixture.player);
        clearInvocations(fixture.world);
        fixture.click(0, ClickType.LEFT);
        assertTrue(fixture.tasks.isEmpty());
        verify(fixture.player, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
        fixture.balance(0);
        fixture.click(fixture.menu().layout.confirm, ClickType.LEFT);
        fixture.tick();
        assertEquals(Material.BARRIER, fixture.view.getTopInventory().getItem(fixture.menu().layout.confirm).getType());
        verify(fixture.player, times(1)).playSound(any(Location.class), eq(Sound.ENTITY_VILLAGER_NO), anyFloat(), anyFloat());
        verify(fixture.player, never()).playSound(any(Location.class), eq(Sound.UI_BUTTON_CLICK), anyFloat(), anyFloat());
        verify(fixture.player, never()).sendMessage(anyString());
        fixture.balance(1000);
        fixture.click(fixture.menu().layout.confirm, ClickType.LEFT);
        fixture.tick();
        clearInvocations(fixture.player);
        clearInvocations(fixture.world);
        fixture.click(fixture.menu().layout.confirm, ClickType.LEFT);
        fixture.tick();
        verify(fixture.player, times(1)).playSound(any(Location.class), eq(Sound.BLOCK_ENCHANTMENT_TABLE_USE), anyFloat(), anyFloat());
        verify(fixture.player, never()).playSound(any(Location.class), eq(Sound.UI_BUTTON_CLICK), anyFloat(), anyFloat());
        verify(fixture.player, never()).playSound(any(Location.class), eq(Sound.ENTITY_VILLAGER_NO), anyFloat(), anyFloat());
        verify(fixture.player, never()).sendMessage(anyString());
        verify(fixture.world, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
    }

    @Test
    void disabledAndEmptySoundsAreSilentAndInvalidSettingsRejectBeforePublication() throws Exception {
        for (String sounds : new String[]{"sounds:\n  enabled: false\n", "sounds:\n  click:\n    sound: ''\n" +
                "  success:\n    sound: ''\n  insufficient:\n    sound: ''\n"}) {
            ServerFixture fixture = new ServerFixture(ServerFixture.LEGACY_PRICING + "base-xp: 25\nmax-cost: 1000000\n" + sounds);
            fixture.confirmPage();
            fixture.click(fixture.menu().layout.confirm, ClickType.LEFT);
            fixture.tick();
            verify(fixture.player, never()).playSound(any(Location.class), any(Sound.class), anyFloat(), anyFloat());
        }
        for (String invalid : new String[]{"sound: modern_invalid", "sound: 1", "volume: .NaN",
                "pitch: .inf", "volume: 4.1", "pitch: -0.1", "pitch: 2.1", "volume: '1'"}) {
            assertThrows(IllegalArgumentException.class, () -> Settings.read(yaml(
                    "base-xp: 25\nmax-cost: 1000000\nsounds:\n  click:\n    " + invalid + "\n")));
        }
    }

    @Test
    void prefixOnlyBlankAndLegacyTemplatesAreHandledWithoutStrippingCustomContent() throws Exception {
        ServerFixture fixture = new ServerFixture();
        for (String body : new String[]{"", "   ", "{prefix}", "{prefix}&a  "}) {
            YamlConfiguration config = ConfigValues.defaults();
            config.set("messages.success", body);
            Settings.read(config).text().send(fixture.player, "success", Collections.emptyMap());
        }
        verify(fixture.player, never()).sendMessage(anyString());
        YamlConfiguration config = ConfigValues.defaults();
        String custom = "&5EnchantedXP &8> &aAn operator's custom message {prefix}{prefix}";
        config.set("messages.success", custom);
        config.set("messages.prefix", "OnlyOnce ");
        Settings.read(config).text().send(fixture.player, "success", Collections.emptyMap());
        verify(fixture.player).sendMessage("OnlyOnce " + ChatColor.translateAlternateColorCodes('&',
                "&5EnchantedXP &8> &aAn operator's custom message "));
        assertEquals(custom, config.getString("messages.success"));
        String oldEligibility = "{prefix}&cEnchantXP requires survival mode and enchantxp.use.";
        config.set("messages.eligibility", oldEligibility);
        Settings.read(config).text().send(fixture.player, "eligibility", Collections.emptyMap());
        verify(fixture.player).sendMessage("OnlyOnce " + ChatColor.RED +
                "EnchantXP requires survival or creative mode and enchantxp.use.");
        assertEquals(oldEligibility, config.getString("messages.eligibility"));
    }

    @Test
    void removedShelvesRefreshHigherPriceAndExtraShelvesAtTheCapDoNotChangeQuote() throws Exception {
        for (boolean remove : new boolean[]{true, false}) {
            ServerFixture fixture = new ServerFixture("base-xp: 25\nmax-cost: 1000000\n");
            Map<String, Material> field = shelfField(fixture);
            int filled = 0;
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) == 2) {
                        for (int y = 64; y <= 65; y++) {
                            if (filled++ < 15) {
                                field.put(key(x, y, z), Material.BOOKSHELF);
                            }
                        }
                    }
                }
            }
            fixture.confirmPage();
            assertEquals(100, fixture.menu().quote.cost);
            if (remove) {
                field.clear();
            } else {
                for (int x = -2; x <= 2; x++) {
                    for (int z = -2; z <= 2; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) == 2) {
                            field.put(key(x, 64, z), Material.BOOKSHELF);
                            field.put(key(x, 65, z), Material.BOOKSHELF);
                        }
                    }
                }
            }
            fixture.click(fixture.menu().layout.confirm, ClickType.LEFT);
            fixture.tick();
            if (remove) {
                assertEquals(0, fixture.writes);
                assertEquals(250, fixture.menu().quote.cost);
                fixture.click(fixture.menu().layout.confirm, ClickType.LEFT);
                fixture.tick();
            }
            assertEquals(1, fixture.writes);
            assertEquals(remove ? 750 : 900, Experience.total(fixture.level, fixture.progress));
        }
    }

    private static Map<String, Material> shelfField(ServerFixture fixture) {
        Map<String, Material> field = new HashMap<String, Material>();
        when(fixture.world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            Block block = mock(Block.class);
            when(block.getType()).thenReturn(field.getOrDefault(key(call.getArgument(0),
                    call.getArgument(1), call.getArgument(2)), Material.AIR));
            return block;
        });
        return field;
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}
