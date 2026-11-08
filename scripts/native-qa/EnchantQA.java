package local.enchantqa;

import io.github.stealingdapenta.enchantxp.enchanting.Enchantments;
import io.github.stealingdapenta.enchantxp.enchanting.EnchantPolicy;
import io.github.stealingdapenta.enchantxp.enchanting.Experience;
import io.github.stealingdapenta.enchantxp.enchanting.Prices;
import io.github.stealingdapenta.enchantxp.enchanting.BookshelfPricing;
import io.github.stealingdapenta.enchantxp.configuration.Settings;
import io.github.stealingdapenta.enchantxp.presentation.MenuText;
import io.github.stealingdapenta.enchantxp.presentation.LoreStyle;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

public final class EnchantQA extends JavaPlugin {
    private int passed;
    private int failed;
    private PermissibleBase nativePermissions;
    private final AtomicInteger reloadSuccesses = new AtomicInteger();
    private final AtomicInteger reloadFailures = new AtomicInteger();
    private Handler reloadLog;
    private Plugin reloadPlugin;
    private byte[] originalConfig;
    private ConfigOwnership configOwnership;
    private File reloadFile;
    private BukkitScheduler qaScheduler;
    private Logger qaLogger;
    private ExecutorService reloadIo;
    private volatile boolean reloadChecksActive;
    private volatile boolean stopping;

    @Override
    public void onEnable() {
        qaScheduler = getServer().getScheduler();
        qaLogger = getLogger();
        getServer().getScheduler().runTask(this, () -> {
            try {
                File root = new File(".").getCanonicalFile();
                if (!root.getParentFile().getName().equals(".playtest") ||
                        !"127.0.0.1".equals(Bukkit.getIp()) || !Bukkit.getOnlineMode() ||
                        !new File("native-qa.txt").isFile()) {
                    throw new IllegalStateException("Native QA requires a fresh authorized loopback .playtest server");
                }
                nativeChecks();
                arena();
                if (!new File("expect-invalid.txt").isFile()) { nativeFeatureChecks(); }
            } catch (Exception failure) {
                failed++;
                getLogger().severe("QA ERROR " + failure.toString());
            }
            Plugin production = getServer().getPluginManager().getPlugin("EnchantXP");
            if (!new File("expect-invalid.txt").isFile() && failed == 0 &&
                    production != null && production.isEnabled()) {
                beginReloadChecks();
            } else {
                summary();
            }
        });
    }

    private void summary() {
        getLogger().info("QA SUMMARY passed=" + passed + " failed=" + failed +
                "; authenticated-player/client gameplay not exercised");
    }

    private void check(String label, boolean condition) {
        if (condition) { passed++; } else { failed++; }
        getLogger().info("QA " + (condition ? "PASS " : "FAIL ") + label);
    }

