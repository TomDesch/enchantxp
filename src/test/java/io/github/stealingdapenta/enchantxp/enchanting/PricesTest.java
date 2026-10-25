package io.github.stealingdapenta.enchantxp.enchanting;

import io.github.stealingdapenta.enchantxp.menu.ServerFixture;
import static io.github.stealingdapenta.enchantxp.testsupport.YamlDocuments.yaml;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PricesTest {
    @Test
    void disabledConfiguredCeilingStillRejectsUnrepresentableRawXpAndInvalidLevels() throws Exception {
        Prices prices = Prices.read(yaml("base-xp: 25000\nmax-cost: -1\n"));
        assertEquals(122500000, prices.cost(70));
        assertTrue(prices.offersUpgrade(null, 0, 70));
        assertTrue(prices.accepts(Integer.MAX_VALUE));
        assertFalse(prices.accepts((long) Integer.MAX_VALUE + 1));
        assertFalse(prices.offers(256));
        Prices expensive = Prices.read(yaml("base-xp: 1000000\nmax-cost: -1\n"));
        assertFalse(expensive.offers(255));
        assertTrue(expensive.offersUpgrade(null, 254, 255));
        for (String invalid : new String[]{"-2", "0", "'-1'", "1.5"}) {
            assertThrows(IllegalArgumentException.class, () ->
                    Prices.read(yaml("base-xp: 1\nmax-cost: " + invalid + "\n")));
        }
    }

    @Test
    void bundledDefaultsProduceDocumentedQuadraticCosts() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        Prices prices = Prices.read(yaml);
        assertEquals(10000, prices.cost(1));
        assertEquals(40000, prices.cost(2));
        assertEquals(250000, prices.cost(5));
    }

    @Test
    void upgradeQuotesCreditCurrentConfiguredValueBeforeApplyingPurchaseCap() throws Exception {
        Prices prices = Prices.read(yaml("base-xp: 10000\nmax-cost: 1000000\n"));
        assertEquals(50000, prices.upgradeCost(null, 2, 3));
        assertFalse(prices.offers(11));
        assertEquals(210000, prices.upgradeCost(null, 10, 11));
        for (int[] pair : new int[][]{{-1, 2}, {256, 2}, {0, 0}, {2, 2}, {3, 2}, {1, 256}}) {
            assertFalse(prices.offersUpgrade(null, pair[0], pair[1]));
            assertThrows(IllegalArgumentException.class, () -> prices.upgradeCost(null, pair[0], pair[1]));
        }
        new ServerFixture();
        Prices overrides = Prices.read(yaml("base-xp: 2000\nmax-cost: 1000000\n" +
                "enchantments:\n  DAMAGE_ALL:\n    levels:\n      '2': 9000\n      '3': 12000\n"));
        assertEquals(3000, overrides.upgradeCost(ServerFixture.SHARPNESS, 2, 3));
        Enchantment fortune = mock(Enchantment.class);
        when(fortune.getName()).thenReturn("LOOT_BONUS_BLOCKS");
        assertEquals(50000, prices.upgradeCost(fortune, 2, 3));
    }

    @Test
    void nonmonotonicCumulativeOverridesFailAtTheirSpecificPath() throws Exception {
        for (int value : new int[]{7777, 8000, 32000}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                    Prices.read(yaml("base-xp: 2000\nmax-cost: 1000000\n" +
                            "enchantments:\n  DAMAGE_ALL:\n    levels:\n      '3': " + value + "\n")));
            assertTrue(failure.getMessage().contains("enchantments.DAMAGE_ALL.levels.3"));
        }
    }

    @Test
    void pricesAreImmutableAndOverLimitChoicesAreNotDiscounted() throws Exception {
        YamlConfiguration yaml = yaml("base-xp: 25\nmax-cost: 100\n");
        Prices prices = Prices.read(yaml);
        yaml.set("base-xp", 1);
        assertEquals(100, prices.cost(2));
        assertFalse(prices.offers(3));
        assertThrows(IllegalArgumentException.class, () -> prices.cost(3));
        assertThrows(IllegalArgumentException.class, () -> prices.cost(Integer.MAX_VALUE));
        assertFalse(prices.offers(0));
    }

    @Test
    void validationRejectsMissingFractionalCoercedNegativeAndOverflowValues() throws Exception {
        for (String value : new String[]{"0", "-1", "1.5", "'25'", "true", "null", "1000001", "9223372036854775807", "[]"}) {
            YamlConfiguration config = yaml("base-xp: " + value + "\nmax-cost: 1000000\n");
            assertThrows(IllegalArgumentException.class, () -> Prices.read(config), value);
        }
        assertThrows(IllegalArgumentException.class, () -> Prices.read(yaml("base-xp: 25\n")));
        assertThrows(IllegalArgumentException.class, () -> Prices.read(yaml("base-xp: 26\nmax-cost: 25\n")));
        assertThrows(IllegalArgumentException.class, () -> Prices.read(yaml("base-xp: 1\nmax-cost: 1000001\n")));
        Prices limit = Prices.read(yaml("base-xp: 1000000\nmax-cost: 1000000\n"));
        assertEquals(1000000, limit.cost(1));
        assertFalse(limit.offers(5));
    }

}
