package io.github.stealingdapenta.enchantxp.menu;

import io.github.stealingdapenta.enchantxp.configuration.values.ConfigValues;
import io.github.stealingdapenta.enchantxp.enchanting.BookshelfPricing;
import io.github.stealingdapenta.enchantxp.enchanting.Prices;
import static io.github.stealingdapenta.enchantxp.testsupport.YamlDocuments.yaml;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookshelfPricingTest {
    @Test
    void balanceLoreShowsEffectiveConfiguredShelfCountOrHonestDisabledStatus() throws Exception {
        for (boolean enabled : new boolean[]{true, false}) {
            ServerFixture fixture = new ServerFixture("base-xp: 25\nmax-cost: -1\nbookshelf-pricing:\n" +
                    "  enabled: " + enabled + "\n  max-bookshelves: 3\n");
            when(fixture.world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
                int x = call.getArgument(0);
                int y = call.getArgument(1);
                int z = call.getArgument(2);
                if (x == 0 && y == 64 && z == 0) {
                    return fixture.block;
                }
                Block block = mock(Block.class);
                when(block.getType()).thenReturn(Math.max(Math.abs(x), Math.abs(z)) == 2 &&
                        (y == 64 || y == 65) ? Material.BOOKSHELF : Material.AIR);
                return block;
            });
            fixture.openTable();
            String lore = String.join("\n", fixture.view.getTopInventory()
                    .getItem(fixture.menu().layout.info).getItemMeta().getLore());
            String plain = ChatColor.stripColor(lore);
            if (enabled) {
                assertTrue(plain.contains("Enlightened by 3/3 bookshelves"), plain);
                assertTrue(lore.contains(ChatColor.GREEN + "3"));
                assertTrue(lore.contains(ChatColor.GOLD + "3"));
            } else {
                assertTrue(plain.contains("Bookshelf pricing disabled"), plain);
                assertFalse(plain.contains("0/"));
                verify(fixture.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
            }
            verify(fixture.world, never()).loadChunk(anyInt(), anyInt());
        }
    }

    private final Map<String, Material> blocks = new HashMap<String, Material>();
    private final Set<String> queried = new HashSet<String>();
    private final Set<String> loaded = new HashSet<String>();
    private World world;
    private BookshelfPricing pricing;

    @BeforeEach
    void setUp() throws Exception {
        new ServerFixture();
        world = mock(World.class);
        pricing = BookshelfPricing.read(ConfigValues.defaults());
        when(world.isChunkLoaded(anyInt(), anyInt())).thenAnswer(call ->
                loaded.contains(call.getArgument(0) + "," + call.getArgument(1)));
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            int x = call.getArgument(0);
            int y = call.getArgument(1);
            int z = call.getArgument(2);
            assertTrue(y >= 0 && y <= 255, "Never read outside legacy world height");
            assertTrue(loaded.contains((x >> 4) + "," + (z >> 4)), "Never read an unloaded chunk");
            String key = key(x, y, z);
            queried.add(key);
            Block block = mock(Block.class);
            when(block.getType()).thenReturn(blocks.getOrDefault(key, Material.AIR));
            return block;
        });
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                loaded.add(x + "," + z);
            }
        }
    }

    @Test
    void exactLegacyRingHas32UniqueShelfPositionsAndCapsAt15() {
        Location table = new Location(world, 0, 64, 0);
        assertEquals(0, pricing.count(table));
        Set<String> candidates = new HashSet<String>();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (Math.max(Math.abs(x), Math.abs(z)) == 2) {
                    for (int y = 64; y <= 65; y++) {
                        candidates.add(key(x, y, z));
                    }
                }
            }
        }
        assertEquals(32, candidates.size());
        for (String candidate : candidates) {
            blocks.clear();
            blocks.put(candidate, Material.BOOKSHELF);
            assertEquals(1, pricing.count(table), candidate + " is counted once, including diagonal corners");
        }
        blocks.clear();
        int count = 0;
        for (String candidate : candidates) {
            blocks.put(candidate, Material.BOOKSHELF);
            assertEquals(Math.min(++count, 15), pricing.count(table));
        }
        assertTrue(queried.containsAll(candidates));
        verify(world, never()).loadChunk(anyInt(), anyInt());
    }

    @Test
    void bothGapHeightsMustBeAirAndDiagonalGapGatesItsThreeColumns() {
        Location table = new Location(world, 0, 64, 0);
        for (int y = 64; y <= 65; y++) {
            blocks.clear();
            blocks.put(key(2, 64, 2), Material.BOOKSHELF);
            blocks.put(key(2, 65, 1), Material.BOOKSHELF);
            blocks.put(key(1, 64, 2), Material.BOOKSHELF);
            blocks.put(key(2, 64, 0), Material.BOOKSHELF);
            assertEquals(4, pricing.count(table));
            blocks.put(key(1, y, 1), Material.TORCH);
            assertEquals(1, pricing.count(table));
        }
    }

    @Test
    void unloadedNegativeNeighborChunksAndOutOfHeightCoordinatesAreNeverRead() {
        loaded.clear();
        loaded.add("-1,-1");
        blocks.put(key(-3, 64, -1), Material.BOOKSHELF);
        assertEquals(1, pricing.count(new Location(world, -1, 64, -1)));
        verify(world, atLeastOnce()).isChunkLoaded(-1, -1);
        verify(world, atLeastOnce()).isChunkLoaded(0, -1);
        queried.clear();
        blocks.clear();
        blocks.put(key(-3, 255, -1), Material.BOOKSHELF);
        assertEquals(0, pricing.count(new Location(world, -1, 255, -1)));
        assertEquals(0, pricing.count(new Location(world, -1, -1, -1)));
        assertEquals(0, pricing.count(new Location(world, -1, 256, -1)));
    }

    @Test
    void disabledPricingIsBaseAndDoesNotReadWorldOrRequireServerThread() throws Exception {
        YamlConfiguration config = yaml("bookshelf-pricing:\n  enabled: false\n");
        BookshelfPricing disabled = BookshelfPricing.read(config);
        when(Bukkit.getServer().isPrimaryThread()).thenReturn(false);
        assertEquals(0, disabled.count(null));
        assertEquals(17, disabled.modify(17, 0));
        verifyNoInteractions(world);
    }

    @Test
    void scansRequireServerThreadAndWholePercentSettingsAreBounded() throws Exception {
        when(Bukkit.getServer().isPrimaryThread()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> pricing.count(new Location(world, 0, 64, 0)));
        for (String invalid : new String[]{"max-bookshelves: 0", "max-bookshelves: 16",
                "zero-shelf-penalty-percent: 1.5", "zero-shelf-penalty-percent: -1",
                "per-bookshelf-reduction-percent: 1000001", "enabled: 'true'", "typo: 1"}) {
            assertThrows(IllegalArgumentException.class, () ->
                    BookshelfPricing.read(yaml("bookshelf-pricing:\n  " + invalid + "\n")));
        }
    }

    @Test
    void modifierRoundsUpgradeDifferenceOnceAndNeverOverflowsOrMakesItFree() throws Exception {
        assertEquals(250, pricing.modify(100, 0));
        assertEquals(240, pricing.modify(100, 1));
        assertEquals(100, pricing.modify(100, 15));
        assertEquals(100, pricing.modify(100, 32));
        Prices prices = Prices.read(yaml("base-xp: 1\nmax-cost: 1000000\n"));
        long difference = prices.upgradeCost(ServerFixture.SHARPNESS, 1, 2);
        assertEquals(8, pricing.modify(difference, 0));
        assertNotEquals(pricing.modify(4, 0) - pricing.modify(1, 0), pricing.modify(difference, 0));
        BookshelfPricing extreme = BookshelfPricing.read(yaml(
                "bookshelf-pricing:\n  zero-shelf-penalty-percent: 1000000\n"));
        assertEquals(650315025000000L, extreme.modify(65025000000L, 0));
        assertEquals(-1, extreme.modify(Long.MAX_VALUE, 0));
        assertEquals(-1, extreme.modify(0, 0));
        BookshelfPricing floor = BookshelfPricing.read(yaml(
                "bookshelf-pricing:\n  per-bookshelf-reduction-percent: 1000000\n  max-bookshelves: 1\n"));
        assertEquals(7, floor.modify(7, 32));
    }

    @Test
    void explicitCumulativeValuesArePureAndMaxCostIsAppliedAfterShelfModifier() throws Exception {
        ServerFixture fixture = new ServerFixture("base-xp: 1\nmax-cost: 7\n" +
                "enchantments:\n  DAMAGE_ALL:\n    levels:\n      '2': 5\n");
        fixture.items.get(0).addEnchantment(ServerFixture.SHARPNESS, 1);
        assertEquals(4, fixture.prices.upgradeCost(ServerFixture.SHARPNESS, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> PurchaseQuote.read(fixture.player, fixture.session(),
                ServerFixture.SHARPNESS, 2, fixture.prices, pricing, 0));
        assertEquals(4, PurchaseQuote.read(fixture.player, fixture.session(), ServerFixture.SHARPNESS, 2,
                fixture.prices, pricing, 15).cost);
        fixture.open();
        assertFalse(fixture.menu().choices.containsValue(ServerFixture.SHARPNESS));
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }
}
