package io.github.stealingdapenta.enchantxp.menu;

import io.github.stealingdapenta.enchantxp.configuration.Settings;
import io.github.stealingdapenta.enchantxp.enchanting.BookshelfPricing;
import io.github.stealingdapenta.enchantxp.enchanting.Enchantments;
import io.github.stealingdapenta.enchantxp.enchanting.EnchantPermissions;
import io.github.stealingdapenta.enchantxp.enchanting.EnchantPolicy;
import io.github.stealingdapenta.enchantxp.enchanting.Experience;
import io.github.stealingdapenta.enchantxp.enchanting.Prices;
import io.github.stealingdapenta.enchantxp.presentation.MenuSounds;
import io.github.stealingdapenta.enchantxp.presentation.MenuStage;
import io.github.stealingdapenta.enchantxp.presentation.MenuText;
import static io.github.stealingdapenta.enchantxp.menu.EnchantAccess.eligible;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public final class TableMenus implements Listener {
    static final int BACK_SLOT = 45;
    static final int PREVIOUS_SLOT = 47;
    static final int CANCEL_SLOT = 49;
    static final int CONFIRM_SLOT = 51;
    static final int NEXT_SLOT = 53;
    static final int INFO_SLOT = 50;
    static final int SORT_SLOT = 52;
    static final int PREVIEW_SLOT = 4;
    private static final double MAX_REACH_SQUARED = 25.0;
    private final Plugin plugin;
    private Prices prices;
    private EnchantPolicy policy;
    private MenuText text;
    private MenuLayout layout;
    private EnchantTransaction transaction;
    private MenuRenderer renderer;
    private BookshelfPricing shelves;
    private EnchantPermissions permissions;
    private MenuSounds sounds;
    private final EnchantPermissions.Registry registry;
    private final Map<UUID, EnchantSession> sessions = new HashMap<UUID, EnchantSession>();
    private boolean running = true;
    private boolean replacing;

    public TableMenus(Plugin plugin, Settings settings) {
        this.plugin = plugin;
        this.prices = settings.prices();
        this.policy = settings.policy();
        this.text = settings.text();
        this.layout = new MenuLayout(settings.rows());
        this.shelves = settings.shelves();
        this.permissions = settings.permissions();
        this.sounds = settings.sounds();
        this.registry = new EnchantPermissions.Registry(plugin.getServer().getPluginManager());
        registry.prepare(permissions, policy);
        this.transaction = new EnchantTransaction(prices, policy, shelves, permissions);
        this.renderer = new MenuRenderer(prices, policy, text, layout, shelves);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void interact(PlayerInteractEvent event) {
        requireMainThread();
        Block block = event.getClickedBlock();
        if (event.isCancelled() || !running || replacing || event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null || block.getType() != Material.ENCHANTMENT_TABLE) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!eligible(player)) {
            message(player, "eligibility");
            return;
        }
        int slot = player.getInventory().getHeldItemSlot();
        forget(player.getUniqueId());
        EnchantSession session = new EnchantSession(player.getUniqueId(), slot, block.getLocation());
        sessions.put(player.getUniqueId(), session);
        InventoryView expected = player.getOpenInventory();
        queue(player, session, () -> {
            if (!context(player, session) || player.getOpenInventory() != expected) {
                message(player, "context");
                finish(player, session);
                return;
            }
            show(player, session, build(player, session, MenuStage.ITEM, null, 0, 0));
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        requireMainThread();
        MenuHolder holder = holder(event.getView());
        if (holder == null) {
            return;
        }
        boolean previouslyCancelled = event.isCancelled();
        event.setCancelled(true);
        if (previouslyCancelled || !(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        EnchantSession session = sessions.get(player.getUniqueId());
        if (!owns(player, session, holder) || event.getView() != player.getOpenInventory() ||
                event.getRawSlot() < 0) {
            return;
        }
        int slot = event.getRawSlot();
        MenuLayout controls = holder.layout;
        if (holder.page == MenuStage.ITEM && event.getClickedInventory() == event.getView().getBottomInventory() &&
                event.getSlot() >= 0 && event.getSlot() < EnchantSession.STORAGE_SIZE &&
                slot >= controls.size && slot < controls.size + EnchantSession.STORAGE_SIZE) {
            ClickType click = event.getClick();
            if (session.pending || (click != ClickType.LEFT && click != ClickType.RIGHT &&
                    click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT)) {
                return;
            }
            int sourceSlot = event.getSlot();
            ItemStack item = player.getInventory().getItem(sourceSlot);
            ItemStack snapshot = item == null ? null : item.clone();
            // Acknowledge the selection attempt, not availability; shelf and choice validation stays deferred.
            if (!empty(snapshot)) {
                clickSound(player);
            }
            queue(player, session, () -> select(player, session, holder, sourceSlot, snapshot));
            return;
        }
        if (event.getClick() != ClickType.LEFT) {
            return;
        }
        if (slot == controls.cancel) {
            clickSound(player);
            // Fence queued work now; the 1.12.2 click contract requires deferring only the inventory close.
            forget(player.getUniqueId());
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (running && player.isOnline() && !player.isDead()) {
                    closeOrphanMenu(player);
                }
            });
            return;
        }
        if (slot == controls.back && holder.page != MenuStage.ITEM) {
            if (session.pending && session.pendingNavigation == slot) {
                return;
            }
            session.pendingNavigation = slot;
            clickSound(player);
            // Fence already-dispatched Confirm/selection work before deferring navigation.
            session.actionGeneration++;
            queue(player, session, () -> back(player, session, holder));
            return;
        }
        if (slot == controls.sort && sortable(holder)) {
            if (session.pending && session.pendingNavigation == slot) {
                return;
            }
            session.pendingNavigation = slot;
            clickSound(player);
            session.actionGeneration++;
            queue(player, session, () -> sort(player, session, holder));
            return;
        }
        if (session.pending) {
            return;
        }
        if (slot >= controls.size) {
            return;
        }
        boolean actionable = holder.choices.containsKey(slot) || holder.levels.containsKey(slot) ||
                (holder.page == MenuStage.CONFIRM && slot == controls.confirm) ||
                (slot == controls.previous && holder.index > 0) ||
                (slot == controls.next && holder.index + 1 < holder.pages);
        if (!actionable) {
            return;
        }
        if (!(holder.page == MenuStage.CONFIRM && slot == controls.confirm)) {
            clickSound(player);
        }
        queue(player, session, () -> act(player, session, holder, slot));
    }

    private void queue(Player player, EnchantSession session, Runnable action) {
        long generation = session.actionGeneration;
        session.pending = true;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!active(player, session) || generation != session.actionGeneration) {
                return;
            }

            try {
                action.run();
            } catch (IllegalArgumentException invalidBalance) {
                message(player, "invalid-balance");
                finish(player, session);
            } catch (RuntimeException failure) {
                plugin.getLogger().log(Level.SEVERE, "EnchantXP menu action failed; no automatic retry", failure);
                message(player, "failure");
                finish(player, session);
            }
        });
    }

    private void clickSound(Player player) {
        try {
            sounds.play(player, "click");
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not play EnchantXP click sound", failure);
        }
    }

    private void select(Player player, EnchantSession session, MenuHolder holder, int slot, ItemStack snapshot) {
        if (!owns(player, session, holder)) {
            return;
        }
        ItemStack item = player.getInventory().getItem(slot);
        if (!context(player, session) || (snapshot == null ? item != null : !snapshot.equals(item))) {
            message(player, "context");
            finish(player, session);
            return;
        }
        if (choices(player, item, shelves.count(session.table)).isEmpty()) {
            session.pending = false;
            session.pendingNavigation = -1;
            message(player, !Enchantments.supported(item, policy) ? "unsupported" :
                    Enchantments.hasLegalUpgrade(item, policy) ? "unavailable" : "no-upgrades");
            return;
        }
        if (EnchantTransaction.outputSlot(player, slot, item) < 0) {
            session.pending = false;
            message(player, "no-space");
            return;
        }
        EnchantSession selected = new EnchantSession(session.owner, session.heldSlot, slot, item, session.table);
        selected.sort = session.sort;
        session.invalidate();
        sessions.put(session.owner, selected);
        selected.pending = true;
        try {
            show(player, selected, build(player, selected, MenuStage.ENCHANTMENT, null, 0, 0));
        } catch (RuntimeException failure) {
            finish(player, selected);
            throw failure;
        }
    }

    private void act(Player player, EnchantSession session, MenuHolder holder, int slot) {
        if (!owns(player, session, holder)) {
            return;
        }
        if (!context(player, session)) {
            message(player, "context");
            finish(player, session);
            return;
        }
        if (slot == holder.layout.previous || slot == holder.layout.next) {
            int index = holder.index + (slot == holder.layout.next ? 1 : -1);
            show(player, session, build(player, session, holder.page, holder.enchantment, holder.level, index));
        } else if (holder.page == MenuStage.ENCHANTMENT) {
            show(player, session, build(player, session, MenuStage.LEVEL, holder.choices.get(slot), 0, 0));
        } else if (holder.page == MenuStage.LEVEL) {
            int target = holder.levels.get(slot);
            if (!available(player, session.snapshot(), holder.enchantment, target, shelves.count(session.table))) {
                refreshLevels(player, session, holder);
                return;
            }
            show(player, session, build(player, session, MenuStage.CONFIRM, holder.enchantment, holder.levels.get(slot), 0));
        } else {
            confirm(player, session, holder);
        }
    }

    private void confirm(Player player, EnchantSession session, MenuHolder holder) {
        if (!available(player, session.snapshot(), holder.enchantment, holder.level, shelves.count(session.table))) {
            refreshLevels(player, session, holder);
            return;
        }
        PurchaseQuote fresh = PurchaseQuote.read(player, session, holder.enchantment, holder.level, prices, shelves);
        if (!fresh.affordable) {
            Map<String, String> values = renderer.values(player, session, holder);
            values.put("cost", Integer.toString(fresh.cost));
            text.send(player, "insufficient", values);
            sounds.play(player, "insufficient");
            show(player, session, build(player, session, MenuStage.CONFIRM, holder.enchantment, holder.level, 0));
            return;
        }
        if (fresh.differsFrom(holder.quote)) {
            message(player, "quote-changed");
            show(player, session, build(player, session, MenuStage.CONFIRM, holder.enchantment, holder.level, 0));
            return;
        }
        boolean retry = false;
        try {
            int balance = fresh.mode == GameMode.CREATIVE ? 0 : balance(player);
            Map<String, String> values = renderer.values(player, session, holder);
            boolean success = transaction.commit(player, session, holder.enchantment, holder.level,
                    () -> context(player, session), holder.quote);
            values.put("balance", fresh.mode == GameMode.CREATIVE ? text.text("ui.unlimited-balance", Collections.emptyMap()) :
                    Integer.toString(success ? balance - fresh.cost : balance));
            text.send(player, success ? "success" : "stale", values);
            if (success) {
                sounds.play(player, "success");
                player.updateInventory();
            }
        } catch (EnchantTransaction.QuoteChangedException changed) {
            retry = true;
            message(player, "quote-changed");
            show(player, session, build(player, session, MenuStage.LEVEL, holder.enchantment, 0, 0));
        } catch (EnchantTransaction.OutputSpaceException failure) {
            message(player, "no-space");
        } catch (IllegalArgumentException failure) {
            message(player, "invalid-balance");
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.SEVERE, "Enchant transaction failed; attempted rollback", failure);
            message(player, "failure");
        } finally {
            if (!retry) {
                finish(player, session);
            }
        }
    }

    private void refreshLevels(Player player, EnchantSession session, MenuHolder holder) {
        message(player, "quote-changed");
        show(player, session, build(player, session, MenuStage.LEVEL, holder.enchantment, 0, 0));
    }

    private void sort(Player player, EnchantSession session, MenuHolder holder) {
        if (!owns(player, session, holder)) {
            return;
        }
        if (!context(player, session)) {
            message(player, "context");
            finish(player, session);
            return;
        }
        session.sort = session.sort.next();
        show(player, session, build(player, session, holder.page, holder.enchantment, 0, 0));
    }

    private void back(Player player, EnchantSession session, MenuHolder holder) {
        if (!owns(player, session, holder)) {
            return;
        }
        if (!context(player, session)) {
            message(player, "context");
            finish(player, session);
            return;
        }
        if (holder.page == MenuStage.ENCHANTMENT) {
            EnchantSession unselected = new EnchantSession(session.owner, session.heldSlot, session.table);
            unselected.sort = session.sort;
            session.invalidate();
            sessions.put(session.owner, unselected);
            try {
                show(player, unselected, build(player, unselected, MenuStage.ITEM, null, 0, 0));
            } catch (RuntimeException failure) {
                finish(player, unselected);
                throw failure;
            }
        } else {
            MenuStage previous = holder.page == MenuStage.CONFIRM ? MenuStage.LEVEL : MenuStage.ENCHANTMENT;
            show(player, session, build(player, session, previous, holder.enchantment, 0, 0));
        }
    }

    private MenuHolder build(Player player, EnchantSession session, MenuStage page, Enchantment enchantment, int level, int index) {
        int countShelves = shelves.count(session.table);
        List<Enchantment> choices = page == MenuStage.ENCHANTMENT ?
                choices(player, session.snapshot(), countShelves) : Collections.emptyList();
        List<Integer> levels = page == MenuStage.LEVEL ?
                levels(player, session.snapshot(), enchantment, countShelves) : Collections.emptyList();
        return renderer.build(
                this, player, session, page, enchantment, level, index, countShelves, choices, levels);
    }

    private static int balance(Player player) {
        return Experience.total(player.getLevel(), player.getExp());
    }

    private static boolean sortable(MenuHolder holder) {
        return holder.availableChoices > 1 &&
                (holder.page == MenuStage.ENCHANTMENT || holder.page == MenuStage.LEVEL);
    }

    private boolean available(Player player, ItemStack item, Enchantment enchantment, int level, int countShelves) {
        long cost = shelves.quote(prices, enchantment, Enchantments.level(item, enchantment), level, countShelves);
        return Enchantments.legal(item, enchantment, level, policy) &&
                permissions.allows(player, enchantment, level) && prices.accepts(cost);
    }

    private List<Integer> levels(Player player, ItemStack item, Enchantment enchantment, int countShelves) {
        List<Integer> result = new ArrayList<Integer>();
        for (int level : Enchantments.levels(item, enchantment, prices, policy)) {
            if (available(player, item, enchantment, level, countShelves)) {
                result.add(level);
            }
        }
        return result;
    }

    private List<Enchantment> choices(Player player, ItemStack item, int countShelves) {
        List<Enchantment> result = new ArrayList<Enchantment>();
        for (Enchantment enchantment : Enchantments.choices(item, prices, policy)) {
            if (!levels(player, item, enchantment, countShelves).isEmpty()) {
                result.add(enchantment);
            }
        }
        return result;
    }

    private void message(Player player, String key) {
        text.send(player, key, Collections.emptyMap());
    }

    private void show(Player player, EnchantSession session, MenuHolder next) {
        if (!active(player, session)) {
            return;
        }
        // The old menu's close event must not invalidate its replacement.
        session.menu = next;
        player.openInventory(next.getInventory());
        if (!owns(player, session, next)) {
            finish(player, session);
            return;
        }
        session.pending = false;
        session.pendingNavigation = -1;
    }

    private boolean context(Player player, EnchantSession session) {
        if (!active(player, session) || !eligible(player) || !empty(player.getItemOnCursor()) ||
                player.getInventory().getHeldItemSlot() != session.heldSlot ||
                (session.hasSelection() && !session.matches(player.getInventory().getItem(session.slot)))) {
            return false;
        }
        Location position = player.getLocation();
        Location table = session.table;
        return position.getWorld().equals(table.getWorld()) &&
                table.getWorld().isChunkLoaded(table.getBlockX() >> 4, table.getBlockZ() >> 4) &&
                position.distanceSquared(table.clone().add(0.5, 0.5, 0.5)) <= MAX_REACH_SQUARED &&
                table.getBlock().getType() == Material.ENCHANTMENT_TABLE;
    }

    private boolean active(Player player, EnchantSession session) {
        return running && !replacing && session != null && session.owner.equals(player.getUniqueId()) &&
                sessions.get(player.getUniqueId()) == session;
    }

    private boolean owns(Player player, EnchantSession session, MenuHolder holder) {
        // 1.12.2 wraps the inventory again for its open view, but retains the original holder.
        return active(player, session) && session.menu == holder && holder.owner.equals(player.getUniqueId()) &&
                holder.sessionId.equals(session.id) && holder(player.getOpenInventory()) == holder &&
                player.getOpenInventory().getPlayer().getUniqueId().equals(session.owner);
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() == 0;
    }

    private MenuHolder holder(InventoryView view) {
        Inventory inventory = view.getTopInventory();
        if (!(inventory.getHolder() instanceof MenuHolder)) {
            return null;
        }
        MenuHolder holder = (MenuHolder) inventory.getHolder();
        return holder.controller == this ? holder : null;
    }

    private void finish(Player player, EnchantSession session) {
        if (sessions.get(player.getUniqueId()) == session) {
            forget(player.getUniqueId());
        }
        closeOrphanMenu(player);
    }

    private void closeOrphanMenu(Player player) {
        if (!player.isOnline() || sessions.containsKey(player.getUniqueId())) {
            return;
        }
        MenuHolder orphan = holder(player.getOpenInventory());
        if (orphan != null && orphan.owner.equals(player.getUniqueId()) &&
                player.getOpenInventory().getPlayer().getUniqueId().equals(orphan.owner)) {
            player.closeInventory();
        }
    }

    private void forget(UUID owner) {
        EnchantSession removed = sessions.remove(owner);
        if (removed != null) {
            removed.invalidate();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event) {
        requireMainThread();
        if (holder(event.getView()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void close(InventoryCloseEvent event) {
        requireMainThread();
        MenuHolder closed = holder(event.getView());
        EnchantSession session = sessions.get(event.getPlayer().getUniqueId());
        if (closed != null && session != null && session.menu == closed) {
            forget(session.owner);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        requireMainThread();
        forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void kick(PlayerKickEvent event) {
        requireMainThread();
        forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void death(PlayerDeathEvent event) {
        requireMainThread();
        forget(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent event) {
        requireMainThread();
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void swap(PlayerSwapHandItemsEvent event) {
        requireMainThread();
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void held(PlayerItemHeldEvent event) {
        requireMainThread();
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    public void shutdown() {
        requireMainThread();
        running = false;
        plugin.getServer().getScheduler().cancelTasks(plugin);
        invalidateMenus();
        registry.shutdown();
    }

    public void replace(Settings settings) {
        replace(settings, () -> true);
    }

    public void replace(Settings settings, BooleanSupplier lifecycleActive) {
        requireMainThread();
        if (!running) {
            throw new IllegalStateException("EnchantXP menus are stopped");
        }

        registry.prepare(settings.permissions(), settings.policy());
        replacing = true;
        try {
            invalidateMenus();
            if (!running || !lifecycleActive.getAsBoolean()) {
                throw new IllegalStateException("EnchantXP menus stopped while closing inventories");
            }
            prices = settings.prices();
            policy = settings.policy();
            text = settings.text();
            layout = new MenuLayout(settings.rows());
            shelves = settings.shelves();
            permissions = settings.permissions();
            sounds = settings.sounds();
            transaction = new EnchantTransaction(prices, policy, shelves, permissions);
            renderer = new MenuRenderer(prices, policy, text, layout, shelves);
        } finally {
            replacing = false;
        }
    }

    public void validateReplacement(Settings settings) {
        requireMainThread();
        if (!running) {
            throw new IllegalStateException("EnchantXP menus are stopped");
        }
        registry.validate(settings.permissions());
    }

    private void invalidateMenus() {
        for (EnchantSession session : sessions.values()) {
            session.invalidate();
        }
        sessions.clear();
        for (Player player : new ArrayList<Player>(plugin.getServer().getOnlinePlayers())) {
            closeOrphanMenu(player);
        }
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("EnchantXP inventory operations require the server thread");
        }
    }
}