    private void nativeChecks() throws Exception {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("EnchantXP");
        boolean invalid = new File("expect-invalid.txt").isFile();
        check("plugin metadata matches compiled QA release", plugin != null && getDescription().getVersion().equals(plugin.getDescription().getVersion()));
        String platform = new String(Files.readAllBytes(new File("native-qa.txt").toPath()), StandardCharsets.UTF_8).trim();
        check("exact native Minecraft API and requested platform", Bukkit.getBukkitVersion().startsWith("1.12.2-") &&
                Bukkit.getVersion().contains(platform));
        check("configured enable state", plugin != null && plugin.isEnabled() != invalid);
        File config = new File("plugins/EnchantXP/config.yml");
        check("config exists", config.isFile());
        if (invalid) {
            check("invalid config is not rewritten", Arrays.equals(Files.readAllBytes(config.toPath()),
                    Files.readAllBytes(new File("invalid-config-before.yml").toPath())));
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(config);
        check("operator config uses build-defined schema one", Settings.read(yaml) != null && yaml.getInt("config-version") == 1);
        check("operator pricing parses without rewriting bytes", Prices.read(yaml) != null);
        YamlConfiguration packaged = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(plugin.getResource("config.yml"), StandardCharsets.UTF_8)) {
            packaged.load(reader);
        }
        Prices prices = Prices.read(packaged);
        YamlConfiguration deleted = new YamlConfiguration();
        deleted.loadFromString(packaged.saveToString());
        deleted.set("base-xp", 7);
        deleted.set("enchantments.LOOT_BONUS_BLOCKS.base-xp", null);
        check("deleted Fortune override uses operator global price",
                Prices.read(deleted).cost(canonical(Enchantment.LOOT_BONUS_BLOCKS), 3) == 63);
        deleted.set("enchantments.LOOT_BONUS_BLOCKS", null);
        check("deleted Fortune section uses operator global price",
                Prices.read(deleted).cost(canonical(Enchantment.LOOT_BONUS_BLOCKS), 3) == 63);
        PluginCommand reload = Bukkit.getPluginCommand("enchantxp");
        check("native reload command registered with executor and completer",
                reload != null && reload.getPlugin() == plugin && reload.getExecutor() != null &&
                reload.getTabCompleter() != null);
        check("native reload permission defaults to op and console is allowed",
                Bukkit.getPluginManager().getPermission("enchantxp.reload") != null &&
                Bukkit.getPluginManager().getPermission("enchantxp.reload").getDefault() ==
                        PermissionDefault.OP &&
                Bukkit.getConsoleSender().hasPermission("enchantxp.reload"));
        nativeCommandChecks(reload);
        for (int rows = 3; rows <= 6; rows++) {
            Inventory sized = Bukkit.createInventory(null, rows * 9, "Native rows " + rows);
            ItemStack info = new ItemStack(Material.EXP_BOTTLE);
            ItemMeta empty = info.getItemMeta();
            empty.setDisplayName("Native blank lore");
            empty.setLore(Collections.emptyList());
            info.setItemMeta(empty);
            sized.setItem(rows * 9 - 4, info);
            check("native chest API rows=" + rows + " and empty lore readback (not production GUI)",
                    sized.getSize() == rows * 9 &&
                    !sized.getItem(rows * 9 - 4).getItemMeta().hasLore());
        }
        check("native packaged defaults and raw prices", prices.cost(1) == 10000 && prices.cost(2) == 40000 &&
                prices.cost(3) == 90000 && prices.cost(4) == 160000 && prices.cost(5) == 250000);
        check("native special enchantment prices", prices.cost(canonical(Enchantment.MENDING), 1) == 250000 &&
                prices.cost(canonical(Enchantment.LOOT_BONUS_BLOCKS), 3) == 225000 &&
                prices.cost(canonical(Enchantment.SILK_TOUCH), 1) == 150000 &&
                prices.cost(canonical(Enchantment.ARROW_INFINITE), 1) == 200000 &&
                prices.cost(canonical(Enchantment.WATER_WORKER), 1) == 75000 &&
                prices.cost(canonical(Enchantment.ARROW_FIRE), 1) == 150000);
        check("native Fortune II to III credit uses current cumulative values",
                prices.upgradeCost(canonical(Enchantment.LOOT_BONUS_BLOCKS), 2, 3) == 125000);
        check("native purchase cap applies after cumulative credit",
                !prices.offers(canonical(Enchantment.DAMAGE_ALL), 11) &&
                prices.upgradeCost(canonical(Enchantment.DAMAGE_ALL), 10, 11) == 210000);
        EnchantPolicy policy = EnchantPolicy.read(packaged);
        boolean vanillaCaps = true;
        for (Enchantment enchantment : Enchantment.values()) {
            vanillaCaps &= policy.maximum(Material.DIAMOND_SWORD, enchantment) == enchantment.getMaxLevel();
        }
        check("default purchase caps equal exact native vanilla maxima", vanillaCaps);
        check("exact frost walker starts at one and ends at two",
                canonical(Enchantment.FROST_WALKER).getStartLevel() == 1 &&
                canonical(Enchantment.FROST_WALKER).getMaxLevel() == 2);
        ItemStack bookIcon = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta bookRaw = bookIcon.getItemMeta();
        check("native API supplies enchanted book storage meta (not a production GUI test)",
                bookRaw instanceof EnchantmentStorageMeta);
        if (bookRaw instanceof EnchantmentStorageMeta) {
            EnchantmentStorageMeta bookMeta = (EnchantmentStorageMeta) bookRaw;
            check("native API stores selected book level", bookMeta.addStoredEnchant(canonical(Enchantment.DAMAGE_ALL), 3, true));
            check("native API accepts stored metadata on book", bookIcon.setItemMeta(bookMeta));
            EnchantmentStorageMeta readback = (EnchantmentStorageMeta) bookIcon.getItemMeta();
            check("native API reads stored enchantment and exact level",
                    readback.getStoredEnchants().size() == 1 &&
                    readback.getStoredEnchantLevel(canonical(Enchantment.DAMAGE_ALL)) == 3);
        }
        ItemStack sword = sword();
        check("damaged named sword supported", Enchantments.supported(sword));
        check("sharpness five legal", legal(sword, Enchantment.DAMAGE_ALL, 5));
        check("sharpness six rejected", !legal(sword, Enchantment.DAMAGE_ALL, 6));
        check("zero level rejected", !legal(sword, Enchantment.DAMAGE_ALL, 0));
        check("armor enchant on sword rejected", !legal(sword, Enchantment.PROTECTION_ENVIRONMENTAL, 1));
        check("single plain and enchanted books supported",
                Enchantments.supported(new ItemStack(Material.BOOK)) &&
                Enchantments.supported(new ItemStack(Material.ENCHANTED_BOOK)));
        check("native plain book stack maximum 64 and enchanted book maximum one",
                Material.BOOK.getMaxStackSize() == 64 && Material.ENCHANTED_BOOK.getMaxStackSize() == 1);
        check("native plain book stacks 2 and 64 supported but 65 rejected",
                Enchantments.supported(new ItemStack(Material.BOOK, 2)) &&
                Enchantments.supported(new ItemStack(Material.BOOK, 64)) &&
                !Enchantments.supported(new ItemStack(Material.BOOK, 65)));
        check("books offer all 30 vanilla types across two GUI-sized pages",
                Enchantments.choices(new ItemStack(Material.BOOK), prices).size() == 30);
        check("stacked enchanted and written books unsupported",
                !Enchantments.supported(new ItemStack(Material.ENCHANTED_BOOK, 2)) &&
                !Enchantments.supported(new ItemStack(Material.BOOK_AND_QUILL)) &&
                !Enchantments.supported(new ItemStack(Material.WRITTEN_BOOK)));
        for (String style : new String[]{"fill", "border"}) {
            String path = "ui.icons." + style;
            ItemStack pane = new ItemStack(Material.STAINED_GLASS_PANE, 1, (short) packaged.getInt(path + ".data"));
            ItemMeta blank = pane.getItemMeta();
            blank.setDisplayName(packaged.getString(path + ".name"));
            blank.setLore(packaged.getStringList(path + ".lore"));
            check("native packaged " + style + " blank pane metadata roundtrip (not client tooltip rendering)",
                    pane.setItemMeta(blank) && " ".equals(pane.getItemMeta().getDisplayName()) &&
                    !pane.getItemMeta().hasLore());
        }
        nativeBookChecks(prices);
        check("stacked swords unsupported", !Enchantments.supported(new ItemStack(Material.DIAMOND_SWORD, 2)));
        check("air and null unsupported", !Enchantments.supported(new ItemStack(Material.AIR)) && !Enchantments.supported(null));
        ItemStack upgraded = sword.clone();
        upgraded.addEnchantment(canonical(Enchantment.DAMAGE_ALL), 3);
        check("native enchant application", upgraded.getEnchantmentLevel(Enchantment.DAMAGE_ALL) == 3);
        check("damage name lore preserved", upgraded.getDurability() == sword.getDurability() &&
                upgraded.getItemMeta().getDisplayName().equals(sword.getItemMeta().getDisplayName()) &&
                upgraded.getItemMeta().getLore().equals(sword.getItemMeta().getLore()));
        check("original clone is unchanged", sword.getEnchantments().isEmpty());
        check("same/lower level rejected", !legal(upgraded, Enchantment.DAMAGE_ALL, 3) &&
                !legal(upgraded, Enchantment.DAMAGE_ALL, 2));
        check("higher level upgrade allowed", legal(upgraded, Enchantment.DAMAGE_ALL, 4));
        check("sharpness smite conflict rejected", !legal(upgraded, Enchantment.DAMAGE_UNDEAD, 1));
        upgraded.addEnchantment(canonical(Enchantment.DURABILITY), 2);
        upgraded.addEnchantment(canonical(Enchantment.DAMAGE_ALL), 4);
        check("unrelated enchant preserved", upgraded.getEnchantmentLevel(Enchantment.DURABILITY) == 2);
        ItemStack bad = sword.clone();
        bad.addUnsafeEnchantment(canonical(Enchantment.DAMAGE_ALL), 6);
        check("existing overlevel unsupported", !Enchantments.supported(bad));
        YamlConfiguration caps = new YamlConfiguration();
        caps.loadFromString("enchantments:\n  DAMAGE_ALL:\n    max-level: 255\n" +
                "items:\n  DIAMOND_SWORD:\n    max-levels:\n      DAMAGE_ALL: 7\n      DURABILITY: 0\n");
        EnchantPolicy raised = EnchantPolicy.read(caps);
        YamlConfiguration highest = new YamlConfiguration();
        highest.loadFromString("base-xp: 1\nmax-cost: 1000000\nenchantments:\n" +
                "  DAMAGE_ALL:\n    max-level: 255\n");
        ItemStack highSource = sword.clone();
        ItemMeta highSourceMeta = highSource.getItemMeta();
        highSourceMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        highSource.setItemMeta(highSourceMeta);
        highSource.addUnsafeEnchantment(canonical(Enchantment.DAMAGE_ALL), 200);
        highSource.addEnchantment(canonical(Enchantment.DURABILITY), 2);
        ItemStack overlevel = highSource.clone();
        overlevel.addUnsafeEnchantment(canonical(Enchantment.DAMAGE_ALL), 255);
        check("native item API Sharpness 200 to 255 credit (not production purchase or player XP)",
                Enchantments.level(highSource, canonical(Enchantment.DAMAGE_ALL)) == 200 &&
                Prices.read(highest).upgradeCost(canonical(Enchantment.DAMAGE_ALL),
                        Enchantments.level(highSource, canonical(Enchantment.DAMAGE_ALL)), 255) == 25025);
        check("native level 255 normal item readback preserves damage name lore flags and other enchant",
                Enchantments.supported(overlevel, EnchantPolicy.read(highest)) &&
                overlevel.getEnchantmentLevel(canonical(Enchantment.DAMAGE_ALL)) == 255 &&
                overlevel.getEnchantmentLevel(canonical(Enchantment.DURABILITY)) == 2 &&
                overlevel.getDurability() == highSource.getDurability() &&
                overlevel.getItemMeta().getDisplayName().equals(highSource.getItemMeta().getDisplayName()) &&
                overlevel.getItemMeta().getLore().equals(highSource.getItemMeta().getLore()) &&
                overlevel.getItemMeta().getItemFlags().equals(highSource.getItemMeta().getItemFlags()) &&
                highSource.getEnchantmentLevel(canonical(Enchantment.DAMAGE_ALL)) == 200);
        ItemStack highBook = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta highMeta = (EnchantmentStorageMeta) highBook.getItemMeta();
        highMeta.setDisplayName("Native high stored book");
        highMeta.setLore(Arrays.asList("Preserve native stored lore"));
        highMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        highMeta.addStoredEnchant(canonical(Enchantment.DAMAGE_ALL), 200, true);
        highMeta.addStoredEnchant(canonical(Enchantment.DURABILITY), 1, true);
        highBook.setItemMeta(highMeta);
        ItemStack highBookBefore = highBook.clone();
        highMeta.addStoredEnchant(canonical(Enchantment.DAMAGE_ALL), 255, true);
        highBook.setItemMeta(highMeta);
        check("native level 255 stored API readback preserves metadata and adaptive credit",
                Enchantments.supported(highBook, EnchantPolicy.read(highest)) &&
                Enchantments.level(highBook, canonical(Enchantment.DAMAGE_ALL)) == 255 &&
                Enchantments.level(highBook, canonical(Enchantment.DURABILITY)) == 1 &&
                highBook.getItemMeta().getDisplayName().equals(highBookBefore.getItemMeta().getDisplayName()) &&
                highBook.getItemMeta().getLore().equals(highBookBefore.getItemMeta().getLore()) &&
                highBook.getItemMeta().getItemFlags().equals(highBookBefore.getItemMeta().getItemFlags()) &&
                Prices.read(highest).upgradeCost(canonical(Enchantment.DAMAGE_ALL),
                        Enchantments.level(highBookBefore, canonical(Enchantment.DAMAGE_ALL)), 255) == 25025);
        nativeHighBookSplit(highest);
        check("native rules accept explicitly configured existing overlevel", Enchantments.supported(bad, raised));
        check("native per-item cap overrides enchantment cap",
                Enchantments.legal(bad, canonical(Enchantment.DAMAGE_ALL), 7, raised) &&
                !Enchantments.legal(bad, canonical(Enchantment.DAMAGE_ALL), 8, raised));
        check("native zero cap hides new purchases",
                !Enchantments.legal(sword, canonical(Enchantment.DURABILITY), 1, raised));
        caps.set("enchantments.DAMAGE_ALL.enabled", false);
        EnchantPolicy disabled = EnchantPolicy.read(caps);
        ItemStack vanillaEnchanted = sword.clone();
        vanillaEnchanted.addEnchantment(canonical(Enchantment.DAMAGE_ALL), 5);
        check("native disabled purchases preserve existing vanilla enchantments",
                Enchantments.supported(vanillaEnchanted, disabled) &&
                !Enchantments.legal(sword, canonical(Enchantment.DAMAGE_ALL), 1, disabled));
        YamlConfiguration quotes = new YamlConfiguration();
        quotes.loadFromString("base-xp: 1000\nmax-cost: 1000000\nenchantments:\n  DAMAGE_ALL:\n" +
                "    base-xp: 2000\n    levels:\n      '3': 12000\n");
        Prices overrides = Prices.read(quotes);
        check("native sparse per-level pricing precedence",
                overrides.cost(canonical(Enchantment.DAMAGE_ALL), 3) == 12000 &&
                overrides.cost(canonical(Enchantment.DAMAGE_ALL), 2) == 8000 &&
                overrides.upgradeCost(canonical(Enchantment.DAMAGE_ALL), 2, 3) == 4000);
        bad = sword.clone();
        bad.addUnsafeEnchantment(canonical(Enchantment.PROTECTION_ENVIRONMENTAL), 1);
        check("existing wrong-type enchant unsupported", !Enchantments.supported(bad));
        bad = upgraded.clone();
        bad.addUnsafeEnchantment(canonical(Enchantment.DAMAGE_UNDEAD), 1);
        check("existing conflict unsupported", !Enchantments.supported(bad));
        ItemStack pick = new ItemStack(Material.DIAMOND_PICKAXE);
        pick.addEnchantment(canonical(Enchantment.SILK_TOUCH), 1);
        check("silk fortune conflict rejected", !legal(pick, Enchantment.LOOT_BONUS_BLOCKS, 1));
        ItemStack bow = new ItemStack(Material.BOW);
        bow.addEnchantment(canonical(Enchantment.ARROW_INFINITE), 1);
        check("infinity mending conflict rejected", !legal(bow, Enchantment.MENDING, 1));
        ItemStack boots = new ItemStack(Material.DIAMOND_BOOTS);
        boots.addEnchantment(canonical(Enchantment.DEPTH_STRIDER), 1);
        check("depth frost conflict rejected", !legal(boots, Enchantment.FROST_WALKER, 1));
        for (Material type : new Material[]{Material.DIAMOND_SWORD, Material.DIAMOND_PICKAXE,
                Material.DIAMOND_BOOTS, Material.BOW, Material.FISHING_ROD, Material.DIAMOND_CHESTPLATE}) {
            ItemStack item = new ItemStack(type);
            boolean legal = !Enchantments.choices(item, prices).isEmpty();
            for (Enchantment enchant : Enchantments.choices(item, prices)) {
                legal &= enchant.canEnchantItem(item) && Enchantments.legal(item, enchant, 1);
            }
            check("native offered choices " + type.name(), legal);
        }
        check("XP branch boundaries", Experience.atLevel(16) == 352 && Experience.atLevel(17) == 394 &&
                Experience.atLevel(31) == 1507 && Experience.atLevel(32) == 1628);
        boolean roundTrip = true;
        for (int raw : new int[]{0, 1, 24, 25, 99, 100, 351, 352, 393, 394, 1507, 1628, 5000, 2000000, Integer.MAX_VALUE}) {
            Experience.Balance balance = Experience.balance(raw);
            roundTrip &= Experience.total(balance.level(), balance.progress()) == raw;
        }

        check("XP conversion boundary roundtrips (no player)", roundTrip);
    }

