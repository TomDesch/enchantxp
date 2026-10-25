package io.github.stealingdapenta.enchantxp;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentTarget;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

final class ServerFixture {
    static final String LEGACY_PRICING = "bookshelf-pricing:\n  enabled: false\n";
    private static final Server SERVER = mock(Server.class);
    static final Enchantment SHARPNESS = new TestEnchantment(16, "DAMAGE_ALL", 5);
    static final Enchantment SMITE = new TestEnchantment(17, "DAMAGE_UNDEAD", 5);
    static final Enchantment UNBREAKING = new TestEnchantment(34, "DURABILITY", 3);
    static final Enchantment CUSTOM = new TestEnchantment(900, "TEST_CUSTOM", 5);
    static {
        when(SERVER.getLogger()).thenReturn(Logger.getLogger("EnchantXP-test"));
        Bukkit.setServer(SERVER);
        Enchantment.registerEnchantment(SHARPNESS);
        Enchantment.registerEnchantment(SMITE);
        Enchantment.registerEnchantment(UNBREAKING);
        Enchantment.registerEnchantment(CUSTOM);
        int id = 100;
        for (String name : Enchantments.names()) {
            if (Enchantment.getByName(name) == null) {
                Enchantment.registerEnchantment(new TestEnchantment(id++, name, 3));
            }
        }
    }
    final Plugin plugin = mock(Plugin.class);
    final Player player = mock(Player.class);
    final PlayerInventory inventory = mock(PlayerInventory.class);
    final World world = mock(World.class);
    final Block block = mock(Block.class);
    final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    final SimplePluginManager manager;
    final Map<Integer, ItemStack> items = new HashMap<Integer, ItemStack>();
    final Queue<Runnable> tasks = new ArrayDeque<Runnable>();
    final Queue<Runnable> asyncTasks = new ArrayDeque<Runnable>();
    final Prices prices;
    final TableMenus menus;
    InventoryView view;
    int level;
    float progress;
    int totalScore;
    int writes;

    ServerFixture() throws Exception {
        this(LEGACY_PRICING + "base-xp: 25\nmax-cost: 1000000\n");
    }

