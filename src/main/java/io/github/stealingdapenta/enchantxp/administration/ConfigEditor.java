package io.github.stealingdapenta.enchantxp.administration;

import io.github.stealingdapenta.enchantxp.presentation.MenuText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

/** Player-owned drafts, with next-tick inventory navigation and private, bounded chat input. */
final class ConfigEditor implements Listener {
    static final String PERMISSION = "enchantxp.config";
    static final int BACK = 45;
    static final int PREVIOUS = 47;
    static final int DISCARD = 49;
    static final int APPLY = 51;
    static final int NEXT = 53;
    private static final int PAGE_SIZE = 28;
    private static final int MAX_SESSIONS = 64;
    private static final int MAX_INPUT = 256;
    private static final long LIFETIME_TICKS = 20 * 60 * 15;
    private static final long LIFETIME_NANOS = 15L * 60 * 1000000000;
    private final Plugin plugin;
    private final ReloadCommand coordinator;
    private final BukkitScheduler scheduler;
    private final Logger logger;
    private final EditorPages pages = new EditorPages(this);
    private final Map<UUID, Draft> drafts = new HashMap<UUID, Draft>();
    // Async chat may inspect only this lookup and its immutable prompt, never a YAML document.
    private final Map<Player, Prompt> prompts = new ConcurrentHashMap<Player, Prompt>();

    ConfigEditor(Plugin plugin, ReloadCommand coordinator) {
        this.plugin = plugin;
        this.coordinator = coordinator;
        this.scheduler = plugin.getServer().getScheduler();
        this.logger = plugin.getLogger();
    }

    void open(Player player, YamlConfiguration document, byte[] hash, long generation) {
        requireMainThread();
        discard(player);
        if (drafts.size() >= MAX_SESSIONS) {
            coordinator.status(player, "config-error", "Too many settings menus are open. Please try again later.");
            return;
        }
        Draft draft = new Draft(player, document, hash, generation);
        drafts.put(player.getUniqueId(), draft);
        try {
            draft.expiry = scheduler.runTaskLater(plugin, () -> {
                if (drafts.get(draft.owner) == draft) {
                    discard(player);
                    closeOwned(player);
                    coordinator.status(player, "config-expired", "");
                }
            }, LIFETIME_TICKS);
            show(draft);
        } catch (RuntimeException failure) {
            if (drafts.get(draft.owner) == draft) {
                discard(player);
            }
            throw failure;
        }
    }

    boolean resume(Player player) {
        requireMainThread();
        Draft draft = drafts.get(player.getUniqueId());
        if (draft == null) {
            return false;
        }
        if (!permitted(draft)) {
            discard(player);
            return false;
        }
        prompts.remove(player);
        show(draft);
        return true;
    }

    boolean current(Draft draft) {
        return drafts.get(draft.owner) == draft && System.nanoTime() - draft.created < LIFETIME_NANOS;
    }