    private void nativeBookChecks(Prices prices) throws Exception {
        ItemStack plain = new ItemStack(Material.BOOK);
        ItemMeta named = plain.getItemMeta();
        named.setDisplayName("Native trading book");
        named.setLore(Arrays.asList("Preserve native lore"));
        plain.setItemMeta(named);
        Inventory storage = Bukkit.createInventory(null, 9);
        storage.setItem(0, plain);
        ItemStack source = storage.getItem(0);
        ItemStack snapshot = source.clone();
        ItemStack result = snapshot.clone();
        ItemMeta before = result.getItemMeta();
        result.setType(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) Bukkit.getItemFactory()
                .asMetaFor(before, Material.ENCHANTED_BOOK);
        check("native mirrored-book API conversion accepts stored enchantment",
                meta.addStoredEnchant(canonical(Enchantment.DAMAGE_ALL), 2, true) && result.setItemMeta(meta));
        check("native mirrored-book API conversion preserves name lore and exact stored level",
                "Native trading book".equals(result.getItemMeta().getDisplayName()) &&
                Arrays.asList("Preserve native lore").equals(result.getItemMeta().getLore()) &&
                Enchantments.level(result, canonical(Enchantment.DAMAGE_ALL)) == 2 &&
                result.getEnchantments().isEmpty());
        check("native mirrored-book source and snapshot remain plain and unchanged",
                source.getType() == Material.BOOK && source.equals(snapshot) && source.equals(storage.getItem(0)));
        check("native stored-book upgrade legality and conflicts",
                legal(result, Enchantment.DAMAGE_ALL, 3) && !legal(result, Enchantment.DAMAGE_ALL, 2) &&
                !legal(result, Enchantment.DAMAGE_UNDEAD, 1) && legal(result, Enchantment.DURABILITY, 1));
        EnchantmentStorageMeta upgraded = (EnchantmentStorageMeta) result.getItemMeta();
        upgraded.addStoredEnchant(canonical(Enchantment.DURABILITY), 1, true);
        upgraded.addStoredEnchant(canonical(Enchantment.DAMAGE_ALL), 3, true);
        check("native stored-book upgrade preserves unrelated stored enchantment and metadata",
                result.setItemMeta(upgraded) &&
                Enchantments.level(result, canonical(Enchantment.DURABILITY)) == 1 &&
                Enchantments.level(result, canonical(Enchantment.DAMAGE_ALL)) == 3 &&
                "Native trading book".equals(result.getItemMeta().getDisplayName()) &&
                Arrays.asList("Preserve native lore").equals(result.getItemMeta().getLore()));
        YamlConfiguration caps = new YamlConfiguration();
        caps.loadFromString("items:\n  BOOK:\n    max-levels:\n      DAMAGE_ALL: 8\n      DURABILITY: 8\n" +
                "  ENCHANTED_BOOK:\n    max-levels:\n      DURABILITY: 2\n");
        EnchantPolicy policy = EnchantPolicy.read(caps);
        check("native book cap fallback survives conversion with explicit stored-book override",
                policy.maximum(Material.ENCHANTED_BOOK, canonical(Enchantment.DAMAGE_ALL)) == 8 &&
                policy.maximum(Material.ENCHANTED_BOOK, canonical(Enchantment.DURABILITY)) == 2 &&
                Enchantments.legal(result, canonical(Enchantment.DAMAGE_ALL), 8, policy));
        caps.set("items.ENCHANTED_BOOK.max-levels.DAMAGE_ALL", 2);
        EnchantPolicy destination = EnchantPolicy.read(caps);
        check("native plain-book offers obey explicit destination cap rather than inherited source cap",
                destination.maximum(Material.BOOK, canonical(Enchantment.DAMAGE_ALL)) == 2 &&
                Enchantments.levels(plain, canonical(Enchantment.DAMAGE_ALL), prices, destination)
                        .equals(Arrays.asList(1, 2)) &&
                !Enchantments.legal(plain, canonical(Enchantment.DAMAGE_ALL), 6, destination));
        check("native stone is unsupported rather than maxed", !Enchantments.supported(new ItemStack(Material.STONE)));
        check("native stored-book quote credits existing stored level",
                prices.upgradeCost(canonical(Enchantment.DAMAGE_ALL),
                        Enchantments.level(result, canonical(Enchantment.DAMAGE_ALL)), 4) == 70000);
    }