    ServerFixture(String config) throws Exception {
        reset(SERVER);
        when(SERVER.getLogger()).thenReturn(Logger.getLogger("EnchantXP-test"));
        when(SERVER.isPrimaryThread()).thenReturn(true);
        when(SERVER.getScheduler()).thenReturn(scheduler);
        when(plugin.getServer()).thenReturn(SERVER);
        manager = new SimplePluginManager(SERVER, null);
        when(SERVER.getPluginManager()).thenReturn(manager);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("EnchantXP-test"));
        when(SERVER.createInventory(any(InventoryHolder.class), anyInt(), anyString())).thenAnswer(call ->
                inventory(call.getArgument(0), call.getArgument(1)));
        ItemFactory factory = mock(ItemFactory.class);
        when(SERVER.getItemFactory()).thenReturn(factory);
        when(factory.getItemMeta(any(Material.class))).thenAnswer(call ->
                call.getArgument(0) == Material.ENCHANTED_BOOK ? new MemoryBookMeta() : new MemoryItemMeta());
        when(factory.isApplicable(any(ItemMeta.class), any(Material.class))).thenReturn(true);
        when(factory.asMetaFor(any(ItemMeta.class), any(Material.class))).thenAnswer(call -> {
            ItemMeta meta = call.getArgument(0);
            if (call.getArgument(1) == Material.ENCHANTED_BOOK && meta instanceof MemoryItemMeta &&
                    !(meta instanceof MemoryBookMeta)) {
                MemoryBookMeta book = new MemoryBookMeta();
                ((MemoryItemMeta) meta).copyTo(book);
                return book;
            }
            return meta.clone();
        });
        when(factory.equals(any(), any())).thenAnswer(call -> metaEquals(call.getArgument(0), call.getArgument(1)));
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(SERVER.getPlayer(player.getUniqueId())).thenReturn(player);
        when(player.isOnline()).thenReturn(true);
        when(SERVER.getOnlinePlayers()).thenAnswer(call ->
                player.isOnline() ? Collections.singletonList(player) : Collections.emptyList());
        when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(player.hasPermission(TableMenus.USE_PERMISSION)).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getSize()).thenReturn(41);
        when(inventory.getItem(anyInt())).thenAnswer(call -> items.get(call.getArgument(0)));
        doAnswer(call -> { items.put(call.getArgument(0), call.getArgument(1)); writes++; return null; }).when(inventory).setItem(anyInt(), nullable(ItemStack.class));
        when(player.getLocation()).thenAnswer(call -> new Location(world, 0.5, 64, 1.5));
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
        when(world.getBlockAt(any(Location.class))).thenReturn(block);
        when(block.getType()).thenReturn(Material.ENCHANTMENT_TABLE);
        when(block.getLocation()).thenAnswer(call -> new Location(world, 0, 64, 0));
        when(player.getLevel()).thenAnswer(call -> level);
        when(player.getExp()).thenAnswer(call -> progress);
        when(player.getTotalExperience()).thenAnswer(call -> totalScore);
        doAnswer(call -> { level = call.getArgument(0); return null; }).when(player).setLevel(anyInt());
        doAnswer(call -> { progress = call.getArgument(0); return null; }).when(player).setExp(anyFloat());
        doAnswer(call -> { totalScore = call.getArgument(0); return null; }).when(player).setTotalExperience(anyInt());
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> { tasks.add(call.getArgument(1)); return mock(BukkitTask.class); });
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            asyncTasks.add(call.getArgument(1)); return mock(BukkitTask.class);
        });
        doAnswer(call -> { tasks.clear(); return null; }).when(scheduler).cancelTasks(plugin);
        Settings settings = Settings.read(PricesTest.yaml(config));
        prices = settings.prices;
        menus = new TableMenus(plugin, settings);
        view = new View(inventory(null, 9));
        when(player.getOpenInventory()).thenAnswer(call -> view);
        when(player.openInventory(any(Inventory.class))).thenAnswer(call -> {
            InventoryView old = view;
            menus.close(new InventoryCloseEvent(old));
            view = new View(wrapper(call.getArgument(0)));
            return view;
        });
        doAnswer(call -> {
            menus.close(new InventoryCloseEvent(view));
            view = new View(inventory(null, 9));
            return null;
        }).when(player).closeInventory();
        items.put(0, new ItemStack(Material.DIAMOND_SWORD));
        balance(1000);
    }

    void balance(int value) {
        Experience.Balance balance = Experience.balance(value);
        level = balance.level();
        progress = balance.progress();
        totalScore = 987654321;
    }

    void fillStorage() {
        for (int slot = 0; slot < EnchantSession.STORAGE_SIZE; slot++) {
            items.putIfAbsent(slot, new ItemStack(Material.DIRT, 64));
        }
    }

    EnchantSession session() {
        return new EnchantSession(player.getUniqueId(), 0, items.get(0), block.getLocation());
    }

    void open() {
        openTable();
        if (menu() != null && menu().page == MenuHolder.Page.ITEM) {
            select(0, ClickType.LEFT);
            tick();
        }
    }

    void openTable() {
        menus.interact(new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, items.get(0), block, BlockFace.UP));
        tick();
    }

    InventoryClickEvent select(int slot, ClickType click) {
        int rawSlot = view.getTopInventory().getSize() + (slot < 9 ? 27 + slot : slot - 9);
        return click(rawSlot, click);
    }

    void tick() {
        int count = tasks.size();
        for (int i = 0; i < count; i++) { tasks.remove().run(); }
    }

    MenuHolder menu() { return (MenuHolder) view.getTopInventory().getHolder(); }

    InventoryClickEvent click(int slot, ClickType click) {
        InventoryClickEvent event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, slot, click, InventoryAction.PICKUP_ALL);
        menus.click(event);
        return event;
    }

    void confirmPage() {
        open();
        int enchantSlot = menu().choices.entrySet().stream().filter(entry -> entry.getValue().equals(SHARPNESS)).findFirst().get().getKey();
        click(enchantSlot, ClickType.LEFT);
        tick();
        click(11, ClickType.LEFT);
        tick();
    }

    private static boolean metaEquals(ItemMeta first, ItemMeta second) {
        MemoryItemMeta empty = new MemoryItemMeta();
        return (first == null ? empty : first).equals(second == null ? empty : second);
    }

    private static Inventory inventory(InventoryHolder holder, int size) {
        Inventory result = mock(Inventory.class);
        Map<Integer, ItemStack> contents = new HashMap<Integer, ItemStack>();
        when(result.getHolder()).thenReturn(holder);
        when(result.getSize()).thenReturn(size);
        when(result.getItem(anyInt())).thenAnswer(call -> contents.get(call.getArgument(0)));
        doAnswer(call -> { contents.put(call.getArgument(0), call.getArgument(1)); return null; }).when(result).setItem(anyInt(), nullable(ItemStack.class));
        return result;
    }

    private static Inventory wrapper(Inventory backing) {
        Inventory result = mock(Inventory.class);
        when(result.getHolder()).thenAnswer(call -> backing.getHolder());
        when(result.getSize()).thenAnswer(call -> backing.getSize());
        when(result.getItem(anyInt())).thenAnswer(call -> backing.getItem(call.getArgument(0)));
        doAnswer(call -> {
            backing.setItem(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(result).setItem(anyInt(), any(ItemStack.class));
        return result;
    }

    final class View extends InventoryView {
        private final Inventory top;
        View(Inventory top) { this.top = top; }
        @Override public Inventory getTopInventory() { return top; }
        @Override public Inventory getBottomInventory() { return inventory; }
        @Override public HumanEntity getPlayer() { return player; }
        @Override public InventoryType getType() { return InventoryType.CHEST; }
    }

    private static final class TestEnchantment extends Enchantment {
        private final String name;
        private final int maximum;
        TestEnchantment(int id, String name, int maximum) { super(id); this.name = name; this.maximum = maximum; }
        @Override public String getName() { return name; }
        @Override public int getMaxLevel() { return maximum; }
        @Override public int getStartLevel() { return 1; }
        @Override public EnchantmentTarget getItemTarget() { return EnchantmentTarget.WEAPON; }
        @Override public boolean isTreasure() { return false; }
        @Override public boolean isCursed() { return false; }
        @Override public boolean conflictsWith(Enchantment other) { return this == SMITE && other.equals(SHARPNESS); }
        @Override public boolean canEnchantItem(ItemStack item) {
            return (this == SHARPNESS || this == SMITE || this == UNBREAKING || this == CUSTOM) &&
                    item.getType() == Material.DIAMOND_SWORD;
        }
    }
}