    private boolean permitted(Draft draft) {
        return current(draft) && draft.player.isOnline() &&
                plugin.getServer().getPlayer(draft.owner) == draft.player && draft.player.hasPermission(PERMISSION);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        requireMainThread();
        EditorHolder holder = holder(event.getView());
        if (holder == null) {
            return;
        }
        boolean cancelled = event.isCancelled();
        event.setCancelled(true);
        if (cancelled || event.getWhoClicked() != holder.draft.player ||
                event.getView() != holder.draft.player.getOpenInventory() || holder.draft.view != holder ||
                !permitted(holder.draft)) {
            return;
        }
        Draft draft = holder.draft;
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == DISCARD && event.getClick() == ClickType.LEFT) {
            // Cancellation fences a queued save before next-tick inventory work.
            discard(draft.player);
            defer(() -> {
                closeOwned(draft.player);
                coordinator.status(draft.player, "config-discarded", "");
            });
            return;
        }
        if (draft.pending || saving(draft)) {
            return;
        }
        Entry entry = holder.entries.get(slot);
        if (entry == null) {
            return;
        }
        Runnable action = event.getClick() == ClickType.RIGHT ? entry.reset : entry.action;
        if (action == null) {
            return;
        }
        draft.pending = true;
        try {
            defer(() -> {
                if (!permitted(draft) || draft.view != holder ||
                        draft.player.getOpenInventory().getTopInventory().getHolder() != holder) {
                    draft.pending = false;
                    return;
                }
                draft.pending = false;
                try {
                    action.run();
                } catch (IllegalArgumentException failure) {
                    coordinator.status(draft.player, "config-error", failure.getMessage());
                }
            });
        } catch (RuntimeException failure) {
            draft.pending = false;
            coordinator.status(draft.player, "config-error", "Could not open the next page. Try again.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event) {
        requireMainThread();
        if (holder(event.getView()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void close(InventoryCloseEvent event) {
        requireMainThread();
        EditorHolder holder = holder(event.getView());
        if (holder == null) {
            return;
        }
        Draft draft = holder.draft;
        if (current(draft) && draft.view == holder && !draft.transition && !prompts.containsKey(draft.player)) {
            discard(draft.player);
            coordinator.status(draft.player, "config-discarded", "");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void chat(AsyncPlayerChatEvent event) {
        Prompt prompt = prompts.get(event.getPlayer());
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        String input = event.getMessage();
        final String bounded = input.length() > MAX_INPUT ? null : input;
        try {
            defer(() -> completeInput(prompt, bounded));
        } catch (RuntimeException failure) {
            // Keep the input private even if disable raced the scheduler.
            logger.warning("Could not schedule EnchantXP settings input; reopen /enchantxp config.");
        }
    }

    private void completeInput(Prompt prompt, String input) {
        requireMainThread();
        Draft draft = prompt.draft;
        if (prompts.get(draft.player) != prompt) {
            return;
        }
        if (!permitted(draft)) {
            prompts.remove(draft.player, prompt);
            discard(draft.player);
            return;
        }
        if (draft.player.getOpenInventory() != prompt.expectedView) {
            prompts.remove(draft.player, prompt);
            discard(draft.player);
            coordinator.status(draft.player, "config-error", "Settings input cancelled because another inventory opened. Reopen /enchantxp config.");
            return;
        }
        if (input == null) {
            coordinator.status(draft.player, "config-error", "Keep your reply to 256 characters.");
            return;
        }
        if (input.equalsIgnoreCase("cancel")) {
            prompts.remove(draft.player, prompt);
            show(draft);
            return;
        }
        try {
            String text = input.equalsIgnoreCase("<blank>") ? "" : input.startsWith("\\") ? input.substring(1) : input;
            prompt.accept.accept(text);
            prompts.remove(draft.player, prompt);
            show(draft);
        } catch (IllegalArgumentException failure) {
            coordinator.status(draft.player, "config-error", failure.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        requireMainThread();
        discard(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void kick(PlayerKickEvent event) {
        requireMainThread();
        discard(event.getPlayer());
    }

    void invalidate() {
        requireMainThread();
        List<Draft> previous = new ArrayList<Draft>(drafts.values());
        for (Draft draft : previous) {
            discard(draft.player);
        }
        for (Draft draft : previous) {
            try {
                closeOwned(draft.player);
            } catch (RuntimeException failure) {
                logger.log(Level.WARNING, "Could not close an invalidated EnchantXP settings menu", failure);
            }
        }
    }

    private void discard(Player player) {
        Draft draft = drafts.remove(player.getUniqueId());
        prompts.remove(player);
        if (draft != null && draft.ticket != null) {
            draft.ticket.abort();
        }
        if (draft != null && draft.expiry != null) {
            draft.expiry.cancel();
        }
    }

    private void closeOwned(Player player) {
        if (player.isOnline() && holder(player.getOpenInventory()) != null) {
            player.closeInventory();
        }
    }

    private boolean saving(Draft draft) {
        return draft.ticket != null && !draft.ticket.aborted.get();
    }

    void prompt(Draft draft, String question, Consumer<String> accept) {
        if (!permitted(draft)) {
            return;
        }
        draft.transition = true;
        try {
            draft.player.closeInventory();
        } finally {
            draft.transition = false;
        }
        if (permitted(draft)) {
            prompts.put(draft.player, new Prompt(draft, accept, draft.player.getOpenInventory()));
            coordinator.status(draft.player, "config-prompt", question);
        }
    }

    void navigate(Draft draft, String route) {
        draft.route = route;
        draft.page = 0;
        show(draft);
    }

    void show(Draft draft) {
        if (!permitted(draft)) {
            return;
        }
        List<Entry> entries = pages.entries(draft);
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        draft.page = Math.max(0, Math.min(draft.page, pages - 1));
        EditorHolder holder = new EditorHolder(this, draft);
        Inventory inventory = Bukkit.createInventory(holder, 54, color("&5Settings &8| &f" + title(draft.route)));
        holder.inventory = inventory;
        for (int slot = 0; slot < 54; slot++) {
            boolean border = slot < 9 || slot >= 45 || slot % 9 == 0 || slot % 9 == 8;
            inventory.setItem(slot, icon(Material.STAINED_GLASS_PANE, border ? 2 : 15, " ", Collections.emptyList()));
        }
        for (int offset = 0; offset < PAGE_SIZE && draft.page * PAGE_SIZE + offset < entries.size(); offset++) {
            int slot = 10 + (offset / 7) * 9 + offset % 7;
            put(holder, slot, entries.get(draft.page * PAGE_SIZE + offset));
        }
        put(holder, BACK, new Entry(Material.ARROW, "Back", Arrays.asList("Return to the previous category."),
                () -> navigate(draft, parent(draft.route)), null));
        if (draft.page > 0) {
            put(holder, PREVIOUS, new Entry(Material.ARROW, "Previous page", Collections.emptyList(),
                    () -> { draft.page--; show(draft); }, null));
        }
        if (draft.page + 1 < pages) {
            put(holder, NEXT, new Entry(Material.ARROW, "Next page", Collections.emptyList(),
                    () -> { draft.page++; show(draft); }, null));
        }
        put(holder, DISCARD, new Entry(Material.BARRIER, "Discard", Arrays.asList("Close without saving your draft."), () -> { }, null));
        put(holder, APPLY, new Entry(Material.EMERALD, "Apply and save", Arrays.asList(
                "Validate the whole draft, then save it.", "An exact backup is kept beside config.yml.",
                "Saving may reformat YAML and remove comments.", "Nothing is written until you choose this."),
                () -> coordinator.save(draft), null));
        inventory.setItem(4, icon(Material.BOOK_AND_QUILL, 0, "Your settings draft", Arrays.asList(
                "Page " + (draft.page + 1) + " of " + pages, "Changes stay private until Apply.",
                "Escape discards. Chat prompts keep your draft.", "Drafts expire after 15 minutes.")));
        draft.transition = true;
        draft.view = holder;
        InventoryView opened = null;
        try {
            opened = draft.player.openInventory(inventory);
        } finally {
            draft.transition = false;
            // Opening fires synchronous listeners that can cancel, invalidate or replace this draft.
            if (!permitted(draft) || draft.view != holder || opened == null ||
                    opened.getTopInventory().getHolder() != holder ||
                    draft.player.getOpenInventory().getTopInventory().getHolder() != holder) {
                if (drafts.get(draft.owner) == draft) {
                    discard(draft.player);
                }
                if (draft.player.getOpenInventory().getTopInventory().getHolder() == holder) {
                    draft.player.closeInventory();
                }
            }
        }
    }

    private void put(EditorHolder holder, int slot, Entry entry) {
        holder.entries.put(slot, entry);
        holder.inventory.setItem(slot, icon(entry.material, 0, entry.name, entry.lore));
    }

    private ItemStack icon(Material material, int data, String name, List<String> lines) {
        ItemStack item = new ItemStack(material, 1, (short) data);
        List<String> lore = new ArrayList<String>();
        for (String line : lines) {
            String remaining = line;
            while (remaining.length() > 48) {
                int split = remaining.lastIndexOf(' ', 48);
                if (split < 1) {
                    split = 48;
                }
                lore.add(color("&7" + remaining.substring(0, split)));
                remaining = remaining.substring(split).trim();
            }
            lore.add(color("&7" + remaining));
        }
        MenuText.decorate(item, color("&d" + name), lore);
        return item;
    }

    private EditorHolder holder(InventoryView view) {
        InventoryHolder holder = view.getTopInventory().getHolder();
        return holder instanceof EditorHolder && ((EditorHolder) holder).editor == this ? (EditorHolder) holder : null;
    }

    private static String title(String route) {
        if (route.equals("home")) {
            return "EnchantXP";
        }
        if (route.contains(":")) {
            return ConfigFields.label(route.substring(0, route.indexOf(':')));
        }
        return ConfigFields.label(route);
    }

    private static String parent(String route) {
        if (route.startsWith("enchant:")) { return "enchants"; }
        if (route.startsWith("levels:")) { return "enchant:" + route.substring(7); }
        if (route.startsWith("level:")) { return "levels:" + route.split(":")[1]; }
        if (route.startsWith("item:") || route.startsWith("pick:")) { return "items"; }
        if (route.startsWith("icon:")) { return "icons"; }
        if (route.startsWith("sound:")) { return "sounds"; }
        if (route.equals("titles") || route.equals("icons")) { return "ui"; }
        if (route.startsWith("lines:ui.icons.")) { return "icon:" + route.split("\\.")[2]; }
        if (route.startsWith("lines:")) { return "ui"; }
        return "home";
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private void defer(Runnable action) {
        scheduler.runTask(plugin, action);
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("EnchantXP settings menus require the server thread");
        }
    }

    static final class Draft {
        final UUID owner;
        final Player player;
        final YamlConfiguration document;
        final byte[] hash;
        final long generation;
        final long created = System.nanoTime();
        String route = "home";
        int page;
        boolean pending;
        boolean transition;
        EditorHolder view;
        ReloadCommand.SaveTicket ticket;
        BukkitTask expiry;

        Draft(Player player, YamlConfiguration document, byte[] hash, long generation) {
            this.owner = player.getUniqueId();
            this.player = player;
            this.document = document;
            this.hash = hash.clone();
            this.generation = generation;
        }
    }

    static final class EditorHolder implements InventoryHolder {
        final ConfigEditor editor;
        final Draft draft;
        final Map<Integer, Entry> entries = new HashMap<Integer, Entry>();
        private Inventory inventory;

        EditorHolder(ConfigEditor editor, Draft draft) {
            this.editor = editor;
            this.draft = draft;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    static final class Entry {
        final Material material;
        final String name;
        final List<String> lore;
        final Runnable action;
        final Runnable reset;

        Entry(Material material, String name, List<String> lore, Runnable action, Runnable reset) {
            this.material = material;
            this.name = name;
            this.lore = lore;
            this.action = action;
            this.reset = reset;
        }
    }

    private static final class Prompt {
        final Draft draft;
        final Consumer<String> accept;
        final InventoryView expectedView;

        Prompt(Draft draft, Consumer<String> accept, InventoryView expectedView) {
            this.draft = draft;
            this.accept = accept;
            this.expectedView = expectedView;
        }
    }
}