    private void nativeHighBookSplit(YamlConfiguration config) {
        ItemStack source = new ItemStack(Material.BOOK, 64);
        ItemMeta named = source.getItemMeta();
        named.setDisplayName("Native high book stack");
        named.setLore(Arrays.asList("Preserve split lore"));
        named.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        source.setItemMeta(named);
        Inventory storage = Bukkit.createInventory(null, 9);
        storage.setItem(0, source);
        ItemStack before = storage.getItem(0).clone();
        ItemStack output = before.clone();
        output.setAmount(1);
        ItemMeta originalMeta = output.getItemMeta();
        output.setType(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta converted = (EnchantmentStorageMeta) Bukkit.getItemFactory()
                .asMetaFor(originalMeta, Material.ENCHANTED_BOOK);
        check("native API plain book conversion accepts stored level 255",
                converted.addStoredEnchant(canonical(Enchantment.DAMAGE_ALL), 255, true) &&
                output.setItemMeta(converted));
        ItemStack remainder = before.clone();
        remainder.setAmount(63);
        storage.setItem(0, remainder);
        storage.setItem(1, output);
        ItemStack readback = storage.getItem(1);
        check("native mirrored inventory split 64 to 63 plus one stored 255 preserves metadata (not production commit)",
                storage.getItem(0).equals(remainder) && storage.getItem(0).getAmount() == 63 &&
                readback.getType() == Material.ENCHANTED_BOOK && readback.getAmount() == 1 &&
                Enchantments.level(readback, canonical(Enchantment.DAMAGE_ALL)) == 255 &&
                readback.getItemMeta().getDisplayName().equals(before.getItemMeta().getDisplayName()) &&
                readback.getItemMeta().getLore().equals(before.getItemMeta().getLore()) &&
                readback.getItemMeta().getItemFlags().equals(before.getItemMeta().getItemFlags()) &&
                before.getAmount() == 64 && before.getType() == Material.BOOK &&
                Prices.read(config).upgradeCost(canonical(Enchantment.DAMAGE_ALL), 0, 255) == 65025);
    }

    private void nativeCommandChecks(PluginCommand command) {
        Permission config = Bukkit.getPluginManager().getPermission("enchantxp.config");
        check("native config permission defaults to op and console is allowed",
                config != null && config.getDefault() == PermissionDefault.OP &&
                Bukkit.getConsoleSender().hasPermission("enchantxp.config"));
        List<String> first = command == null ? Collections.emptyList() :
                command.tabComplete(Bukkit.getConsoleSender(), "enchantxp", new String[]{""});
        check("native console completion includes help and reload but excludes player-only config",
                first.contains("help") && first.contains("reload") && !first.contains("config"));
        List<String> prefix = command == null ? Collections.emptyList() :
                command.tabComplete(Bukkit.getConsoleSender(), "enchantxp", new String[]{"HE"});
        check("native console command prefix completion is case insensitive",
                prefix.equals(Collections.singletonList("help")));
        check("native help dispatch works without launching an editor",
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "enchantxp help"));
        List<String> unsupported = command == null ? Collections.emptyList() :
                command.tabComplete(Bukkit.getConsoleSender(), "enchantxp",
                        new String[]{"config", "unsupported-qa-argument"});
        check("native console unsupported config argument completion is empty (no player-name fallback)",
                unsupported.isEmpty());
        boolean dispatched = false;
        try {
            dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "enchantxp config");
        } catch (RuntimeException failure) {
            getLogger().warning("QA console config dispatch threw " + failure.getClass().getSimpleName());
        }
        check("native console config player-only dispatch returns true without throwing (not GUI proof)",
                dispatched);
    }

    private void beginReloadChecks() {
        reloadPlugin = Bukkit.getPluginManager().getPlugin("EnchantXP");
        if (!"127.0.0.1".equals(Bukkit.getIp()) || !Bukkit.getOnlineMode() ||
                Bukkit.getWorld("qa-world") == null || !new File("server.jar").isFile()) {
            check("reload fixture safety gate: isolated authenticated loopback qa-world", false);
            summary();
            return;
        }
        reloadFile = new File(reloadPlugin.getDataFolder(), "config.yml");
        nativePermissions = new PermissibleBase(null);
        nativePermissions.addAttachment(this, "enchantxp.enchant.*", true);
        reloadIo = Executors.newSingleThreadExecutor();
        reloadChecksActive = true;
        reloadLog = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getMessage().contains("Reloaded ")) { reloadSuccesses.incrementAndGet(); }
                if (record.getMessage().contains("Cannot reload ")) { reloadFailures.incrementAndGet(); }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        reloadPlugin.getLogger().addHandler(reloadLog);
        reloadIo.execute(() -> {
            try {
                File root = new File(".").getCanonicalFile();
                File expected = new File(new File(root, "plugins"), "EnchantXP").getCanonicalFile();
                if (!reloadFile.getCanonicalFile().getParentFile().equals(expected) ||
                        !root.getParentFile().getName().equals(".playtest")) {
                    throw new IllegalStateException("Reload checks require an owned .playtest disposable server");
                }
                configOwnership = new ConfigOwnership(reloadFile.toPath());
                originalConfig = configOwnership.originalBytes();
                if (reloadChecksActive) { qaScheduler.runTask(this, () -> reloadPhase(0)); }
            } catch (Exception failure) {
                qaLogger.severe("QA reload setup failed: " + failure);
                if (!reloadChecksActive) { return; }
                qaScheduler.runTask(this, () -> {
                    check("reload fixture setup", false);
                    finishReloadChecks();
                });
            }
        });
    }

    private void reloadPhase(int phase) {
        if (!reloadChecksActive) { return; }
        String candidate = "config-version: 1\nbase-xp: 7\nmax-cost: 1000000\npermissions:\n  enabled: true\n" +
                "enchantments:\n  DAMAGE_ALL:\n    max-level: 8\nitems:\n  DIAMOND_SWORD:\n" +
                "    max-levels:\n      DAMAGE_ALL: " + (phase == 2 ? 16 : 12) +
                "\nui:\n  rows: 3\n  icons:\n    info:\n      lore: []\n";
        if (phase == 1) { candidate = "ui: [broken\n"; }
        if (phase == 3) { candidate = "config-version: 1\nbase-xp: 7\nmax-cost: 1000000\npermissions:\n  enabled: false\n"; }
        byte[] contents = phase == 4 ? originalConfig : candidate.getBytes(StandardCharsets.UTF_8);
        int before = phase == 1 ? reloadFailures.get() : reloadSuccesses.get();
        int listeners = HandlerList.getRegisteredListeners(reloadPlugin).size();
        reloadIo.execute(() -> {
            if (!reloadChecksActive) { return; }
            try {
                configOwnership.writeFixture(contents);
                if (!reloadChecksActive) { return; }
                qaScheduler.runTask(this, () -> {
                    if (!reloadChecksActive) { return; }
                    check("native console dispatch reload phase " + phase,
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "enchantxp reload"));
                    awaitReload(phase, before, listeners, contents, 0);
                });
            } catch (Exception failure) {
                qaLogger.severe("QA reload write failed: " + failure);
                if (!reloadChecksActive) { return; }
                qaScheduler.runTask(this, () -> {
                    check("reload file write phase " + phase, false);
                    if (phase < 4) { reloadPhase(4); } else { finishReloadChecks(); }
                });
            }
        });
    }

    private void awaitReload(int phase, int before, int listeners, byte[] expected, int polls) {
        if (!reloadChecksActive) { return; }
        int observed = phase == 1 ? reloadFailures.get() : reloadSuccesses.get();
        if (observed <= before && polls < 100) {
            qaScheduler.runTaskLater(this,
                    () -> awaitReload(phase, before, listeners, expected, polls + 1), 1L);
            return;
        }
        check("native reload phase " + phase + (phase == 1 ? " rejected candidate" : " published candidate"),
                observed == before + 1 && reloadPlugin.isEnabled());
        check("native reload phase " + phase + " does not register duplicate listeners",
                HandlerList.getRegisteredListeners(reloadPlugin).size() == listeners);
        if (phase == 0) {
            Permission parent = Bukkit.getPluginManager().getPermission("enchantxp.enchant.*");
            Permission leaf = Bukkit.getPluginManager().getPermission("enchantxp.enchant.sharpness.12");
            check("native reload enables owned 30-enchantment parent and item-cap leaf default OP",
                    parent != null && parent.getChildren().size() == 30 &&
                    leaf != null && leaf.getDefault() == PermissionDefault.OP);
            check("native pre-registration attachment is refreshed without relog",
                    nativePermissions.hasPermission("enchantxp.enchant.sharpness.12"));
            nativePermissions.addAttachment(this, "enchantxp.enchant.sharpness.12", false);
            check("native separate exact negative attachment denies resolved leaf",
                    !nativePermissions.hasPermission("enchantxp.enchant.sharpness.12"));
        } else if (phase == 1) {
            check("native invalid reload preserves registry and resolved decisions",
                    Bukkit.getPluginManager().getPermission("enchantxp.enchant.sharpness.12") != null &&
                    !nativePermissions.hasPermission("enchantxp.enchant.sharpness.12"));
        } else if (phase == 2) {
            check("native valid cap raise grows parent children for existing attachment without relog",
                    Bukkit.getPluginManager().getPermission("enchantxp.enchant.sharpness.16") != null &&
                    nativePermissions.hasPermission("enchantxp.enchant.sharpness.16"));
        } else if (phase == 3 || phase == 4) {
            check("native disabled feature retains owned nodes until plugin shutdown phase " + phase,
                    Bukkit.getPluginManager().getPermission("enchantxp.enchant.sharpness.16") != null);
        }
        reloadIo.execute(() -> {
            boolean unchanged = false;
            try {
                unchanged = Arrays.equals(expected, Files.readAllBytes(reloadFile.toPath()));
                if (phase == 4 && unchanged) {
                    configOwnership.releaseRestored();
                    originalConfig = null;
                }
            } catch (IOException | RuntimeException failure) {
                qaLogger.warning("QA config verification failed in phase " + phase + ": " + failure);
                unchanged = false;
            }
            final boolean exact = unchanged;
            if (!reloadChecksActive) { return; }
            qaScheduler.runTask(this, () -> {
                if (!reloadChecksActive) { return; }
                check("native reload phase " + phase + " preserves exact operator bytes", exact);
                if (phase < 4) { reloadPhase(phase + 1); } else { finishReloadChecks(); }
            });
        });
    }

    private void finishReloadChecks() {
        reloadChecksActive = false;
        if (reloadLog != null) {
            reloadPlugin.getLogger().removeHandler(reloadLog);
            reloadLog.close();
            reloadLog = null;
        }
        // Publish SUMMARY only after the final owned-file cleanup is fenced on the single writer.
        reloadIo.execute(() -> {
            boolean restored = restoreOwnedConfig();
            if (stopping) { return; }
            try {
                qaScheduler.runTask(this, () -> {
                    if (stopping) { return; }
                    if (!restored) { check("reload fixture original restoration", false); }
                    summary();
                });
            } catch (RuntimeException failure) {
                qaLogger.warning("QA final summary could not be scheduled: " + failure);
            }
        });
    }

    private boolean restoreOwnedConfig() {
        try {
            if (configOwnership != null) { configOwnership.restoreIfOwned(); }
            originalConfig = null;
            return true;
        } catch (IOException | RuntimeException failure) {
            qaLogger.warning("QA config restoration refused or failed; no unowned bytes overwritten: " + failure);
            return false;
        }
    }

    @Override
    public void onDisable() {
        stopping = true;
        reloadChecksActive = false;
        if (reloadLog != null) {
            reloadPlugin.getLogger().removeHandler(reloadLog);
            reloadLog.close();
            reloadLog = null;
        }
        if (reloadIo != null) {
            // This single writer fences in-flight fixture writes before restoring the owned file.
            reloadIo.execute(this::restoreOwnedConfig);
            reloadIo.shutdown();
            try {
                if (!reloadIo.awaitTermination(5, TimeUnit.SECONDS)) {
                    qaLogger.severe("QA config writer did not stop within five seconds");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                qaLogger.severe("Interrupted while waiting for QA config restoration");
            }
        }
        if (nativePermissions != null) { nativePermissions.clearPermissions(); }
    }

    /** Confined to reloadIo; completed checks relinquish all rights to future operator edits. */
    static final class ConfigOwnership {
        private final Path file;
        private byte[] original;
        private byte[] lastFixture;
        private byte[] attemptedFixture;
        private boolean restorePending;

        ConfigOwnership(Path file) throws IOException {
            this.file = file;
            original = currentBytes();
            lastFixture = original;
        }

        byte[] originalBytes() {
            return original.clone();
        }

        void writeFixture(byte[] contents) throws IOException {
            if (original == null) { throw new IllegalStateException("QA config ownership was released"); }
            byte[] actual = currentBytes();
            if (!Arrays.equals(actual, lastFixture) && !Arrays.equals(actual, attemptedFixture)) {
                throw new IOException("config.yml changed outside QA; refusing fixture write or restoration");
            }
            // A failed/closed write may leave either known generation; unknown partial bytes are never claimed.
            attemptedFixture = contents.clone();
            restorePending = true;
            Files.write(file, attemptedFixture);
            lastFixture = attemptedFixture;
            attemptedFixture = null;
        }

        void releaseRestored() throws IOException {
            if (original == null) { return; }
            if (!Arrays.equals(original, currentBytes())) {
                throw new IOException("QA original config restoration is not byte-exact; ownership retained");
            }
            release();
        }

        void restoreIfOwned() throws IOException {
            if (restorePending) {
                writeFixture(original);
                releaseRestored();
            } else {
                release();
            }
        }

        private byte[] currentBytes() throws IOException {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("QA config is missing or no longer a regular owned file");
            }
            return Files.readAllBytes(file);
        }

        private void release() {
            restorePending = false;
            original = null;
            lastFixture = null;
            attemptedFixture = null;
        }
    }

    private boolean legal(ItemStack item, Enchantment enchantment, int level) {
        // Match production menu choices: 1.12 constants are wrappers, not registry identities.
        return Enchantments.legal(item, canonical(enchantment), level);
    }

    private Enchantment canonical(Enchantment enchantment) {
        return Enchantment.getByName(enchantment.getName());
    }

    private ItemStack sword() {
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD, 1, (short) 37);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("QA damaged sword");
        meta.setLore(Arrays.asList("Keep this name, lore and damage after enchanting."));
        item.setItemMeta(meta);
        return item;
    }

    private void arena() {
        World world = Bukkit.getWorld("qa-world");
        if (world == null) { throw new IllegalStateException("Only disposable qa-world is permitted"); }
        if (world.getBlockAt(0, 64, 0).getType() == Material.ENCHANTMENT_TABLE) {
            getLogger().info("Existing QA table found; preserving arena blocks, spawn and world settings.");
            return;
        }
        world.setTime(6000);
        world.setGameRuleValue("doDaylightCycle", "false");
        world.setGameRuleValue("doMobSpawning", "false");
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                world.getBlockAt(x, 63, z).setType(Material.STONE);
                for (int y = 64; y <= 68; y++) { world.getBlockAt(x, y, z).setType(Material.AIR); }
            }
        }
        world.getBlockAt(0, 64, 0).setType(Material.ENCHANTMENT_TABLE);
        world.setSpawnLocation(0, 64, 3);
    }

    private void nativeFeatureChecks() {
        World world = Bukkit.getWorld("qa-world");
        YamlConfiguration config = new YamlConfiguration();
        BookshelfPricing pricing = BookshelfPricing.read(config);
        check("native bookshelf options retain vanilla maximum fifteen", pricing.enabled() && pricing.maximum() == 15);
        check("native bookshelf base modifier 0/1/15/32 shelves",
                pricing.modify(100, 0) == 250 && pricing.modify(100, 1) == 240 &&
                pricing.modify(100, 15) == 100 && pricing.modify(100, 32) == 100);
        nativeScratchShelves(world, pricing);
        config.set("bookshelf-pricing.enabled", false);
        check("native disabled pricing needs no table/world and exposes honest status",
                !BookshelfPricing.read(config).enabled() && BookshelfPricing.read(config).count(null) == 0);
        nativeSchemaAndCapChecks();
        nativeLoreChecks();
        check("native legacy private sound constants exist (not audio playback proof)",
                Sound.valueOf("UI_BUTTON_CLICK") == Sound.UI_BUTTON_CLICK &&
                Sound.valueOf("BLOCK_ENCHANTMENT_TABLE_USE") == Sound.BLOCK_ENCHANTMENT_TABLE_USE &&
                Sound.valueOf("ENTITY_VILLAGER_NO") == Sound.ENTITY_VILLAGER_NO);
    }

    private void nativeLoreChecks() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("config-version", 1);
        config.set("base-xp", 25);
        config.set("max-cost", -1);
        MenuText defaults = Settings.read(config).text();
        check("native input anvil metadata uses explicit default action prefix",
                defaults.icon("instructions", Collections.emptyMap()).getItemMeta().getLore().equals(
                        Collections.singletonList(ChatColor.GREEN + "\u00bb Left-click one compatible item below.")));
        config.set("lore-style.action-prefix", "Go: ");
        MenuText custom = Settings.read(config).text();
        check("native prefix-only edit preserves inherited stage and input instructions",
                custom.lore("ui.type-lore", Collections.emptyMap()).equals(
                        Collections.singletonList(ChatColor.GREEN + "Go: Left-click to choose enchantment")) &&
                custom.icon("instructions", Collections.emptyMap()).getItemMeta().getLore().get(0).contains("Go: ") &&
                !custom.lore("ui.review-lore", Collections.emptyMap()).isEmpty() && !config.contains("ui", true));
        check("native operator role contract shares custom action prefix and distinct info colors",
                LoreStyle.read(config).editorLines(Arrays.asList(LoreStyle.action("Choose."),
                        LoreStyle.number("Value", "10%"))).equals(Arrays.asList(
                        ChatColor.GRAY + "" + ChatColor.GREEN + "Go: Choose.",
                        ChatColor.GRAY + "" + ChatColor.AQUA + "Value: " + ChatColor.GOLD + "10%")));
        config.set("lore-style.action-prefix", "");
        check("native blank action prefix removes symbol without unresolved marker",
                Settings.read(config).text().icon("instructions", Collections.emptyMap()).getItemMeta().getLore().equals(
                        Collections.singletonList(ChatColor.GREEN + "Left-click one compatible item below.")));
        config.set("ui.option-lore", Arrays.asList("{action}Choose {item}", "&7Left-click custom prose"));
        List<String> customLore = Settings.read(config).text().lore("ui.option-lore",
                Collections.singletonMap("item", "{action}&6{cost}"));
        check("native custom prose and inserted literal formatting are not reclassified or rescanned",
                customLore.equals(Arrays.asList(ChatColor.GREEN + "Choose {action}&6{cost}",
                        ChatColor.GRAY + "Left-click custom prose")));
    }

    private void nativeSchemaAndCapChecks() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("config-version", 1);
        config.set("base-xp", 25000);
        config.set("max-cost", -1);
        config.set("enchantments.LOOT_BONUS_BLOCKS.max-level", 70);
        Settings settings = Settings.read(config);
        Enchantment fortune = canonical(Enchantment.LOOT_BONUS_BLOCKS);
        check("native uncapped Fortune seventy retains all seventy legal domain choices",
                Enchantments.levels(new ItemStack(Material.BOOK), fortune, settings.prices(), settings.policy()).size() == 70);
        check("native uncapped prices retain technical signed-integer charge guard",
                settings.prices().cost(fortune, 70) == 122500000 &&
                !settings.prices().accepts((long) Integer.MAX_VALUE + 1));
        config.set("config-version", null);
        check("native missing schema rejected with actionable path", rejectedSchema(config));
        config.set("config-version", "1");
        check("native string schema rejected without coercion", rejectedSchema(config));
        config.set("config-version", 2);
        check("native incompatible schema rejected", rejectedSchema(config));
        check("native literal text formatting examples survive post-color substitution",
                settings.text().text("messages.config-prompt", Collections.singletonMap("detail", "&5 purple &l bold")).contains("&5 purple &l bold"));
        check("native shelf-status token is restricted to balance/info",
                MenuText.placeholders("ui.icons.info.lore").contains("bookshelf-status") &&
                !MenuText.placeholders("ui.option-lore").contains("bookshelf-status"));
    }

    private boolean rejectedSchema(YamlConfiguration config) {
        try {
            Settings.read(config);
            return false;
        } catch (IllegalArgumentException expected) {
            return expected.getMessage().contains("config-version") && expected.getMessage().contains("upgrade");
        }
    }

    private void nativeScratchShelves(World world, BookshelfPricing pricing) {
        // Never clear the seeded playtester's arena or any occupied scratch block.
        Location table = new Location(world, 8, 250, 8);
        List<BlockState> saved = new ArrayList<BlockState>();
        for (int x = 6; x <= 10; x++) {
            for (int z = 6; z <= 10; z++) {
                for (int y = 250; y <= 251; y++) {
                    BlockState state = world.getBlockAt(x, y, z).getState();
                    if (state.getType() != Material.AIR) {
                        getLogger().info("QA SKIP native bookshelf scratch checks: occupied block; no world changes made.");
                        return;
                    }
                    saved.add(state);
                }
            }
        }
        try {
            world.getBlockAt(8, 250, 8).setType(Material.ENCHANTMENT_TABLE);
            check("native scratch empty enchanting ring counts zero", pricing.count(table) == 0);
            world.getBlockAt(10, 250, 10).setType(Material.BOOKSHELF);
            world.getBlockAt(10, 251, 9).setType(Material.BOOKSHELF);
            check("native scratch diagonal corner and upper side are unique positions", pricing.count(table) == 2);
            world.getBlockAt(9, 251, 9).setType(Material.STONE);
            check("native scratch upper blocked diagonal gap removes both columns", pricing.count(table) == 0);
            world.getBlockAt(9, 251, 9).setType(Material.AIR);
            setShelves(table, 32);
            check("native scratch 32-position ring caps bookshelf power at 15", pricing.count(table) == 15);
            YamlConfiguration lower = new YamlConfiguration();
            lower.set("bookshelf-pricing.max-bookshelves", 3);
            BookshelfPricing limited = BookshelfPricing.read(lower);
            check("native configured effective count and displayed maximum agree below fifteen",
                    limited.count(table) == 3 && limited.maximum() == 3);
        } finally {
            boolean restored = true;
            for (BlockState state : saved) {
                restored &= state.update(true, false);
                restored &= state.getBlock().getType() == state.getType();
            }
            check("native scratch blocks restored", restored);
        }
    }

    private void setShelves(Location table, int count) {
        World world = table.getWorld();
        if (!"qa-world".equals(world.getName())) { throw new IllegalStateException("Not in disposable qa-world"); }
        int index = 0;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                if (Math.max(Math.abs(x), Math.abs(z)) == 2) {
                    for (int height = 0; height <= 1; height++) {
                        world.getBlockAt(table.getBlockX() + x, table.getBlockY() + height, table.getBlockZ() + z)
                                .setType(index++ < count ? Material.BOOKSHELF : Material.AIR);
                    }
                }
            }
        }
    }

}
