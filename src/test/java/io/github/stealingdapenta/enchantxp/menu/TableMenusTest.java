package io.github.stealingdapenta.enchantxp.menu;

import io.github.stealingdapenta.enchantxp.configuration.Settings;
import io.github.stealingdapenta.enchantxp.enchanting.Enchantments;
import io.github.stealingdapenta.enchantxp.enchanting.Experience;
import io.github.stealingdapenta.enchantxp.presentation.MenuStage;
import io.github.stealingdapenta.enchantxp.presentation.MenuText;
import static io.github.stealingdapenta.enchantxp.testsupport.YamlDocuments.yaml;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Item;
import org.bukkit.Server;
import org.bukkit.Bukkit;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TableMenusTest {
    @Test
    void fortuneSeventyIsReachableAcrossPagesWithoutConfiguredCeilingButFiniteCapFiltersPrices() throws Exception {
        for (int cap : new int[]{-1, 1000000}) {
            fixture = new ServerFixture("config-version: 1\nbase-xp: 10000\nmax-cost: " + cap +
                    "\nbookshelf-pricing:\n  zero-shelf-penalty-percent: 50\n" +
                    "enchantments:\n  LOOT_BONUS_BLOCKS:\n    base-xp: 25000\n    max-level: 70\n");
            fixture.items.put(0, new ItemStack(Material.BOOK));
            fixture.open();
            Enchantment fortune = Enchantment.getByName("LOOT_BONUS_BLOCKS");
            int slot = fixture.menu().choices.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(fortune)).findFirst().get().getKey();
            fixture.click(slot, ClickType.LEFT);
            fixture.tick();
            Set<Integer> levels = new HashSet<>();
            int pages = fixture.menu().pages;
            for (int page = 0; page < pages; page++) {
                levels.addAll(fixture.menu().levels.values());
                if (page + 1 < pages) {
                    fixture.click(fixture.menu().layout.next, ClickType.LEFT);
                    fixture.tick();
                }
            }
            assertEquals(cap == -1 ? 70 : 5, levels.size());
            assertEquals(cap == -1, levels.contains(70));
            if (cap == -1) {
                fixture.balance(200000000);
                int seventy = fixture.menu().levels.entrySet().stream()
                        .filter(entry -> entry.getValue() == 70).findFirst().get().getKey();
                fixture.click(seventy, ClickType.LEFT);
                fixture.tick();
                fixture.click(fixture.confirmSlot(), ClickType.LEFT);
                fixture.tick();
                assertEquals(16250000, Experience.total(fixture.level, fixture.progress));
                assertEquals(70, Enchantments.level(fixture.items.get(0), fortune));
            }
        }
    }

    @Test
    void everyHeightPaginatesAllBookTypesAnd255LevelsWithGlobalSortAndSafeSlots() throws Exception {
        for (int rows = 3; rows <= 6; rows++) {
            String config = ServerFixture.LEGACY_PRICING + "base-xp: 1\nmax-cost: 1000000\nui:\n  rows: " + rows +
                    "\nenchantments:\n  DAMAGE_ALL:\n    max-level: 255\n    name: '&cZZ Sharpness'\n";
            fixture = new ServerFixture(config);
            fixture.items.put(0, new ItemStack(Material.BOOK));
            fixture.open();
            List<Enchantment> defaults = Enchantments.choices(fixture.items.get(0), fixture.prices);
            MenuText text = Settings.read(yaml(config)).text();
            Comparator<Enchantment> names = (first, second) -> String.CASE_INSENSITIVE_ORDER.compare(
                    ChatColor.stripColor(text.name(first)), ChatColor.stripColor(text.name(second)));
            for (int mode = 0; mode < 4; mode++) {
                List<Enchantment> expected = new ArrayList<Enchantment>(defaults);
                if (mode == 1) { expected.sort(names.thenComparing(Enchantment::getName)); }
                if (mode == 2) { expected.sort(names.reversed().thenComparing(Enchantment::getName)); }
                List<Enchantment> actual = new ArrayList<Enchantment>();
                assertEquals(0, fixture.menu().index);
                int pages = fixture.menu().pages;
                for (int page = 0; page < pages; page++) {
                    assertSafeChoices(rows);
                    actual.addAll(enchantmentOrder());
                    if (page + 1 < pages) {
                        fixture.click(fixture.menu().layout.next, ClickType.LEFT);
                        fixture.tick();
                    }
                }
                assertEquals(expected, actual);
                if (pages > 1) {
                    fixture.click(fixture.menu().layout.previous, ClickType.LEFT);
                    fixture.tick();
                    assertEquals(pages - 2, fixture.menu().index);
                }
                if (mode < 3) {
                    fixture.click(fixture.menu().layout.sort, ClickType.LEFT);
                    fixture.tick();
                }
            }
            // Return to page one through the global sort cycle before selecting Sharpness.
            fixture.click(fixture.menu().layout.sort, ClickType.LEFT);
            fixture.tick();
            fixture.click(fixture.menu().layout.sort, ClickType.LEFT);
            fixture.tick();
            assertTrue(fixture.menu().choices.containsValue(ServerFixture.SHARPNESS));
            fixture.click(sharpnessSlot(), ClickType.LEFT);
            fixture.tick();
            assertEquals(255, levelOrder().get(0));
            fixture.click(fixture.menu().layout.sort, ClickType.LEFT);
            fixture.tick();
            Set<Integer> all = new HashSet<Integer>();
            int pages = fixture.menu().pages;
            assertEquals((255 + (rows - 2) * 7 - 1) / ((rows - 2) * 7), pages);
            for (int page = 0; page < pages; page++) {
                assertSafeChoices(rows);
                assertEquals(page, fixture.menu().index);
                for (int level : fixture.menu().levels.values()) {
                    assertTrue(all.add(level));
                }
                if (page + 1 < pages) {
                    fixture.click(fixture.menu().layout.next, ClickType.LEFT);
                    fixture.tick();
                }
            }
            assertEquals(255, all.size());
            fixture.click(fixture.menu().layout.sort, ClickType.LEFT);
            fixture.tick();
            assertEquals(1, levelOrder().get(0));
            fixture.click(fixture.menu().layout.sort, ClickType.LEFT);
            fixture.tick();
            assertEquals(255, levelOrder().get(0));
            assertEquals(0, fixture.writes);
        }
    }

    @Test
    void highLevelNormalStoredAndSplitBookPurchasesPreserveMetadataAfterSettingsReplacement() throws Exception {
        for (Material type : new Material[]{Material.DIAMOND_SWORD, Material.ENCHANTED_BOOK, Material.BOOK}) {
            fixture = new ServerFixture();
            ItemStack original = new ItemStack(type, type == Material.BOOK ? 64 : 1,
                    type == Material.DIAMOND_SWORD ? (short) 37 : 0);
            ItemMeta meta = original.getItemMeta();
            meta.setDisplayName("Original {cost} name");
            meta.setLore(Arrays.asList("Keep original lore", "Second line"));
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            if (meta instanceof EnchantmentStorageMeta) {
                ((EnchantmentStorageMeta) meta).addStoredEnchant(ServerFixture.SHARPNESS, 200, true);
                ((EnchantmentStorageMeta) meta).addStoredEnchant(ServerFixture.UNBREAKING, 1, true);
            }
            original.setItemMeta(meta);
            if (type == Material.DIAMOND_SWORD) {
                original.addUnsafeEnchantment(ServerFixture.SHARPNESS, 200);
                original.addEnchantment(ServerFixture.UNBREAKING, 1);
            }
            ItemStack before = original.clone();
            fixture.items.put(0, original);
            fixture.balance(100000);
            fixture.menus.replace(Settings.read(yaml(ServerFixture.LEGACY_PRICING + "base-xp: 1\nmax-cost: 1000000\n" +
                    "enchantments:\n  DAMAGE_ALL:\n    max-level: 255\nui:\n  rows: 3\n")));
            fixture.open();
            // Books have several type pages; ascending then descending puts Sharpness on page one.
            while (!fixture.menu().choices.containsValue(ServerFixture.SHARPNESS)) {
                fixture.click(fixture.menu().layout.next, ClickType.LEFT);
                fixture.tick();
            }
            fixture.click(sharpnessSlot(), ClickType.LEFT);
            fixture.tick();
            fixture.click(fixture.menu().layout.sort, ClickType.LEFT);
            fixture.tick();
            fixture.click(fixture.menu().layout.sort, ClickType.LEFT);
            fixture.tick();
            assertEquals(255, levelOrder().get(0));
            fixture.click(10, ClickType.LEFT);
            fixture.tick();
            int cost = type == Material.BOOK ? 65025 : 25025;
            assertStoredBook(fixture.menu().layout.review, 255);
            assertTrue(fixture.view.getTopInventory().getItem(fixture.menu().layout.confirm)
                    .getItemMeta().getLore().stream().anyMatch(line -> line.contains(cost + " raw XP")));
            fixture.click(fixture.menu().layout.confirm, ClickType.LEFT);
            fixture.tick();
            ItemStack result = fixture.items.get(type == Material.BOOK ? 1 : 0);
            assertEquals(255, Enchantments.level(result, ServerFixture.SHARPNESS));
            assertEquals(type == Material.BOOK ? 0 : 1, Enchantments.level(result, ServerFixture.UNBREAKING));
            assertEquals(before.getItemMeta().getDisplayName(), result.getItemMeta().getDisplayName());
            assertEquals(before.getItemMeta().getLore(), result.getItemMeta().getLore());
            assertEquals(before.getItemMeta().getItemFlags(), result.getItemMeta().getItemFlags());
            assertEquals(before.getDurability(), result.getDurability());
            assertEquals(100000 - cost, Experience.total(fixture.level, fixture.progress));
            assertEquals(before, original);
            if (type == Material.BOOK) {
                before.setAmount(63);
                assertEquals(before, fixture.items.get(0));
                assertEquals(Material.ENCHANTED_BOOK, result.getType());
                assertEquals(2, fixture.writes);
            } else {
                assertEquals(1, fixture.writes);
            }
        }
    }

    @Test
    void eachHeightFencesConfirmBeforeBackCancelAndSettingsSwapAndRejectsBottomClicksAsControls() throws Exception {
        for (int rows = 3; rows <= 6; rows++) {
            fixture = new ServerFixture(ServerFixture.LEGACY_PRICING + "base-xp: 25\nmax-cost: 1000000\nui:\n  rows: " + rows + "\n");
            fixture.confirmPage();
            MenuLayout layout = fixture.menu().layout;
            fixture.select(0, ClickType.LEFT);
            assertTrue(fixture.tasks.isEmpty());
            fixture.click(layout.confirm, ClickType.LEFT);
            Runnable confirm = fixture.tasks.remove();
            fixture.click(layout.back, ClickType.LEFT);
            confirm.run();
            fixture.tick();
            assertEquals(MenuStage.LEVEL, fixture.menu().page);
            fixture.click(11, ClickType.LEFT);
            fixture.tick();
            fixture.click(layout.confirm, ClickType.LEFT);
            fixture.click(layout.cancel, ClickType.LEFT);
            fixture.tick();
            assertNull(fixture.menu());
            fixture.confirmPage();
            fixture.click(layout.confirm, ClickType.LEFT);
            confirm = fixture.tasks.remove();
            fixture.menus.replace(Settings.read(yaml("base-xp: 1\nmax-cost: 1000000\n")));
            confirm.run();
            assertNull(fixture.menu());
            assertEquals(0, fixture.writes);
            assertEquals(1000, Experience.total(fixture.level, fixture.progress));
        }
    }

    private void assertSafeChoices(int rows) {
        MenuHolder menu = fixture.menu();
        int footer = (rows - 1) * 9;
        Inventory inventory = fixture.view.getTopInventory();
        assertEquals(rows * 9, inventory.getSize());
        Set<Integer> slots = new HashSet<Integer>(menu.choices.keySet());
        slots.addAll(menu.levels.keySet());
        for (int slot : slots) {
            assertTrue(slot >= 10 && slot < footer && slot % 9 >= 1 && slot % 9 <= 7);
        }
        assertEquals(Material.BOOK, inventory.getItem(4).getType());
        assertEquals(Material.BARRIER, inventory.getItem(footer + 4).getType());
        assertEquals(Material.ARROW, inventory.getItem(footer).getType());
        assertEquals(Material.HOPPER, inventory.getItem(footer + 7).getType());
        assertEquals(Material.EXP_BOTTLE, inventory.getItem(footer + 5).getType());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            assertNotNull(inventory.getItem(slot));
        }
    }

    @Test
    void configuredHeightsUseLastRowControlsAndSevenChoicesPerInteriorRow() throws Exception {
        for (int rows = 3; rows <= 6; rows++) {
            fixture = new ServerFixture(ServerFixture.LEGACY_PRICING + "base-xp: 1\nmax-cost: 1000000\nui:\n  rows: " + rows + "\n");
            fixture.items.put(0, new ItemStack(Material.BOOK));
            fixture.openTable();
            assertEquals(rows * 9, fixture.view.getTopInventory().getSize());
            int center = ((rows - 1) / 2) * 9 + 4;
            assertNull(fixture.view.getTopInventory().getItem(center - 1));
            assertEquals(Material.ANVIL, fixture.view.getTopInventory().getItem(center + 1).getType());
            int footer = (rows - 1) * 9;
            assertEquals(Material.BARRIER, fixture.view.getTopInventory().getItem(footer + 4).getType());
            fixture.select(0, ClickType.LEFT);
            fixture.tick();
            assertEquals((rows - 2) * 7, fixture.menu().choices.size());
        }
    }

    @Test
    void bookTypesPaginateWithoutStoredLevelsAndDisplayInputNeverAcceptsActions() {
        fixture.items.put(0, new ItemStack(Material.BOOK));
        fixture.openTable();
        MenuHolder input = fixture.menu();
        fixture.click(21, ClickType.LEFT);
        assertTrue(fixture.tasks.isEmpty());
        assertSame(input, fixture.menu());
        fixture.select(0, ClickType.LEFT);
        fixture.tick();
        assertEquals(2, fixture.menu().pages);
        assertEquals(28, fixture.menu().choices.size());
        Set<Enchantment> choices = new HashSet<Enchantment>(fixture.menu().choices.values());
        for (int slot : fixture.menu().choices.keySet()) {
            assertTrue(((EnchantmentStorageMeta) fixture.view.getTopInventory().getItem(slot).getItemMeta())
                    .getStoredEnchants().isEmpty());
        }
        fixture.click(TableMenus.NEXT_SLOT, ClickType.LEFT);
        fixture.tick();
        choices.addAll(fixture.menu().choices.values());
        assertEquals(30, choices.size());
        assertEquals(Material.BOOK, fixture.items.get(0).getType());
        assertEquals(0, fixture.writes);
    }

    @Test
    void supportedMaxedAndPriceCappedItemsReceiveDistinctFeedbackWithoutClosingOrMutation() throws Exception {
        for (boolean cappedPrice : new boolean[]{false, true}) {
            fixture = new ServerFixture(ServerFixture.LEGACY_PRICING + (cappedPrice ? "base-xp: 25\nmax-cost: 25\n" :
                    "base-xp: 25\nmax-cost: 1000000\n"));
            ItemStack sword = fixture.items.get(0);
            sword.addEnchantment(ServerFixture.SHARPNESS, cappedPrice ? 1 : 5);
            sword.addEnchantment(ServerFixture.UNBREAKING, 3);
            ItemStack before = sword.clone();
            fixture.openTable();
            MenuHolder menu = fixture.menu();
            fixture.select(0, ClickType.LEFT);
            fixture.tick();
            assertSame(menu, fixture.menu());
            verify(fixture.player).sendMessage(contains(cappedPrice ? "server price limits" : "no more upgrades"));
            assertEquals(before, sword);
            assertEquals(0, fixture.writes);
            assertEquals(1000, Experience.total(fixture.level, fixture.progress));
        }
    }

    @Test
    void itemPageHasContinuousPinkBorderAndEmptyDisplayInputBesideAnvil() throws Exception {
        ServerFixture page = new ServerFixture();
        page.openTable();
        assertNull(page.view.getTopInventory().getItem(21));
        assertEquals(Material.ANVIL, page.view.getTopInventory().getItem(23).getType());
        for (int slot = 0; slot < 9; slot++) {
            assertEquals(2, page.view.getTopInventory().getItem(slot).getDurability());
        }
        assertEquals(15, page.view.getTopInventory().getItem(22).getDurability());
    }

    private ServerFixture fixture;

    @BeforeEach
    void setUp() throws Exception { fixture = new ServerFixture(); }

    @Test
    void emptyHandOpensSelectionAndMainInventoryItemCanBePurchasedWithoutMovingIt() {
        ItemStack sword = fixture.items.remove(0);
        fixture.items.put(17, sword);
        fixture.openTable();
        assertNotNull(fixture.menu());
        assertEquals(MenuStage.ITEM, fixture.menu().page);
        assertTrue(fixture.select(17, ClickType.LEFT).isCancelled());
        assertEquals(MenuStage.ITEM, fixture.menu().page);
        fixture.tick();
        assertEquals(MenuStage.ENCHANTMENT, fixture.menu().page);
        assertSame(sword, fixture.items.get(17));
        assertEquals(0, fixture.writes);
        int choice = fixture.menu().choices.entrySet().stream()
                .filter(entry -> entry.getValue().equals(ServerFixture.SHARPNESS)).findFirst().get().getKey();
        fixture.click(choice, ClickType.LEFT);
        fixture.tick();
        fixture.click(11, ClickType.LEFT);
        fixture.tick();
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        fixture.tick();
        assertEquals(900, Experience.total(fixture.level, fixture.progress));
        assertEquals(2, fixture.items.get(17).getEnchantmentLevel(ServerFixture.SHARPNESS));
        assertNull(fixture.items.get(0));
        assertEquals(0, sword.getEnchantmentLevel(ServerFixture.SHARPNESS));
        assertEquals(1, fixture.writes);
    }

    @Test
    void holdingAnIncompatibleItemStillOpensSelectionAndInvalidSelectionExplainsRetry() {
        fixture.items.put(0, new ItemStack(Material.STONE));
        fixture.openTable();
        assertNotNull(fixture.menu());
        assertEquals(MenuStage.ITEM, fixture.menu().page);
        fixture.select(0, ClickType.LEFT);
        fixture.tick();
        assertEquals(MenuStage.ITEM, fixture.menu().page);
        verify(fixture.player).sendMessage(contains("Choose one compatible"));
        assertEquals(0, fixture.writes);
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void selectionBlocksDangerousClicksArmorOffhandOutsideAndDragWithoutMovingItems() {
        fixture.openTable();
        MenuHolder selection = fixture.menu();
        for (ClickType click : ClickType.values()) {
            if (click != ClickType.LEFT && click != ClickType.RIGHT &&
                    click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT) {
                assertTrue(fixture.select(0, click).isCancelled(), click.name());
                assertTrue(fixture.tasks.isEmpty());
            }
        }
        for (int rawSlot : new int[] {-999, 90, 91, 94}) {
            InventoryClickEvent invalid = new InventoryClickEvent(fixture.view, InventoryType.SlotType.ARMOR, rawSlot,
                    ClickType.LEFT, InventoryAction.PICKUP_ALL);
            fixture.menus.click(invalid);
            assertTrue(invalid.isCancelled());
            assertTrue(fixture.tasks.isEmpty());
        }
        InventoryDragEvent drag = new InventoryDragEvent(fixture.view, null, new ItemStack(Material.DIRT), false,
                Collections.singletonMap(81, new ItemStack(Material.DIRT)));
        fixture.menus.drag(drag);
        assertTrue(drag.isCancelled());
        assertSame(selection, fixture.menu());
        assertEquals(0, fixture.writes);
    }

    @Test
    void ordinaryStorageSelectionClicksAreCancelledDeferredAndNeverMoveItemsOrXp() throws Exception {
        for (ClickType click : new ClickType[]{ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT}) {
            for (int source : new int[]{0, 17, 35}) {
                fixture = new ServerFixture();
                ItemStack item = fixture.items.remove(0);
                fixture.items.put(source, item);
                ItemStack before = item.clone();
                fixture.openTable();
                MenuHolder selection = fixture.menu();
                int raw = selection.layout.size + (source < 9 ? 27 + source : source - 9);
                InventoryClickEvent event = new InventoryClickEvent(fixture.view, InventoryType.SlotType.CONTAINER,
                        raw, click, click.isShiftClick() ? InventoryAction.MOVE_TO_OTHER_INVENTORY :
                        click == ClickType.RIGHT ? InventoryAction.PICKUP_HALF : InventoryAction.PICKUP_ALL);
                fixture.menus.click(event);
                assertTrue(event.isCancelled(), click.name());
                assertSame(selection, fixture.menu());
                assertEquals(1, fixture.tasks.size());
                fixture.select(source, click);
                assertEquals(1, fixture.tasks.size());
                fixture.tick();
                assertEquals(MenuStage.ENCHANTMENT, fixture.menu().page, click.name());
                assertEquals(before, item);
                assertSame(item, fixture.items.get(source));
                assertEquals(1, fixture.items.size());
                assertEquals(0, fixture.writes);
                assertEquals(1000, Experience.total(fixture.level, fixture.progress));
                assertEquals(987654321, fixture.totalScore);
                verify(fixture.player, never()).setLevel(anyInt());
                verify(fixture.player, never()).setExp(anyFloat());
            }
        }
    }

    @Test
    void expandedSelectionClicksStillRespectCancellationForeignViewsSnapshotsAndBookSpace() throws Exception {
        for (ClickType click : new ClickType[]{ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT}) {
            for (int rejection = 0; rejection < 5; rejection++) {
                fixture = new ServerFixture();
                fixture.items.put(0, new ItemStack(Material.BOOK, 2));
                fixture.openTable();
                InventoryView original = fixture.view;
                InventoryClickEvent event = new InventoryClickEvent(original, InventoryType.SlotType.QUICKBAR,
                        original.getTopInventory().getSize() + 27, click, InventoryAction.MOVE_TO_OTHER_INVENTORY);
                if (rejection == 0) { event.setCancelled(true); }
                if (rejection == 1) { fixture.view = fixture.new View(mock(Inventory.class)); }
                if (rejection == 2) {
                    Player foreign = mock(Player.class);
                    when(foreign.getUniqueId()).thenReturn(UUID.randomUUID());
                    fixture.view = new InventoryView() {
                        @Override public Inventory getTopInventory() { return original.getTopInventory(); }
                        @Override public Inventory getBottomInventory() { return fixture.inventory; }
                        @Override public Player getPlayer() { return foreign; }
                        @Override public InventoryType getType() { return InventoryType.CHEST; }
                    };
                    event = new InventoryClickEvent(fixture.view, InventoryType.SlotType.QUICKBAR,
                            original.getTopInventory().getSize() + 27, click, InventoryAction.MOVE_TO_OTHER_INVENTORY);
                }
                if (rejection == 4) { fixture.fillStorage(); }
                fixture.menus.click(event);
                assertTrue(event.isCancelled());
                if (rejection < 3) { assertTrue(fixture.tasks.isEmpty()); }
                if (rejection == 3) { fixture.items.get(0).setAmount(3); }
                Map<Integer, ItemStack> before = new HashMap<Integer, ItemStack>();
                fixture.items.forEach((slot, item) -> before.put(slot, item.clone()));
                fixture.tick();
                assertEquals(before, fixture.items);
                assertEquals(0, fixture.writes);
                assertEquals(1000, Experience.total(fixture.level, fixture.progress));
                if (rejection >= 3) {
                    assertTrue(fixture.menu() == null || fixture.menu().page == MenuStage.ITEM);
                }
            }
        }
    }

    @Test
    void rightAndShiftClicksCannotNavigateOrConfirmAfterSelection() throws Exception {
        fixture.confirmPage();
        MenuHolder original = fixture.menu();
        for (ClickType click : new ClickType[]{ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT}) {
            for (int slot : new int[]{original.layout.confirm, original.layout.back, original.layout.cancel, original.layout.sort}) {
                assertTrue(fixture.click(slot, click).isCancelled());
                assertTrue(fixture.tasks.isEmpty());
                assertSame(original, fixture.menu());
            }
        }
        assertEquals(0, fixture.writes);
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void selectionRevalidatesAllContextAndClickedSnapshotBeforePublishingAnItemSession() throws Exception {
        for (int change = 0; change < 11; change++) {
            fixture = new ServerFixture();
            fixture.openTable();
            fixture.select(0, ClickType.LEFT);
            if (change == 0) { when(fixture.player.hasPermission(EnchantAccess.USE_PERMISSION)).thenReturn(false); }
            if (change == 1) { when(fixture.player.getGameMode()).thenReturn(GameMode.ADVENTURE); }
            if (change == 2) { when(fixture.player.getItemOnCursor()).thenReturn(new ItemStack(Material.DIRT)); }
            if (change == 3) { when(fixture.inventory.getHeldItemSlot()).thenReturn(1); }
            if (change == 4) { fixture.items.get(0).setDurability((short) 4); }
            if (change == 5) { when(fixture.player.getLocation()).thenReturn(new Location(fixture.world, 20, 64, 0)); }
            if (change == 6) { when(fixture.block.getType()).thenReturn(Material.AIR); }
            if (change == 7) { when(fixture.player.isOnline()).thenReturn(false); }
            if (change == 8) { when(fixture.player.isDead()).thenReturn(true); }
            if (change == 9) { when(fixture.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false); }
            if (change == 10) { when(fixture.player.getLocation()).thenReturn(new Location(mock(World.class), 0, 64, 0)); }
            fixture.tick();
            assertTrue(fixture.menu() == null || fixture.menu().page == MenuStage.ITEM, "change " + change);
            assertEquals(0, fixture.writes, "change " + change);
            assertEquals(1000, Experience.total(fixture.level, fixture.progress));
        }
    }

    @Test
    void selectedMainInventorySnapshotAndObservedHotbarSlotRemainIndependentAndStrict() throws Exception {
        for (int change = 0; change < 3; change++) {
            fixture = new ServerFixture();
            ItemStack sword = fixture.items.remove(0);
            fixture.items.put(35, sword);
            fixture.openTable();
            fixture.select(35, ClickType.LEFT);
            fixture.tick();
            fixture.click(10, ClickType.LEFT);
            fixture.tick();
            fixture.click(11, ClickType.LEFT);
            fixture.tick();
            fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
            if (change == 0) { when(fixture.inventory.getHeldItemSlot()).thenReturn(1); }
            if (change == 1) {
                ItemMeta meta = sword.getItemMeta();
                meta.setDisplayName("Changed after selection");
                sword.setItemMeta(meta);
            }
            if (change == 2) { fixture.items.remove(35); }
            fixture.tick();
            assertEquals(0, fixture.writes, "change " + change);
            assertEquals(1000, Experience.total(fixture.level, fixture.progress));
        }
    }

    @Test
    void queuedSelectionIsFencedByCancelCloseExternalTakeoverReplacementAndShutdown() throws Exception {
        for (int stop = 0; stop < 5; stop++) {
            fixture = new ServerFixture();
            fixture.openTable();
            fixture.select(0, ClickType.LEFT);
            Runnable dispatched = fixture.tasks.remove();
            InventoryView external = null;
            if (stop == 0) { fixture.click(49, ClickType.LEFT); }
            if (stop == 1) { fixture.menus.close(new InventoryCloseEvent(fixture.view)); }
            if (stop == 2) {
                fixture.player.closeInventory();
                external = fixture.view;
            }
            if (stop == 3) { fixture.openTable(); }
            if (stop == 4) { fixture.menus.shutdown(); }
            MenuHolder replacement = fixture.menu();
            dispatched.run();
            fixture.tick();
            if (stop == 2) { assertSame(external, fixture.view); }
            if (stop == 3) {
                assertSame(replacement, fixture.menu());
                assertEquals(MenuStage.ITEM, fixture.menu().page);
            } else {
                assertTrue(fixture.menu() == null || fixture.menu().page == MenuStage.ITEM);
            }
            assertEquals(0, fixture.writes, "stop " + stop);
            assertEquals(1000, Experience.total(fixture.level, fixture.progress));
        }
    }

    @Test
    void nativeLikeInventoryWrapperKeepsMenuOpenAndSharesItsHolderAndIcons() {
        fixture.open();
        MenuHolder menu = fixture.menu();
        assertNotNull(menu);
        Inventory top = fixture.view.getTopInventory();
        assertNotSame(menu.getInventory(), top);
        assertSame(menu, top.getHolder());
        assertSame(menu.getInventory().getItem(49), top.getItem(49));
        fixture.click(49, ClickType.LEFT);
        fixture.tick();
        assertNull(fixture.menu());
        assertEquals(0, fixture.writes);
    }

    @Test
    void completeFlowDefersEveryTransitionAndChargesOnlyAfterExplicitConfirmation() {
        fixture.open();
        MenuHolder first = fixture.menu();
        int slot = first.choices.entrySet().stream().filter(entry -> entry.getValue().equals(ServerFixture.SHARPNESS)).findFirst().get().getKey();
        assertTrue(fixture.click(slot, ClickType.LEFT).isCancelled());
        fixture.click(slot, ClickType.LEFT);
        assertSame(first, fixture.menu());
        assertEquals(1, fixture.tasks.size());
        fixture.tick();
        assertEquals(MenuStage.LEVEL, fixture.menu().page);
        fixture.click(11, ClickType.LEFT);
        fixture.tick();
        assertEquals(MenuStage.CONFIRM, fixture.menu().page);
        assertEquals(0, fixture.writes);
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        assertEquals(0, fixture.writes);
        fixture.tick();
        assertEquals(1, fixture.writes);
        assertEquals(900, Experience.total(fixture.level, fixture.progress));
        assertEquals(2, fixture.items.get(0).getEnchantmentLevel(ServerFixture.SHARPNESS));
        assertNull(fixture.view.getTopInventory().getHolder());
    }

    @Test
    void everyNonLeftClickAndBottomOutsideAndDragActionIsCancelledWithoutScheduling() {
        fixture.confirmPage();
        for (ClickType click : ClickType.values()) {
            if (click != ClickType.LEFT) {
                assertTrue(fixture.click(TableMenus.CONFIRM_SLOT, click).isCancelled(), click.name());
                assertTrue(fixture.tasks.isEmpty());
            }
        }
        assertTrue(fixture.click(54, ClickType.LEFT).isCancelled());
        assertTrue(fixture.click(-999, ClickType.LEFT).isCancelled());
        InventoryDragEvent drag = new InventoryDragEvent(fixture.view, null, new ItemStack(Material.DIRT, 2), false,
                Collections.singletonMap(54, new ItemStack(Material.DIRT)));
        fixture.menus.drag(drag);
        assertTrue(drag.isCancelled());
        assertTrue(fixture.tasks.isEmpty());
        assertEquals(0, fixture.writes);
    }

    @Test
    void staleViewEventsAndForeignPlayerCannotActOnCurrentSession() {
        fixture.open();
        InventoryView stale = fixture.view;
        fixture.click(10, ClickType.LEFT);
        fixture.tick();
        InventoryClickEvent event = new InventoryClickEvent(stale, InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        fixture.menus.click(event);
        assertTrue(event.isCancelled());
        assertTrue(fixture.tasks.isEmpty());
        Player foreign = mock(Player.class);
        when(foreign.getUniqueId()).thenReturn(UUID.randomUUID());
        InventoryView foreignView = new InventoryView() {
            @Override public Inventory getTopInventory() { return fixture.view.getTopInventory(); }
            @Override public Inventory getBottomInventory() { return fixture.inventory; }
            @Override public Player getPlayer() { return foreign; }
            @Override public InventoryType getType() { return InventoryType.CHEST; }
        };
        InventoryClickEvent foreignEvent = new InventoryClickEvent(foreignView, InventoryType.SlotType.CONTAINER, 11, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        fixture.menus.click(foreignEvent);
        assertTrue(foreignEvent.isCancelled());
        assertTrue(fixture.tasks.isEmpty());
        assertEquals(0, fixture.writes);
    }

    @Test
    void closeQuitKickAndDisableInvalidateQueuedConfirmation() throws Exception {
        for (int stop = 0; stop < 4; stop++) {
            fixture = new ServerFixture();
            fixture.confirmPage();
            fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
            if (stop == 0) { fixture.menus.close(new InventoryCloseEvent(fixture.view)); }
            if (stop == 1) { fixture.menus.quit(new PlayerQuitEvent(fixture.player, "quit")); }
            if (stop == 2) { fixture.menus.kick(new PlayerKickEvent(fixture.player, "kick", "left")); }
            if (stop == 3) { fixture.menus.shutdown(); }
            fixture.tick();
            assertEquals(0, fixture.writes, "stop " + stop);
            assertEquals(1000, Experience.total(fixture.level, fixture.progress));
        }
    }

    @Test
    void confirmationRevalidatesPermissionModeCursorSlotItemBalanceReachBlockAndOnlineStatus() throws Exception {
        for (int change = 0; change < 12; change++) {
            fixture = new ServerFixture();
            fixture.confirmPage();
            fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
            if (change == 0) { when(fixture.player.hasPermission(EnchantAccess.USE_PERMISSION)).thenReturn(false); }
            if (change == 1) { when(fixture.player.getGameMode()).thenReturn(GameMode.CREATIVE); }
            if (change == 2) { when(fixture.player.getItemOnCursor()).thenReturn(new ItemStack(Material.DIRT)); }
            if (change == 3) { when(fixture.inventory.getHeldItemSlot()).thenReturn(1); }
            if (change == 4) { fixture.items.get(0).setDurability((short) 4); }
            if (change == 5) { fixture.balance(99); }
            if (change == 6) { when(fixture.player.getLocation()).thenReturn(new Location(fixture.world, 20, 64, 0)); }
            if (change == 7) { when(fixture.block.getType()).thenReturn(Material.AIR); }
            if (change == 8) { when(fixture.player.isOnline()).thenReturn(false); }
            if (change == 9) { when(fixture.player.isDead()).thenReturn(true); }
            if (change == 10) { when(fixture.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false); }
            if (change == 11) { when(fixture.player.getLocation()).thenReturn(new Location(mock(World.class), 0, 64, 0)); }
            fixture.tick();
            assertEquals(0, fixture.writes, "change " + change);
            assertEquals(0, fixture.items.get(0).getEnchantmentLevel(ServerFixture.SHARPNESS));
        }
    }

    @Test
    void cancelAndExternalInventoryReplacementLeaveItemsAndExperienceUntouched() {
        fixture.confirmPage();
        fixture.click(49, ClickType.LEFT);
        fixture.tick();
        assertNull(fixture.view.getTopInventory().getHolder());
        fixture.confirmPage();
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        fixture.player.closeInventory();
        fixture.tick();
        assertEquals(0, fixture.writes);
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void cancelBeforeQueuedConfirmationPreventsItemWritesAndExperienceCharge() {
        fixture.confirmPage();
        MenuHolder confirmation = fixture.menu();
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        fixture.click(49, ClickType.LEFT);
        assertSame(confirmation, fixture.menu());
        verify(fixture.player, never()).closeInventory();
        fixture.tick();
        assertEquals(0, fixture.writes);
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
        assertNull(fixture.menu());
    }

    @Test
    void cancelDuringQueuedTransitionPreventsNextPageFromOpening() {
        fixture.open();
        MenuHolder first = fixture.menu();
        fixture.click(10, ClickType.LEFT);
        fixture.click(49, ClickType.LEFT);
        assertSame(first, fixture.menu());
        fixture.tick();
        assertNull(fixture.menu());
        verify(fixture.player, times(2)).openInventory(any(Inventory.class));
        assertEquals(0, fixture.writes);
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void staleCancelCallbackCannotCloseReplacementSession() {
        fixture.confirmPage();
        fixture.click(49, ClickType.LEFT);
        Runnable cancel = fixture.tasks.remove();
        fixture.open();
        MenuHolder replacement = fixture.menu();
        cancel.run();
        assertSame(replacement, fixture.menu());
        fixture.click(10, ClickType.LEFT);
        fixture.tick();
        assertEquals(MenuStage.LEVEL, fixture.menu().page);
        assertEquals(0, fixture.writes);
    }

    @Test
    void queuedCancelCannotDisruptReplacementSessionWaitingToOpen() {
        fixture.confirmPage();
        fixture.click(49, ClickType.LEFT);
        fixture.menus.interact(interaction(EquipmentSlot.HAND));
        fixture.tick();
        assertNotNull(fixture.menu());
        assertEquals(MenuStage.ITEM, fixture.menu().page);
        verify(fixture.player, never()).closeInventory();
        verify(fixture.player, never()).sendMessage(anyString());
        assertEquals(0, fixture.writes);
    }

    @Test
    void cancelThenShutdownClosesOrphanMenuAndFencesDispatchedCancel() {
        fixture.confirmPage();
        fixture.click(49, ClickType.LEFT);
        Runnable dispatched = fixture.tasks.element();
        fixture.menus.shutdown();
        assertNull(fixture.menu());
        assertTrue(fixture.tasks.isEmpty());
        clearInvocations(fixture.player);
        dispatched.run();
        verify(fixture.player, never()).getOpenInventory();
        verify(fixture.player, never()).closeInventory();
        assertEquals(0, fixture.writes);
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void cancelThenRejectedReopenClosesOldMenuWithoutWritingOrCharging() {
        fixture.confirmPage();
        fixture.click(49, ClickType.LEFT);
        fixture.menus.interact(interaction(EquipmentSlot.HAND));
        when(fixture.player.getLocation()).thenReturn(new Location(fixture.world, 20, 64, 0));
        fixture.tick();
        assertNull(fixture.menu());
        verify(fixture.player).sendMessage(contains("Stay near the table"));
        verify(fixture.player, times(1)).closeInventory();
        assertEquals(0, fixture.writes);
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void deferredCancelAfterQuitOrDeathDoesNotAccessInventory() throws Exception {
        for (int stop = 0; stop < 2; stop++) {
            fixture = new ServerFixture();
            fixture.confirmPage();
            fixture.click(49, ClickType.LEFT);
            if (stop == 0) {
                fixture.menus.quit(new PlayerQuitEvent(fixture.player, "quit"));
                when(fixture.player.isOnline()).thenReturn(false);
            } else {
                fixture.menus.death(new PlayerDeathEvent(fixture.player, Collections.emptyList(), 0, "death"));
                when(fixture.player.isDead()).thenReturn(true);
            }
            clearInvocations(fixture.player);
            fixture.tick();
            verify(fixture.player, never()).getOpenInventory();
            verify(fixture.player, never()).closeInventory();
            assertEquals(0, fixture.writes);
            assertEquals(1000, Experience.total(fixture.level, fixture.progress));
        }
    }

    @Test
    void shutdownAfterCancelPreservesExternalInventory() {
        fixture.confirmPage();
        fixture.click(49, ClickType.LEFT);
        fixture.player.closeInventory();
        Inventory replacement = fixture.view.getTopInventory();
        clearInvocations(fixture.player);
        fixture.menus.shutdown();
        assertSame(replacement, fixture.view.getTopInventory());
        verify(fixture.player, never()).closeInventory();
        assertTrue(fixture.tasks.isEmpty());
    }

    @Test
    void deferredCancelDoesNotCloseExternalInventory() {
        fixture.confirmPage();
        fixture.click(49, ClickType.LEFT);
        fixture.player.closeInventory();
        Inventory replacement = fixture.view.getTopInventory();
        fixture.tick();
        assertSame(replacement, fixture.view.getTopInventory());
        verify(fixture.player, times(1)).closeInventory();
        assertEquals(0, fixture.writes);
    }

    @Test
    void previouslyCancelledCancelDoesNotInvalidateQueuedConfirmation() {
        fixture.confirmPage();
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        InventoryClickEvent cancel = new InventoryClickEvent(fixture.view, InventoryType.SlotType.CONTAINER, 49,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
        cancel.setCancelled(true);
        fixture.menus.click(cancel);
        fixture.tick();
        assertEquals(1, fixture.writes);
        assertEquals(900, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void deferredContextRejectionExplainsStrictDistancePolicyWithoutRevivingSession() throws Exception {
        for (int stage = 0; stage < 3; stage++) {
            fixture = new ServerFixture();
            if (stage == 0) {
                fixture.menus.interact(interaction(EquipmentSlot.HAND));
            } else if (stage == 1) {
                fixture.open();
                fixture.click(10, ClickType.LEFT);
            } else {
                fixture.confirmPage();
                fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
            }
            when(fixture.player.getLocation()).thenReturn(new Location(fixture.world, -3.05, 64, -3.05));
            Runnable rejected = fixture.tasks.remove();
            rejected.run();
            assertNull(fixture.menu());
            assertEquals(0, fixture.writes);
            assertEquals(1000, Experience.total(fixture.level, fixture.progress));
            verify(fixture.player).sendMessage(contains("Stay near the table"));
            when(fixture.player.getLocation()).thenReturn(new Location(fixture.world, 0.5, 64, 1.5));
            rejected.run();
            assertNull(fixture.menu());
            verify(fixture.player, times(1)).sendMessage(anyString());
        }
    }

    @Test
    void initialOpenAcceptsExactFiveBlockRetentionBoundary() {
        when(fixture.player.getLocation()).thenReturn(new Location(fixture.world, 0.5, 64.5, 5.5));
        fixture.open();
        assertEquals(MenuStage.ENCHANTMENT, fixture.menu().page);
        verify(fixture.player, never()).sendMessage(anyString());
    }

    @Test
    void staleInitialCallbackCannotRejectOrReplaceNewerSession() {
        fixture.menus.interact(interaction(EquipmentSlot.HAND));
        Runnable stale = fixture.tasks.remove();
        fixture.menus.interact(interaction(EquipmentSlot.HAND));
        stale.run();
        verify(fixture.player, never()).sendMessage(anyString());
        fixture.tick();
        assertEquals(MenuStage.ITEM, fixture.menu().page);
        verify(fixture.player, times(1)).openInventory(any(Inventory.class));
    }

    @Test
    void initialOpenIsDeferredAndProtectedInteractionOffhandAdventureAndPermissionsAreRejected() throws Exception {
        PlayerInteractEvent interaction = interaction(EquipmentSlot.HAND);
        fixture.menus.interact(interaction);
        assertTrue(interaction.isCancelled());
        assertNull(fixture.view.getTopInventory().getHolder());
        fixture.menus.shutdown();
        fixture.tick();
        assertNull(fixture.view.getTopInventory().getHolder());
        for (int rejection = 0; rejection < 4; rejection++) {
            fixture = new ServerFixture();
            PlayerInteractEvent event = interaction(rejection == 0 ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND);
            if (rejection == 1) { when(fixture.player.getGameMode()).thenReturn(GameMode.ADVENTURE); }
            if (rejection == 2) { when(fixture.player.hasPermission(EnchantAccess.USE_PERMISSION)).thenReturn(false); }
            if (rejection == 3) {
                event.setCancelled(true);
            }
            fixture.dispatchInteraction(event);
            assertTrue(fixture.tasks.isEmpty());
        }
    }

    @Test
    void swapAndPreviouslyCancelledClicksCannotMutateOrSchedule() {
        fixture.confirmPage();
        PlayerSwapHandItemsEvent swap = new PlayerSwapHandItemsEvent(fixture.player, null, fixture.items.get(0));
        fixture.menus.swap(swap);
        assertTrue(swap.isCancelled());
        InventoryClickEvent event = new InventoryClickEvent(fixture.view, InventoryType.SlotType.CONTAINER, TableMenus.CONFIRM_SLOT, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        event.setCancelled(true);
        fixture.menus.click(event);
        assertTrue(fixture.tasks.isEmpty());
    }

    @Test
    void deathInvalidatesQueuedPurchaseAndDropAndHotbarChangesAreBlocked() {
        fixture.confirmPage();
        PlayerDropItemEvent drop = new PlayerDropItemEvent(fixture.player, mock(Item.class));
        fixture.menus.drop(drop);
        assertTrue(drop.isCancelled());
        PlayerItemHeldEvent held = new PlayerItemHeldEvent(fixture.player, 0, 1);
        fixture.menus.held(held);
        assertTrue(held.isCancelled());
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        fixture.menus.death(new PlayerDeathEvent(fixture.player, Collections.emptyList(), 0, "death"));
        fixture.tick();
        assertEquals(0, fixture.writes);
    }

    @Test
    void takeoverBeforeDeferredOpenDoesNotReplaceAnExternalInventory() {
        fixture.menus.interact(interaction(EquipmentSlot.HAND));
        fixture.player.closeInventory();
        Inventory replacement = fixture.view.getTopInventory();
        fixture.tick();
        assertSame(replacement, fixture.view.getTopInventory());
        assertNull(fixture.view.getTopInventory().getHolder());
        verify(fixture.player, never()).openInventory(any(Inventory.class));
        verify(fixture.player).sendMessage(contains("Something changed"));
    }

    @Test
    void disabledDuringOpenCannotPublishAMenuOrLeaveUsableQueuedWork() {
        when(fixture.player.openInventory(any(Inventory.class))).thenAnswer(call -> {
            fixture.view = fixture.new View(call.getArgument(0));
            fixture.menus.shutdown();
            return fixture.view;
        });
        fixture.open();
        assertNull(fixture.view.getTopInventory().getHolder());
        assertTrue(fixture.tasks.isEmpty());
        assertEquals(0, fixture.writes);
    }

    @Test
    void shutdownStillFencesACallbackEvenIfSchedulerAlreadyDispatchedIt() {
        fixture.confirmPage();
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        Runnable dispatched = fixture.tasks.remove();
        fixture.menus.shutdown();
        dispatched.run();
        assertEquals(0, fixture.writes);
        assertNull(fixture.view.getTopInventory().getHolder());
    }

    @Test
    void offThreadInventoryOperationsFailBeforeMutation() {
        Server server = Bukkit.getServer();
        when(server.isPrimaryThread()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> fixture.menus.interact(interaction(EquipmentSlot.HAND)));
        assertTrue(fixture.tasks.isEmpty());
        assertEquals(0, fixture.writes);
    }

    @Test
    void typeChoicesHideLevelPriceAndBalanceWhileLevelsReviewAndPaymentShareAdaptivePrice() throws Exception {
        fixture = new ServerFixture(ServerFixture.LEGACY_PRICING + "base-xp: 1000\nmax-cost: 1000000\n" +
                "enchantments:\n  DAMAGE_ALL:\n    name: '&bChosen Sharpness'\n    levels:\n      '3': 7777\n");
        fixture.balance(100000);
        ItemStack original = fixture.items.get(0);
        original.addEnchantment(ServerFixture.SHARPNESS, 2);
        ItemMeta meta = original.getItemMeta();
        meta.setDisplayName("My {cost} sword");
        meta.setLore(Collections.singletonList("Original lore"));
        original.setItemMeta(meta);
        fixture.open();
        int choice = sharpnessSlot();
        assertTrue(((EnchantmentStorageMeta) fixture.view.getTopInventory().getItem(choice).getItemMeta())
                .getStoredEnchants().isEmpty());
        ItemStack book = fixture.view.getTopInventory().getItem(choice);
        assertTrue(book.getItemMeta().getDisplayName().contains("Chosen Sharpness"));
        assertFalse(book.getItemMeta().getLore().stream().anyMatch(line -> line.contains("XP")));
        assertFalse(book.getItemMeta().getDisplayName().contains("3"));
        assertFalse(book.getItemMeta().hasItemFlag(ItemFlag.HIDE_ENCHANTS));
        assertTrue(fixture.view.getTopInventory().getItem(TableMenus.PREVIEW_SLOT).getItemMeta()
                .getDisplayName().contains("My {cost} sword"));
        assertEquals("My {cost} sword", original.getItemMeta().getDisplayName());
        assertEquals(Collections.singletonList("Original lore"), original.getItemMeta().getLore());
        assertTrue(fixture.view.getTopInventory().getItem(TableMenus.PREVIEW_SLOT).getItemMeta()
                .hasItemFlag(ItemFlag.HIDE_ATTRIBUTES));
        assertFalse(original.getItemMeta().hasItemFlag(ItemFlag.HIDE_ATTRIBUTES));
        fixture.click(choice, ClickType.LEFT);
        fixture.tick();
        assertEquals(3, fixture.menu().levels.get(10));
        assertStoredBook(10, 3);
        fixture.click(10, ClickType.LEFT);
        fixture.tick();
        assertStoredBook(22, 3);
        assertTrue(fixture.view.getTopInventory().getItem(TableMenus.CONFIRM_SLOT).getItemMeta()
                .getLore().stream().anyMatch(line -> line.contains("3777 raw XP")));
        assertTrue(fixture.view.getTopInventory().getItem(22).getItemMeta().getLore().stream()
                .anyMatch(line -> line.contains("green confirm")));
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        fixture.tick();
        assertEquals(96223, Experience.total(fixture.level, fixture.progress));
        assertEquals(3, fixture.items.get(0).getEnchantmentLevel(ServerFixture.SHARPNESS));
        assertEquals(Collections.singletonList("Original lore"), fixture.items.get(0).getItemMeta().getLore());
        verify(fixture.player).sendMessage(contains("Confirmed " + ChatColor.AQUA + "Chosen Sharpness 3 for 3777 XP."));
        assertEquals(1, fixture.writes);
    }

    @Test
    void concisePackagedInstructionsBalanceAndSuccessHaveOnlyRequestedVisibleText() {
        fixture.openTable();
        ItemMeta instructions = fixture.view.getTopInventory().getItem(23).getItemMeta();
        assertEquals("Choose your item", ChatColor.stripColor(instructions.getDisplayName()));
        assertEquals(Collections.singletonList("\u00bb Left-click one compatible item below."),
                stripped(instructions.getLore()));
        ItemMeta info = fixture.view.getTopInventory().getItem(TableMenus.INFO_SLOT).getItemMeta();
        assertEquals("Balance: 1000 XP", ChatColor.stripColor(info.getDisplayName()));
        assertEquals(Arrays.asList("Bookshelf pricing disabled", "Page: 1 of 1"), stripped(info.getLore()));
        assertEquals(" ", fixture.view.getTopInventory().getItem(0).getItemMeta().getDisplayName());
        fixture.confirmPage();
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        fixture.tick();
        verify(fixture.player).sendMessage(argThat((String line) ->
                ChatColor.stripColor(line).equals("EnchantXP > Confirmed Sharpness 2 for 100 XP.")));
    }

    @Test
    void stackedBookSelectionAndPurchaseSplitOneAndDuplicateOrCancelNeverDoubleClaim() throws Exception {
        for (boolean cancel : new boolean[]{false, true}) {
            fixture = new ServerFixture();
            fixture.items.put(0, new ItemStack(Material.BOOK, 64));
            fixture.confirmPage();
            assertEquals(MenuStage.CONFIRM, fixture.menu().page);
            fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
            fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
            if (cancel) { fixture.click(TableMenus.CANCEL_SLOT, ClickType.LEFT); }
            fixture.tick();
            assertEquals(cancel ? 64 : 63, fixture.items.get(0).getAmount());
            assertEquals(Material.BOOK, fixture.items.get(0).getType());
            assertEquals(cancel ? 1000 : 900, Experience.total(fixture.level, fixture.progress));
            if (cancel) {
                assertNull(fixture.items.get(1));
                assertEquals(0, fixture.writes);
            } else {
                assertEquals(Material.ENCHANTED_BOOK, fixture.items.get(1).getType());
                assertEquals(1, fixture.items.get(1).getAmount());
                assertEquals(2, Enchantments.level(fixture.items.get(1), ServerFixture.SHARPNESS));
                assertEquals(2, fixture.writes);
            }
        }
    }

    @Test
    void fullStorageBlocksStackSelectionWithConfiguredFeedbackButSingleBookStillPurchases() throws Exception {
        fixture = new ServerFixture(ServerFixture.LEGACY_PRICING + "base-xp: 25\nmax-cost: 1000000\nmessages:\n  prefix: ''\n  no-space: 'Leave an empty slot.'\n");
        fixture.items.put(0, new ItemStack(Material.BOOK, 64));
        fixture.fillStorage();
        fixture.openTable();
        MenuHolder item = fixture.menu();
        fixture.select(0, ClickType.LEFT);
        fixture.tick();
        assertSame(item, fixture.menu());
        verify(fixture.player).sendMessage("Leave an empty slot.");
        assertEquals(0, fixture.writes);
        fixture.items.put(0, new ItemStack(Material.BOOK));
        fixture.confirmPage();
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        fixture.tick();
        assertEquals(Material.ENCHANTED_BOOK, fixture.items.get(0).getType());
        assertEquals(900, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void lastEmptySlotTakenAfterConfirmOrSourceAmountChangeNeverChargesOrOverwrites() throws Exception {
        for (boolean changedAmount : new boolean[]{false, true}) {
            fixture = new ServerFixture();
            fixture.items.put(0, new ItemStack(Material.BOOK, 64));
            fixture.fillStorage();
            fixture.items.remove(35);
            fixture.confirmPage();
            fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
            if (changedAmount) {
                fixture.items.get(0).setAmount(63);
            } else {
                fixture.items.put(35, new ItemStack(Material.STONE));
            }
            fixture.tick();
            assertEquals(0, fixture.writes);
            assertEquals(1000, Experience.total(fixture.level, fixture.progress));
            assertEquals(Material.BOOK, fixture.items.get(0).getType());
            verify(fixture.player).sendMessage(contains(changedAmount ? "Something changed" : "Make room"));
            assertNull(fixture.menu());
        }
    }

    @Test
    void retainedSingleItemResultAfterFailedRestoreLogsFailureWithoutRefundOrSuccess() {
        fixture.confirmPage();
        Logger logger = mock(Logger.class);
        when(fixture.plugin.getLogger()).thenReturn(logger);
        IllegalStateException failure = new IllegalStateException("source published then failed");
        IllegalStateException rollback = new IllegalStateException("original restore failed before write");
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            if (first.getAndSet(false)) {
                fixture.items.put(0, call.getArgument(1));
                throw failure;
            }
            throw rollback;
        }).when(fixture.inventory).setItem(eq(0), nullable(ItemStack.class));
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        fixture.tick();
        assertEquals(2, Enchantments.level(fixture.items.get(0), ServerFixture.SHARPNESS));
        assertEquals(900, Experience.total(fixture.level, fixture.progress));
        assertTrue(Arrays.asList(failure.getSuppressed()).contains(rollback));
        verify(logger).log(eq(Level.SEVERE), eq("Enchant transaction failed; attempted rollback; enchantment=DAMAGE_ALL, level=2, chargeRawXp=100, material=DIAMOND_SWORD"), same(failure));
        verify(fixture.player).sendMessage(contains("Enchanting failed"));
        verify(fixture.player, never()).sendMessage(contains("Confirmed"));
        assertNull(fixture.menu());
    }

    @Test
    void failedDestinationClearRetainsPaidBookWithoutDuplicationAndLogsFailureNotSuccess() {
        fixture.items.put(0, new ItemStack(Material.BOOK, 64));
        fixture.confirmPage();
        Logger logger = mock(Logger.class);
        when(fixture.plugin.getLogger()).thenReturn(logger);
        IllegalStateException failure = new IllegalStateException("destination published then failed");
        IllegalStateException rollback = new IllegalStateException("destination rollback failed before clearing");
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            if (first.getAndSet(false)) {
                fixture.items.put(1, call.getArgument(1));
                throw failure;
            }
            throw rollback;
        }).when(fixture.inventory).setItem(eq(1), nullable(ItemStack.class));
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        fixture.tick();
        assertEquals(63, fixture.items.get(0).getAmount());
        assertEquals(Material.ENCHANTED_BOOK, fixture.items.get(1).getType());
        assertEquals(1, fixture.items.get(1).getAmount());
        assertEquals(900, Experience.total(fixture.level, fixture.progress));
        assertTrue(Arrays.asList(failure.getSuppressed()).contains(rollback));
        verify(logger).log(eq(Level.SEVERE), eq("Enchant transaction failed; attempted rollback; enchantment=DAMAGE_ALL, level=2, chargeRawXp=100, material=BOOK"), same(failure));
        verify(fixture.player).sendMessage(contains("Enchanting failed"));
        verify(fixture.player, never()).sendMessage(contains("Confirmed"));
        assertNull(fixture.menu());
    }

    @Test
    void failedStackRollbackIsLoggedWithSuppressedFailuresAndNeverReportsSuccess() {
        fixture.items.put(0, new ItemStack(Material.BOOK, 64));
        fixture.confirmPage();
        Logger logger = mock(Logger.class);
        when(fixture.plugin.getLogger()).thenReturn(logger);
        IllegalStateException failure = new IllegalStateException("destination failure");
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            fixture.items.put(1, call.getArgument(1));
            if (first.getAndSet(false)) { throw failure; }
            throw new IllegalStateException("destination rollback failure");
        }).when(fixture.inventory).setItem(eq(1), nullable(ItemStack.class));
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        fixture.tick();
        assertEquals(1, failure.getSuppressed().length);
        verify(logger).log(eq(Level.SEVERE), contains("attempted rollback"), same(failure));
        verify(fixture.player).sendMessage(contains("Enchanting failed"));
        verify(fixture.player, never()).sendMessage(contains("Confirmed"));
        assertEquals(64, fixture.items.get(0).getAmount());
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
        assertNull(fixture.items.get(1));
    }

    @Test
    void sortCyclesGloballyAcrossBothBookPagesUsingStrippedConfiguredNamesAndCanonicalTies() throws Exception {
        String config = ServerFixture.LEGACY_PRICING + "base-xp: 25\nmax-cost: 1000000\nenchantments:\n" +
                "  DAMAGE_ALL:\n    name: '&czz LAST'\n  DAMAGE_UNDEAD:\n    name: '&aaardvark'\n" +
                "  DURABILITY:\n    name: '&bAARDVARK'\n";
        fixture = new ServerFixture(config);
        fixture.items.put(0, new ItemStack(Material.BOOK, 64));
        fixture.open();
        MenuText text = Settings.read(yaml(config)).text();
        List<Enchantment> defaults = Enchantments.choices(fixture.items.get(0), fixture.prices);
        Comparator<Enchantment> names = (first, second) ->
                String.CASE_INSENSITIVE_ORDER.compare(ChatColor.stripColor(text.name(first)), ChatColor.stripColor(text.name(second)));
        for (int cycle = 0; cycle < 4; cycle++) {
            List<Enchantment> expected = new ArrayList<Enchantment>(defaults);
            if (cycle == 1) { expected.sort(names.thenComparing(Enchantment::getName)); }
            if (cycle == 2) { expected.sort(names.reversed().thenComparing(Enchantment::getName)); }
            assertEquals(0, fixture.menu().index);
            assertEquals(expected.subList(0, 28), enchantmentOrder());
            fixture.click(TableMenus.NEXT_SLOT, ClickType.LEFT);
            fixture.tick();
            List<Enchantment> all = new ArrayList<Enchantment>(expected.subList(0, 28));
            List<Enchantment> second = enchantmentOrder();
            assertEquals(expected.subList(28, 30), second);
            all.addAll(second);
            assertEquals(30, new HashSet<Enchantment>(all).size());
            if (cycle < 3) {
                MenuHolder before = fixture.menu();
                fixture.click(TableMenus.SORT_SLOT, ClickType.LEFT);
                assertSame(before, fixture.menu());
                fixture.tick();
            }
        }
        assertEquals(0, fixture.writes);
    }

    @Test
    void backClearsChoiceContextButRetainsSelectedEnchantmentOnConfirmationReturn() throws Exception {
        fixture = new ServerFixture(ServerFixture.LEGACY_PRICING +
                "base-xp: 1\nmax-cost: 1000000\nui:\n" +
                "  type-lore: ['{enchantment}: {current}/{max}']\n" +
                "  option-lore: ['{enchantment}: {current}/{max} -> {level}']\n");
        ItemStack source = fixture.items.get(0);
        source.addUnsafeEnchantment(ServerFixture.SHARPNESS, 2);
        source.addUnsafeEnchantment(ServerFixture.UNBREAKING, 1);
        ItemStack original = source.clone();
        fixture.open();
        MenuHolder initial = fixture.menu();
        Map<Enchantment, List<String>> initialLore = new HashMap<>();
        initial.choices.forEach((slot, enchantment) ->
                initialLore.put(enchantment, initial.getInventory().getItem(slot).getItemMeta().getLore()));
        assertEquals(Collections.singletonList("Unbreaking: /"), initialLore.get(ServerFixture.UNBREAKING));
        fixture.click(sharpnessSlot(), ClickType.LEFT);
        fixture.tick();
        int levelSlot = fixture.menu().levels.entrySet().stream()
                .filter(entry -> entry.getValue() == 3).findFirst().get().getKey();
        List<String> levelLore = fixture.menu().getInventory().getItem(levelSlot).getItemMeta().getLore();
        assertEquals(Collections.singletonList("Sharpness: 2/5 -> 3"), levelLore);
        fixture.click(levelSlot, ClickType.LEFT);
        fixture.tick();
        assertEquals(MenuStage.CONFIRM, fixture.menu().page);
        fixture.click(TableMenus.BACK_SLOT, ClickType.LEFT);
        fixture.tick();
        assertEquals(MenuStage.LEVEL, fixture.menu().page);
        assertSame(ServerFixture.SHARPNESS, fixture.menu().enchantment);
        assertEquals(levelLore, fixture.menu().getInventory().getItem(levelSlot).getItemMeta().getLore());
        fixture.click(TableMenus.BACK_SLOT, ClickType.LEFT);
        fixture.tick();
        assertEquals(MenuStage.ENCHANTMENT, fixture.menu().page);
        assertEquals(initial.sessionId, fixture.menu().sessionId);
        fixture.menu().choices.forEach((slot, enchantment) ->
                assertEquals(initialLore.get(enchantment), fixture.menu().getInventory().getItem(slot).getItemMeta().getLore()));
        assertEquals(original, source);
        assertSame(source, fixture.items.get(0));
        assertEquals(0, fixture.writes);
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void levelSortIsNumericResetsPaginationAndSurvivesBackAndNewItemSelection() throws Exception {
        fixture = new ServerFixture(ServerFixture.LEGACY_PRICING + "base-xp: 1\nmax-cost: 1000000\n" +
                "enchantments:\n  DAMAGE_ALL:\n    max-level: 35\n");
        fixture.open();
        fixture.click(sharpnessSlot(), ClickType.LEFT);
        fixture.tick();
        fixture.click(TableMenus.NEXT_SLOT, ClickType.LEFT);
        fixture.tick();
        fixture.click(TableMenus.SORT_SLOT, ClickType.LEFT);
        fixture.tick();
        assertEquals(0, fixture.menu().index);
        assertEquals(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
                levelOrder().subList(0, 10));
        fixture.click(TableMenus.SORT_SLOT, ClickType.LEFT);
        fixture.tick();
        assertEquals(35, levelOrder().get(0));
        assertEquals(10, levelOrder().get(25));
        fixture.click(TableMenus.NEXT_SLOT, ClickType.LEFT);
        fixture.tick();
        assertEquals(Arrays.asList(7, 6, 5, 4, 3, 2, 1), levelOrder());
        fixture.click(TableMenus.BACK_SLOT, ClickType.LEFT);
        fixture.tick();
        assertSortLabel("Alphabetical descending");
        fixture.click(TableMenus.BACK_SLOT, ClickType.LEFT);
        fixture.tick();
        assertEquals(MenuStage.ITEM, fixture.menu().page);
        assertEquals(Material.STAINED_GLASS_PANE, fixture.view.getTopInventory().getItem(TableMenus.SORT_SLOT).getType());
        fixture.items.put(17, new ItemStack(Material.DIAMOND_SWORD));
        fixture.select(17, ClickType.LEFT);
        fixture.tick();
        assertSortLabel("Alphabetical descending");
        assertEquals(Arrays.asList(ServerFixture.UNBREAKING, ServerFixture.SMITE, ServerFixture.SHARPNESS),
                enchantmentOrder());
        fixture.click(sharpnessSlot(), ClickType.LEFT);
        fixture.tick();
        assertEquals(35, levelOrder().get(0));
        fixture.click(TableMenus.SORT_SLOT, ClickType.LEFT);
        fixture.tick();
        assertEquals(1, levelOrder().get(0));
        assertSortLabel("Default");
    }

    @Test
    void inertSortDoesNotFenceDispatchedConfirmOrSelectionButBackCancelStillFence() throws Exception {
        for (int stop = 0; stop < 5; stop++) {
            fixture = new ServerFixture();
            fixture.confirmPage();
            fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
            Runnable confirm = fixture.tasks.remove();
            fixture.click(TableMenus.SORT_SLOT, ClickType.LEFT);
            assertTrue(fixture.tasks.isEmpty());
            if (stop == 1) { fixture.click(TableMenus.CANCEL_SLOT, ClickType.LEFT); }
            if (stop == 2) { fixture.click(TableMenus.BACK_SLOT, ClickType.LEFT); }
            if (stop == 3) { fixture.player.closeInventory(); }
            if (stop == 4) { fixture.openTable(); }
            confirm.run();
            fixture.tick();
            assertEquals(stop == 0 ? 1 : 0, fixture.writes, "stop " + stop);
            assertEquals(stop == 0 ? 900 : 1000, Experience.total(fixture.level, fixture.progress));
            if (stop == 0) {
                assertNull(fixture.menu());
            }
            if (stop == 1 || stop == 3) { assertNull(fixture.menu()); }
            if (stop == 2) { assertSortLabel("Default"); }
            if (stop == 4) { assertEquals(MenuStage.ITEM, fixture.menu().page); }
        }
        fixture = new ServerFixture();
        fixture.openTable();
        fixture.select(0, ClickType.LEFT);
        Runnable selection = fixture.tasks.remove();
        fixture.click(TableMenus.SORT_SLOT, ClickType.LEFT);
        assertTrue(fixture.tasks.isEmpty());
        selection.run();
        fixture.tick();
        assertEquals(MenuStage.ENCHANTMENT, fixture.menu().page);
        assertSortLabel("Default");
        assertEquals(0, fixture.writes);
    }

    @Test
    void sortModeIsIsolatedBetweenPlayersUsingTheSameMenus() {
        fixture.open();
        fixture.click(TableMenus.SORT_SLOT, ClickType.LEFT);
        fixture.tick();
        assertSortLabel("Alphabetical ascending");
        Player other = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getItem(0)).thenAnswer(call -> fixture.items.get(0));
        when(inventory.getSize()).thenReturn(41);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        when(other.isOnline()).thenReturn(true);
        when(other.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(other.hasPermission(EnchantAccess.USE_PERMISSION)).thenReturn(true);
        when(other.getInventory()).thenReturn(inventory);
        when(other.getLocation()).thenAnswer(call -> fixture.player.getLocation());
        AtomicReference<InventoryView> view = new AtomicReference<InventoryView>();
        when(other.getOpenInventory()).thenAnswer(call -> view.get());
        when(other.openInventory(any(Inventory.class))).thenAnswer(call -> {
            Inventory top = call.getArgument(0);
            view.set(new InventoryView() {
                @Override public Inventory getTopInventory() { return top; }
                @Override public Inventory getBottomInventory() { return inventory; }
                @Override public Player getPlayer() { return other; }
                @Override public InventoryType getType() { return InventoryType.CHEST; }
            });
            return view.get();
        });
        fixture.menus.interact(new PlayerInteractEvent(other, Action.RIGHT_CLICK_BLOCK, null,
                fixture.block, BlockFace.UP, EquipmentSlot.HAND));
        fixture.tick();
        InventoryClickEvent select = new InventoryClickEvent(view.get(), InventoryType.SlotType.CONTAINER,
                view.get().getTopInventory().getSize() + 27, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        fixture.menus.click(select);
        fixture.tick();
        assertEquals("Cycle sort: Default", ChatColor.stripColor(view.get().getTopInventory()
                .getItem(TableMenus.SORT_SLOT).getItemMeta().getDisplayName()));
        fixture.menus.click(new InventoryClickEvent(view.get(), InventoryType.SlotType.CONTAINER,
                TableMenus.SORT_SLOT, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        fixture.tick();
        fixture.menus.click(new InventoryClickEvent(view.get(), InventoryType.SlotType.CONTAINER,
                TableMenus.SORT_SLOT, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        fixture.tick();
        assertEquals("Cycle sort: Alphabetical descending", ChatColor.stripColor(view.get().getTopInventory()
                .getItem(TableMenus.SORT_SLOT).getItemMeta().getDisplayName()));
        assertSortLabel("Alphabetical ascending");
    }

    private static List<String> stripped(List<String> lines) {
        List<String> result = new ArrayList<String>();
        for (String line : lines) { result.add(ChatColor.stripColor(line)); }
        return result;
    }

    private List<Enchantment> enchantmentOrder() {
        List<Enchantment> result = new ArrayList<Enchantment>();
        fixture.menu().choices.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.add(entry.getValue()));
        return result;
    }

    private List<Integer> levelOrder() {
        List<Integer> result = new ArrayList<Integer>();
        fixture.menu().levels.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.add(entry.getValue()));
        return result;
    }

    private void assertSortLabel(String label) {
        assertEquals("Cycle sort: " + label, ChatColor.stripColor(fixture.view.getTopInventory()
                .getItem(TableMenus.SORT_SLOT).getItemMeta().getDisplayName()));
    }

    @Test
    void all255LevelsPaginateIn28SafeContentSlotsAndLastBookCommitsExplicitUnsafeCap() throws Exception {
        fixture = new ServerFixture(ServerFixture.LEGACY_PRICING + "base-xp: 1\nmax-cost: 1000000\n" +
                "enchantments:\n  DAMAGE_ALL:\n    max-level: 255\n    levels:\n      '255': 77777\n");
        fixture.balance(100000);
        fixture.open();
        fixture.click(sharpnessSlot(), ClickType.LEFT);
        fixture.tick();
        Set<Integer> offered = new HashSet<Integer>();
        for (int page = 0; page < 10; page++) {
            MenuHolder holder = fixture.menu();
            assertEquals(page, holder.index);
            assertEquals(10, holder.pages);
            assertEquals(page == 9 ? 3 : 28, holder.levels.size());
            for (Map.Entry<Integer, Integer> entry : holder.levels.entrySet()) {
                assertTrue(entry.getKey() >= 10 && entry.getKey() <= 43);
                assertTrue(entry.getKey() % 9 >= 1 && entry.getKey() % 9 <= 7);
                assertTrue(offered.add(entry.getValue()), "duplicate level");
                assertStoredBook(entry.getKey(), entry.getValue());
            }
            for (int slot = 0; slot < 54; slot++) {
                assertNotNull(fixture.view.getTopInventory().getItem(slot), "empty slot " + slot);
            }
            assertEquals(Material.BARRIER, fixture.view.getTopInventory().getItem(TableMenus.CANCEL_SLOT).getType());
            assertFalse(holder.levels.containsKey(TableMenus.BACK_SLOT));
            assertFalse(holder.levels.containsKey(TableMenus.PREVIOUS_SLOT));
            assertFalse(holder.levels.containsKey(TableMenus.NEXT_SLOT));
            assertFalse(holder.levels.containsKey(TableMenus.CONFIRM_SLOT));
            if (page < 9) {
                fixture.click(TableMenus.NEXT_SLOT, ClickType.LEFT);
                fixture.tick();
            }
        }
        assertEquals(255, offered.size());
        fixture.click(TableMenus.PREVIOUS_SLOT, ClickType.LEFT);
        fixture.tick();
        assertEquals(8, fixture.menu().index);
        fixture.click(TableMenus.NEXT_SLOT, ClickType.LEFT);
        fixture.tick();
        fixture.click(12, ClickType.LEFT);
        fixture.tick();
        assertEquals(255, fixture.menu().level);
        assertStoredBook(22, 255);
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        fixture.tick();
        assertEquals(22223, Experience.total(fixture.level, fixture.progress));
        assertEquals(255, fixture.items.get(0).getEnchantmentLevel(ServerFixture.SHARPNESS));
        assertEquals(1, fixture.writes);
    }

    @Test
    void confirmThenBackFencesAlreadyDispatchedPaymentAndBackRemainsDeferred() {
        fixture.confirmPage();
        MenuHolder confirmation = fixture.menu();
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        Runnable dispatched = fixture.tasks.remove();
        fixture.click(TableMenus.BACK_SLOT, ClickType.LEFT);
        assertSame(confirmation, fixture.menu());
        dispatched.run();
        assertEquals(0, fixture.writes);
        fixture.tick();
        assertEquals(MenuStage.LEVEL, fixture.menu().page);
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
        fixture.click(11, ClickType.LEFT);
        fixture.tick();
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        fixture.tick();
        assertEquals(1, fixture.writes);
        assertEquals(900, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void cancelDuringQueuedBackStillFencesBothBackAndConfirmAndClosesOnlyNextTick() {
        fixture.confirmPage();
        MenuHolder confirmation = fixture.menu();
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        fixture.click(TableMenus.BACK_SLOT, ClickType.LEFT);
        fixture.click(TableMenus.CANCEL_SLOT, ClickType.LEFT);
        assertSame(confirmation, fixture.menu());
        verify(fixture.player, never()).closeInventory();
        fixture.tick();
        assertNull(fixture.menu());
        assertEquals(0, fixture.writes);
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void backDuringQueuedLevelSelectionFencesChoiceAndReturnsToItemSelectionWithoutEscrow() {
        fixture.open();
        fixture.click(sharpnessSlot(), ClickType.LEFT);
        fixture.click(TableMenus.BACK_SLOT, ClickType.LEFT);
        fixture.tick();
        assertEquals(MenuStage.ITEM, fixture.menu().page);
        assertEquals(0, fixture.writes);
        fixture.select(0, ClickType.LEFT);
        fixture.tick();
        assertEquals(MenuStage.ENCHANTMENT, fixture.menu().page);
    }

    @Test
    void insufficientAndInvalidBalancesUseConfiguredFeedbackAndNeverCharge() throws Exception {
        fixture = new ServerFixture(ServerFixture.LEGACY_PRICING + "base-xp: 1000\nmax-cost: 1000000\n" +
                "messages:\n  prefix: ''\n  insufficient: 'Need {cost}; have {balance}'\n  invalid-balance: 'Bad balance'\n");
        fixture.confirmPage();
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        fixture.tick();
        verify(fixture.player).sendMessage("Need 4000; have 1000");
        assertEquals(0, fixture.writes);
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
        fixture.confirmPage();
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        fixture.progress = Float.NaN;
        fixture.tick();
        verify(fixture.player).sendMessage("Bad balance");
        assertEquals(0, fixture.writes);
    }

    @Test
    void uiCannotOfferPurchasesDisabledByItemCapsOrOverConfiguredPriceLimit() throws Exception {
        fixture = new ServerFixture(ServerFixture.LEGACY_PRICING + "base-xp: 1000\nmax-cost: 1000\n" +
                "items:\n  DIAMOND_SWORD:\n    max-levels:\n      DAMAGE_ALL: 0\n");
        fixture.open();
        assertFalse(fixture.menu().choices.containsValue(ServerFixture.SHARPNESS));
        int unbreaking = fixture.menu().choices.entrySet().stream()
                .filter(entry -> entry.getValue().equals(ServerFixture.UNBREAKING)).findFirst().get().getKey();
        fixture.click(unbreaking, ClickType.LEFT);
        fixture.tick();
        assertEquals(Collections.singleton(1), new HashSet<Integer>(fixture.menu().levels.values()));
    }

    @Test
    void cappedUpgradeUsesDifferenceRatherThanFullCumulativeValue() throws Exception {
        fixture = new ServerFixture(ServerFixture.LEGACY_PRICING + "base-xp: 25\nmax-cost: 100\n");
        fixture.items.get(0).addEnchantment(ServerFixture.SHARPNESS, 1);
        fixture.open();
        int choice = sharpnessSlot();
        fixture.click(choice, ClickType.LEFT);
        fixture.tick();
        assertEquals(Collections.singleton(2), new HashSet<Integer>(fixture.menu().levels.values()));
        assertStoredBook(10, 2);
        fixture.click(10, ClickType.LEFT);
        fixture.tick();
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        fixture.tick();
        assertEquals(925, Experience.total(fixture.level, fixture.progress));
        assertEquals(2, fixture.items.get(0).getEnchantmentLevel(ServerFixture.SHARPNESS));
    }

    @Test
    void rejectedStoredEnchantMetadataFailsClosedInsteadOfDisplayingFakeBookOrTouchingItem() {
        EnchantmentStorageMeta rejected = mock(EnchantmentStorageMeta.class);
        when(Bukkit.getItemFactory().getItemMeta(Material.ENCHANTED_BOOK)).thenReturn(rejected);
        fixture.open();
        fixture.click(sharpnessSlot(), ClickType.LEFT);
        fixture.tick();
        assertNull(fixture.menu());
        assertEquals(0, fixture.writes);
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
        verify(fixture.player).sendMessage(contains("Enchanting failed"));
    }

    @Test
    void rejectedBookMetaApplicationFailsExplicitlyBeforeAnIconCanBeUsed() throws Exception {
        when(Bukkit.getItemFactory().isApplicable(any(EnchantmentStorageMeta.class), eq(Material.ENCHANTED_BOOK))).thenReturn(false);
        MenuText text = Settings.read(yaml(ServerFixture.LEGACY_PRICING + "base-xp: 25\nmax-cost: 1000000\n")).text();
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                text.book(ServerFixture.SHARPNESS, 2, Collections.emptyMap(), "option"));
        assertTrue(failure.getMessage().contains("metadata"));
        assertEquals(0, fixture.writes);
    }

    @Test
    void cosmeticButtonChangesDoNotDefineActionIdentityAndConfirmStyleIsFullyConfigurable() throws Exception {
        fixture = new ServerFixture(ServerFixture.LEGACY_PRICING + "base-xp: 25\nmax-cost: 1000000\nui:\n  icons:\n" +
                "    confirm:\n      material: DIAMOND\n      data: 0\n      name: 'Cancel-looking {cost}'\n" +
                "      lore: ['Custom quote {cost}']\n    cancel:\n      material: EMERALD\n      name: 'Confirm-looking'\n");
        fixture.confirmPage();
        ItemStack icon = fixture.view.getTopInventory().getItem(TableMenus.CONFIRM_SLOT);
        assertEquals(Material.DIAMOND, icon.getType());
        assertEquals("Cancel-looking 100", icon.getItemMeta().getDisplayName());
        assertEquals(Collections.singletonList("Custom quote 100"), icon.getItemMeta().getLore());
        fixture.click(TableMenus.CANCEL_SLOT, ClickType.LEFT);
        fixture.tick();
        assertEquals(0, fixture.writes);
        fixture.confirmPage();
        fixture.click(TableMenus.CONFIRM_SLOT, ClickType.LEFT);
        fixture.tick();
        assertEquals(1, fixture.writes);
        assertEquals(900, Experience.total(fixture.level, fixture.progress));
    }

    private int sharpnessSlot() {
        return fixture.menu().choices.entrySet().stream()
                .filter(entry -> entry.getValue().equals(ServerFixture.SHARPNESS)).findFirst().get().getKey();
    }

    private void assertStoredBook(int slot, int level) {
        ItemStack book = fixture.view.getTopInventory().getItem(slot);
        assertEquals(Material.ENCHANTED_BOOK, book.getType());
        assertTrue(book.getItemMeta() instanceof EnchantmentStorageMeta);
        assertEquals(Collections.singletonMap(ServerFixture.SHARPNESS, level),
                ((EnchantmentStorageMeta) book.getItemMeta()).getStoredEnchants());
    }

    private PlayerInteractEvent interaction(EquipmentSlot hand) {
        return new PlayerInteractEvent(fixture.player, Action.RIGHT_CLICK_BLOCK, fixture.items.get(0), fixture.block, BlockFace.UP, hand);
    }
}
