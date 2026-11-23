package io.github.stealingdapenta.enchantxp.configuration;

import io.github.stealingdapenta.enchantxp.configuration.values.ConfigValues;
import io.github.stealingdapenta.enchantxp.enchanting.Enchantments;
import io.github.stealingdapenta.enchantxp.menu.ServerFixture;
import io.github.stealingdapenta.enchantxp.presentation.MenuStage;
import io.github.stealingdapenta.enchantxp.presentation.MenuText;
import io.github.stealingdapenta.enchantxp.presentation.LoreStyle;
import static io.github.stealingdapenta.enchantxp.testsupport.YamlDocuments.yaml;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SettingsTest {
    @Test
    void blankAndFormattingOnlyRolesResetInheritedColorsInEditorAndTable() throws Exception {
        for (String role : Arrays.asList("", "&l")) {
            String expected = ChatColor.RESET + ChatColor.translateAlternateColorCodes('&', role);
            YamlConfiguration document = yaml("base-xp: 25\nmax-cost: -1\n");
            document.set("lore-style.number-color", role);
            document.set("lore-style.action-color", role);
            document.set("lore-style.action-prefix", "");
            document.set("ui.option-lore", Arrays.asList("{label}Value: {number}10", "{number}10", "{action}Choose"));
            List<String> table = Settings.read(document).text().lore("ui.option-lore", Collections.emptyMap());
            assertEquals(Arrays.asList(ChatColor.AQUA + "Value: " + expected + "10", expected + "10", expected + "Choose"), table);
            List<String> editor = LoreStyle.read(document).editorLines(Arrays.asList(LoreStyle.number("Value", "10"), "{number}10", LoreStyle.action("Choose")));
            assertTrue(editor.get(0).endsWith(expected + "10"));
            assertEquals(expected + "10", editor.get(1));
            assertEquals(expected + "Choose", editor.get(2));
            document.set("lore-style.label-color", role);
            document.set("lore-style.text-color", role);
            assertEquals(expected + "Value: " + expected + "literal {action}&6",
                    LoreStyle.read(document).editorLines(Collections.singletonList(LoreStyle.info("Value", "literal {action}&6"))).get(0));
            document.set("ui.option-lore", Arrays.asList("&a{label}Label: {text}plain", "{label}Label", "{text}plain"));
            assertEquals(Arrays.asList(ChatColor.GREEN + expected + "Label: " + expected + "plain", expected + "Label", expected + "plain"),
                    Settings.read(document).text().lore("ui.option-lore", Collections.emptyMap()));
            assertEquals(Arrays.asList(expected + "Label", expected + "plain"),
                    LoreStyle.read(document).editorLines(Arrays.asList("{label}Label", "{text}plain")));
            document.set("lore-style.action-prefix", "&c! ");
            document.set("ui.option-lore", Collections.singletonList("{action}Choose"));
            String action = expected + ChatColor.RED + "! " + expected + "Choose";
            assertEquals(Collections.singletonList(action), Settings.read(document).text().lore("ui.option-lore", Collections.emptyMap()));
            assertEquals(Collections.singletonList(action),
                    LoreStyle.read(document).editorLines(Collections.singletonList(LoreStyle.action("Choose"))));
        }
    }

    @Test
    void actionPrefixBoundsCountVisibleCharactersRatherThanValidColorCodes() throws Exception {
        YamlConfiguration document = yaml("");
        document.set("lore-style.action-prefix", "&a12345678&l12345678");
        assertNotNull(LoreStyle.read(document));
        assertEquals(2, LoreStyle.read(document).editorLines(Collections.singletonList(
                LoreStyle.action("Left-click to choose an enchantment from this list."))).size());
        document.set("lore-style.action-prefix", "12345678901234567");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> LoreStyle.read(document));
        assertTrue(failure.getMessage().contains("lore-style.action-prefix"));
        assertTrue(failure.getMessage().contains("16 visible"));
        document.set("lore-style.action-prefix", "&q123456789012345");
        assertThrows(IllegalArgumentException.class, () -> LoreStyle.read(document));
        document.set("lore-style.action-prefix", "");
        assertNotNull(LoreStyle.read(document));
    }

    @Test
    void prefixOnlyOverridePreservesInheritedStageAndInputLoreAndSupportsBlankPrefix() throws Exception {
        for (String prefix : Arrays.asList("Go: ", "")) {
            YamlConfiguration document = yaml("base-xp: 25\nmax-cost: -1\n");
            document.set("lore-style.action-prefix", prefix);
            document.set("lore-style.action-color", "&d");
            String before = document.saveToString();
            MenuText text = Settings.read(document).text();
            assertEquals(Collections.singletonList(ChatColor.LIGHT_PURPLE + prefix + "Left-click one compatible item below."),
                    text.icon("instructions", Collections.emptyMap()).getItemMeta().getLore());
            assertEquals(Collections.singletonList(ChatColor.LIGHT_PURPLE + prefix + "Left-click to choose enchantment"),
                    text.lore("ui.type-lore", Collections.emptyMap()));
            for (String stage : Arrays.asList("option", "review", "selection")) {
                assertFalse(text.lore("ui." + stage + "-lore", Collections.emptyMap()).isEmpty());
            }
            assertEquals(before, document.saveToString());
            assertFalse(document.contains("ui", true));
        }
    }

    @Test
    void explicitLoreRolesDoNotClassifyCustomProseOrRescanInsertedValues() throws Exception {
        Settings settings = settings("lore-style:\n  action-prefix: 'Do: '\n  label-color: '&3'\n  number-color: '&6'\n" +
                "ui:\n  option-lore: ['{label}Price: {number}{cost}', '{action}Choose {item}', '&7Left-click custom prose']\n");
        Map<String, String> values = new HashMap<>();
        values.put("cost", "10");
        values.put("item", "{action}&a{cost}");
        assertEquals(Arrays.asList(ChatColor.DARK_AQUA + "Price: " + ChatColor.GOLD + "10",
                ChatColor.GREEN + "Do: Choose {action}&a{cost}", ChatColor.GRAY + "Left-click custom prose"),
                settings.text().lore("ui.option-lore", values));
        for (String invalid : Arrays.asList("messages:\n  success: '{action}'", "ui:\n  option-name: '{label}'",
                "lore-style:\n  action-prefix: '{cost}'", "lore-style:\n  action-color: 'green'",
                "lore-style:\n  action-prefix: 12", "lore-style:\n  unknown: ''", "lore-style: []")) {
            assertThrows(IllegalArgumentException.class, () -> settings(invalid), invalid);
        }
    }

    @Test
    void operatorWrapPreservesLiteralValuesRoleColorsAndInformationBoundaries() throws Exception {
        LoreStyle style = LoreStyle.read(yaml(""));
        String value = String.join(" ", Collections.nCopies(12, "literal"));
        List<String> wrapped = style.editorLines(Arrays.asList(LoreStyle.info("Value", value),
                "{label}Placeholders:", "- {action}", LoreStyle.info("Text", "{action}&6{cost}")));
        assertTrue(wrapped.get(1).startsWith(ChatColor.GRAY.toString()));
        assertTrue(wrapped.contains(ChatColor.GRAY + "" + ChatColor.AQUA + "Placeholders:"));
        assertTrue(wrapped.contains(ChatColor.GRAY + "- {action}"));
        assertTrue(wrapped.contains(ChatColor.GRAY + "" + ChatColor.AQUA + "Text: " + ChatColor.GRAY + "{action}&6{cost}"));
    }

    @Test
    void schemaIsRequiredTypedAndBuildDefinedWithoutChangingOperatorDocument() throws Exception {
        for (Object version : Arrays.asList(null, "1", 1.0, true, 0, 2)) {
            YamlConfiguration document = yaml("base-xp: 25\nmax-cost: 1000000\n");
            document.set("config-version", version);
            document.set("permission.enabled", true);
            String before = document.saveToString();
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> Settings.read(document));
            assertTrue(failure.getMessage().contains("config-version"));
            assertTrue(failure.getMessage().contains("upgrade"));
            assertEquals(before, document.saveToString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"permission", "typo", "Permissions"})
    void unknownRootKeysFailWithTheirExactCaseSensitivePathsWithoutChangingDocument(String key) throws Exception {
        YamlConfiguration document = yaml("base-xp: 25\nmax-cost: 1000000\n" + key + ":\n  enabled: true\n");
        String before = document.saveToString();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> Settings.read(document));
        assertEquals(key + " is unknown", failure.getMessage());
        assertEquals(before, document.saveToString());
    }

    @Test
    void deletingPackagedFortuneBaseOrSectionFallsBackToOperatorGlobal() throws Exception {
        new ServerFixture();
        Enchantment fortune = Enchantment.getByName("LOOT_BONUS_BLOCKS");
        for (String path : Arrays.asList("enchantments.LOOT_BONUS_BLOCKS.base-xp", "enchantments.LOOT_BONUS_BLOCKS")) {
            YamlConfiguration yaml = ConfigValues.defaults();
            yaml.set("base-xp", 123);
            yaml.set(path, null);
            Settings settings = Settings.read(yaml);
            assertEquals(1107, settings.prices().cost(fortune, 3), path);
            assertFalse(yaml.contains(path, true));
        }
    }

    @Test
    void deletedAndExplicitEmptyLoreRenderNoLoreButWhollyAbsentIconKeepsDefaults() throws Exception {
        new ServerFixture();
        for (boolean deleted : new boolean[]{false, true}) {
            YamlConfiguration yaml = ConfigValues.defaults();
            yaml.set("ui.icons.info.lore", deleted ? null : Collections.emptyList());
            yaml.set("ui.review-lore", deleted ? null : Collections.emptyList());
            MenuText text = Settings.read(yaml).text();
            assertFalse(text.icon("info", Collections.emptyMap()).getItemMeta().hasLore());
            assertFalse(text.book(ServerFixture.SHARPNESS, 2, Collections.emptyMap(), "review")
                    .getItemMeta().hasLore());
        }
        MenuText text = settings("").text();
        assertTrue(text.icon("info", Collections.emptyMap()).getItemMeta().hasLore());
        assertFalse(settings("ui:\n  icons:\n    info:\n      name: 'Custom'\n")
                .text().icon("info", Collections.emptyMap()).getItemMeta().hasLore());
        MenuText titleOnly = settings("ui:\n  titles:\n    item: 'Custom title'\n").text();
        for (String stage : Arrays.asList("option", "type", "review", "selection")) {
            assertTrue(titleOnly.lore("ui." + stage + "-lore", Collections.emptyMap()).isEmpty());
        }
        assertTrue(titleOnly.icon("info", Collections.emptyMap()).getItemMeta().hasLore());
        MenuText nullLore = settings("ui:\n  option-lore: null\n  type-lore: null\n" +
                "  review-lore: null\n  selection-lore: null\n  icons:\n    info:\n      lore: null\n").text();
        for (String stage : Arrays.asList("option", "type", "review", "selection")) {
            assertTrue(nullLore.lore("ui." + stage + "-lore", Collections.emptyMap()).isEmpty());
        }
        assertFalse(nullLore.icon("info", Collections.emptyMap()).getItemMeta().hasLore());
    }

    private ServerFixture fixture;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new ServerFixture();
    }

    @Test
    void partialOperatorYamlPreservesExplicitOldPricingAndMergesOnlyOptionalLeaves() throws Exception {
        YamlConfiguration yaml = yaml("base-xp: 25\nmax-cost: 1000000\n" +
                "ui:\n  option-lore: []\n  icons:\n    accent:\n      data: 0\n  titles:\n    item: ''\n" +
                "messages:\n  unsupported: ''\nenchantments:\n  DAMAGE_ALL:\n    max-level: 0\n");
        Settings settings = Settings.read(yaml);
        Map<String, String> values = Collections.singletonMap("balance", "123");
        assertEquals(25, settings.prices().cost(ServerFixture.SHARPNESS, 1));
        assertEquals(0, settings.policy().maximum(Material.DIAMOND_SWORD, ServerFixture.SHARPNESS));
        assertEquals("", settings.text().title(MenuStage.ITEM, values));
        assertTrue(settings.text().lore("ui.option-lore", values).isEmpty());
        assertEquals(0, settings.text().icon("accent", values).getDurability());
        assertEquals(Material.STAINED_GLASS_PANE, settings.text().icon("accent", values).getType());
        assertTrue(settings.text().icon("info", values).getItemMeta().getDisplayName().contains("123"));
        settings.text().send(fixture.player, "unsupported", values);
        verify(fixture.player, never()).sendMessage(anyString());
        assertFalse(yaml.contains("ui.icons.accent.material", true));
        assertFalse(yaml.contains("messages.prefix", true));
    }

    @Test
    void sortButtonTemplatesAreConfigurableAndSortTokenIsRestrictedToThatButton() throws Exception {
        Settings settings = settings("ui:\n  icons:\n    sort:\n      name: 'Order {sort}'\n      lore: ['Now {sort}']\n");
        Map<String, String> values = Collections.singletonMap("sort", "Level descending");
        ItemStack icon = settings.text().icon("sort", values);
        assertEquals("Order Level descending", icon.getItemMeta().getDisplayName());
        assertEquals(Arrays.asList("Now Level descending"), icon.getItemMeta().getLore());
        for (String invalid : Arrays.asList(
                "ui:\n  titles:\n    item: '{sort}'",
                "ui:\n  option-name: '{sort}'",
                "messages:\n  success: '{sort}'",
                "ui:\n  icons:\n    cancel:\n      lore: ['{sort}']")) {
            assertThrows(IllegalArgumentException.class, () -> settings(invalid));
        }
    }

    @Test
    void snapshotCopiesNestedOperatorListsAndMaps() throws Exception {
        YamlConfiguration yaml = yaml("base-xp: 1000\nmax-cost: 1000000\n" +
                "ui:\n  option-lore: ['Saved {cost}']\n" +
                "enchantments:\n  DAMAGE_ALL:\n    max-level: 9\n    levels:\n      '3': 9333\n" +
                "items:\n  DIAMOND_SWORD:\n    max-levels:\n      DAMAGE_ALL: 7\n");
        Settings settings = Settings.read(yaml);
        yaml.set("ui.option-lore", Arrays.asList("Changed"));
        yaml.set("enchantments.DAMAGE_ALL.levels.3", 1);
        yaml.set("items.DIAMOND_SWORD.max-levels.DAMAGE_ALL", 0);
        assertEquals(Arrays.asList("Saved 9333"),
                settings.text().lore("ui.option-lore", Collections.singletonMap("cost", "9333")));
        assertEquals(9333, settings.prices().cost(ServerFixture.SHARPNESS, 3));
        assertEquals(7, settings.policy().maximum(Material.DIAMOND_SWORD, ServerFixture.SHARPNESS));
    }

    @Test
    void malformedTypesUnknownNamesAndUnknownKeysFailWithTheirSpecificPaths() throws Exception {
        String[][] cases = {
                {"ui: []", "ui"},
                {"ui:\n  rows: 2", "ui.rows"},
                {"ui:\n  rows: 7", "ui.rows"},
                {"ui:\n  rows: 3.5", "ui.rows"},
                {"ui:\n  rows: '3'", "ui.rows"},
                {"ui:\n  rows: true", "ui.rows"},
                {"ui:\n  rows: []", "ui.rows"},
                {"ui:\n  titles: ''", "ui.titles"},
                {"ui:\n  titles:\n    item: 1", "ui.titles.item"},
                {"ui:\n  option-lore: 'not a list'", "ui.option-lore"},
                {"ui:\n  option-lore: ''", "ui.option-lore"},
                {"ui:\n  option-lore: [true]", "ui.option-lore"},
                {"ui:\n  unknown: ''", "ui.unknown"},
                {"ui:\n  icons:\n    fill:\n      material: PURPLE_STAINED_GLASS_PANE", "ui.icons.fill.material"},
                {"ui:\n  icons:\n    fill:\n      material: AIR", "ui.icons.fill.material"},
                {"ui:\n  icons:\n    fill:\n      data: '15'", "ui.icons.fill.data"},
                {"ui:\n  icons:\n    fill:\n      data: -1", "ui.icons.fill.data"},
                {"messages:\n  typo: ''", "messages.typo"},
                {"messages:\n  success: false", "messages.success"},
                {"enchantments:\n  SHARPNESS:\n    max-level: 5", "enchantments.SHARPNESS"},
                {"enchantments:\n  DAMAGE_ALL:\n    typo: 1", "enchantments.DAMAGE_ALL.typo"},
                {"enchantments:\n  DAMAGE_ALL:\n    enabled: 'false'", "enchantments.DAMAGE_ALL.enabled"},
                {"enchantments:\n  DAMAGE_ALL:\n    max-level: 256", "enchantments.DAMAGE_ALL.max-level"},
                {"enchantments:\n  DAMAGE_ALL:\n    max-level: -1", "enchantments.DAMAGE_ALL.max-level"},
                {"enchantments:\n  DAMAGE_ALL:\n    max-level: 1.5", "enchantments.DAMAGE_ALL.max-level"},
                {"enchantments:\n  DAMAGE_ALL:\n    name: '{level}'", "enchantments.DAMAGE_ALL.name"},
                {"items:\n  NETHERITE_SWORD:\n    max-levels: {}", "items.NETHERITE_SWORD"},
                {"items:\n  DIAMOND_SWORD:\n    max-level: 4", "items.DIAMOND_SWORD.max-level"},
                {"items:\n  DIAMOND_SWORD:\n    max-levels:\n      SHARPNESS: 4", "items.DIAMOND_SWORD.max-levels.SHARPNESS"},
                {"items:\n  DIAMOND_SWORD:\n    max-levels:\n      DAMAGE_ALL: '4'", "items.DIAMOND_SWORD.max-levels.DAMAGE_ALL"}
        };
        for (String[] example : cases) {
            YamlConfiguration yaml = yaml("base-xp: 1000\nmax-cost: 1000000\n" + example[0] + "\n");
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> Settings.read(yaml), example[0]);
            assertTrue(failure.getMessage().contains(example[1]), failure.getMessage());
        }
        assertThrows(InvalidConfigurationException.class, () -> yaml("ui: [broken\n"));
        assertThrows(IllegalArgumentException.class, () -> Settings.read(yaml("max-cost: 1000000\n")));
    }

    @Test
    void messageTokensAreValidatedPerTemplateAndInsertedValuesAreNeverRescanned() throws Exception {
        for (String invalid : Arrays.asList(
                "messages:\n  prefix: '{prefix}'",
                "messages:\n  prefix: '{player}'",
                "messages:\n  context: '{cost}'",
                "messages:\n  success: '{path}'",
                "messages:\n  success: '{unknown}'",
                "ui:\n  titles:\n    item: '{unknown}'",
                "ui:\n  titles:\n    item: '{cost}'",
                "ui:\n  selection-name: '{level}'",
                "ui:\n  option-lore: ['{broken']")) {
            assertThrows(IllegalArgumentException.class, () -> settings(invalid), invalid);
        }
        Settings settings = settings("messages:\n  success: '{prefix}{player}: {enchantment} {cost}'\n" +
                "  prefix: '&5Prefix '\n");
        when(fixture.player.getName()).thenReturn("{cost}&aPlayer");
        Map<String, String> values = new HashMap<String, String>();
        values.put("cost", "9000");
        values.put("enchantment", "{player}");
        settings.text().send(fixture.player, "success", values);
        verify(fixture.player).sendMessage(ChatColor.DARK_PURPLE + "Prefix {cost}&aPlayer: {player} 9000");
        Settings reload = settings("messages:\n  reload-failure: '{prefix}File {path}'\n");
        reload.text().send(fixture.player, "reload-failure", Collections.singletonMap("path", "config{cost}.yml"));
        verify(fixture.player).sendMessage(contains("File config{cost}.yml"));
    }

    @Test
    void titlePolicyBoundsRenderedCodeUnitsAndNeverLeavesDanglingColorPrefix() throws Exception {
        Settings settings = settings("ui:\n  titles:\n    item: '1234567890123456789012345678901&a{player}'\n");
        String title = settings.text().title(MenuStage.ITEM, Collections.singletonMap("player", "longname"));
        assertEquals("1234567890123456789012345678901", title);
        Settings inserted = settings("ui:\n  titles:\n    item: '{player}'\n");
        assertEquals(32, inserted.text().title(MenuStage.ITEM,
                Collections.singletonMap("player", "1234567890123456789012345678901234567890")).length());
    }

    @Test
    void pricingUsesSparseExplicitThenEnchantBaseThenGlobalAndKeepsOldExplicitBase() throws Exception {
        Settings settings = settings("enchantments:\n  DAMAGE_ALL:\n    base-xp: 2000\n    levels:\n      '3': 12000\n");
        assertEquals(2000, settings.prices().cost(ServerFixture.SHARPNESS, 1));
        assertEquals(8000, settings.prices().cost(ServerFixture.SHARPNESS, 2));
        assertEquals(12000, settings.prices().cost(ServerFixture.SHARPNESS, 3));
        assertEquals(32000, settings.prices().cost(ServerFixture.SHARPNESS, 4));
        assertEquals(9000, settings.prices().cost(ServerFixture.UNBREAKING, 3));
        for (String name : Arrays.asList("MENDING", "LOOT_BONUS_BLOCKS", "SILK_TOUCH", "ARROW_INFINITE")) {
            Enchantment enchantment = mock(Enchantment.class);
            when(enchantment.getName()).thenReturn(name);
            int base = 1000;
            assertEquals(base, settings.prices().cost(enchantment, 1), name);
            assertEquals(base * 4, settings.prices().cost(enchantment, 2), name);
        }
    }

    @Test
    void priceBoundsNeverCoerceClampOverflowOrDiscountAndExplicitCostsRespectConfiguredCap() throws Exception {
        for (String fragment : Arrays.asList(
                "base-xp: 0", "base-xp: 1000001", "base-xp: '25'",
                "levels:\n      '0': 2", "levels:\n      '256': 2", "levels:\n      '03': 2",
                "levels:\n      bad: 2", "levels:\n      '3': 0", "levels:\n      '3': 1000001",
                "levels:\n      '3': 9223372036854775807", "levels:\n      '3': 1.5",
                "levels: []")) {
            assertThrows(IllegalArgumentException.class,
                    () -> settings("enchantments:\n  DAMAGE_ALL:\n    " + fragment + "\n"), fragment);
        }
        Settings capped = Settings.read(yaml("base-xp: 100\nmax-cost: 1000\n" +
                "enchantments:\n  DAMAGE_ALL:\n    base-xp: 100\n    levels:\n      '2': 700\n      '3': 1001\n"));
        assertTrue(capped.prices().offers(ServerFixture.SHARPNESS, 1));
        assertEquals(700, capped.prices().cost(ServerFixture.SHARPNESS, 2));
        assertFalse(capped.prices().offers(ServerFixture.SHARPNESS, 3));
        assertFalse(capped.prices().offers(ServerFixture.SHARPNESS, 255));
        assertThrows(IllegalArgumentException.class, () -> capped.prices().cost(ServerFixture.SHARPNESS, 255));
        assertFalse(capped.prices().offers(ServerFixture.SHARPNESS, Integer.MAX_VALUE));
        assertFalse(capped.prices().offers(ServerFixture.SHARPNESS, Integer.MIN_VALUE));
    }

    @Test
    void itemCapOverridesEnchantCapButCannotReenableGloballyDisabledPurchases() throws Exception {
        Settings settings = settings("enchantments:\n  DAMAGE_ALL:\n    max-level: 7\n" +
                "  DURABILITY:\n    enabled: false\n" +
                "items:\n  DIAMOND_SWORD:\n    max-levels:\n      DAMAGE_ALL: 3\n      DURABILITY: 255\n");
        assertEquals(3, settings.policy().maximum(Material.DIAMOND_SWORD, ServerFixture.SHARPNESS));
        assertEquals(7, settings.policy().maximum(Material.IRON_SWORD, ServerFixture.SHARPNESS));
        assertEquals(0, settings.policy().maximum(Material.DIAMOND_SWORD, ServerFixture.UNBREAKING));
        assertEquals(5, settings.policy().maximum(Material.DIAMOND_SWORD, ServerFixture.SMITE));
        assertFalse(Enchantments.legal(fixture.items.get(0), ServerFixture.SHARPNESS, 4, settings.policy()));
        assertTrue(Enchantments.legal(fixture.items.get(0), ServerFixture.SHARPNESS, 3, settings.policy()));
        assertFalse(Enchantments.legal(fixture.items.get(0), ServerFixture.UNBREAKING, 1, settings.policy()));
    }

    @Test
    void loweredOrDisabledCapsPreserveExistingVanillaEnchantsWhileZeroHidesPurchases() throws Exception {
        ItemStack sword = fixture.items.get(0);
        sword.addEnchantment(ServerFixture.SHARPNESS, 5);
        Settings settings = settings("enchantments:\n  DAMAGE_ALL:\n    enabled: false\n" +
                "items:\n  DIAMOND_SWORD:\n    max-levels:\n      DAMAGE_ALL: 255\n      DURABILITY: 0\n");
        assertTrue(Enchantments.supported(sword, settings.policy()));
        assertFalse(Enchantments.choices(sword, settings.prices(), settings.policy()).contains(ServerFixture.SHARPNESS));
        assertFalse(Enchantments.legal(sword, ServerFixture.UNBREAKING, 1, settings.policy()));
        assertEquals(5, sword.getEnchantmentLevel(ServerFixture.SHARPNESS));
    }

    @Test
    void explicitlyRaisedCapsAllowExistingOverlevelsOnlyWithinEffectiveCapAndKeepConflictChecks() throws Exception {
        Settings settings = settings("enchantments:\n  DAMAGE_ALL:\n    max-level: 255\n" +
                "items:\n  DIAMOND_SWORD:\n    max-levels:\n      DAMAGE_ALL: 8\n");
        ItemStack sword = fixture.items.get(0);
        sword.addUnsafeEnchantment(ServerFixture.SHARPNESS, 6);
        assertFalse(Enchantments.supported(sword));
        assertTrue(Enchantments.supported(sword, settings.policy()));
        assertTrue(Enchantments.legal(sword, ServerFixture.SHARPNESS, 8, settings.policy()));
        assertFalse(Enchantments.legal(sword, ServerFixture.SHARPNESS, 9, settings.policy()));
        assertFalse(Enchantments.legal(sword, ServerFixture.SMITE, 1, settings.policy()));
        sword.addUnsafeEnchantment(ServerFixture.SHARPNESS, 9);
        assertFalse(Enchantments.supported(sword, settings.policy()));
        sword.removeEnchantment(ServerFixture.SHARPNESS);
        sword.addUnsafeEnchantment(ServerFixture.CUSTOM, 1);
        assertFalse(Enchantments.supported(sword, settings.policy()));
    }

    private static Settings settings(String optional) throws Exception {
        return Settings.read(yaml("base-xp: 1000\nmax-cost: 1000000\n" + optional));
    }
}
