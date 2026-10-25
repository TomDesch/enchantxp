package io.github.stealingdapenta.enchantxp.administration;

import io.github.stealingdapenta.enchantxp.configuration.Settings;
import io.github.stealingdapenta.enchantxp.menu.TableMenus;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;

/** One coordinator serializes reload, draft reads and staged saves. YAML stays on the server thread. */
public final class ReloadCommand implements CommandExecutor, TabCompleter {
    static final String PERMISSION = "enchantxp.reload";
    private final Plugin plugin;
    private final TableMenus menus;
    private final File file;
    private final ConfigFileStore store;
    private final BukkitScheduler scheduler;
    private final Logger logger;
    private final ConfigEditor editor;
    private final ExecutorService cleanupExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(1), action -> {
        Thread thread = new Thread(action, "EnchantXP-config-cleanup");
        thread.setDaemon(true);
        return thread;
    });
    private Settings settings;
    private volatile boolean active = true;
    private final AtomicReference<Object> busy = new AtomicReference<Object>();
    private long operation;
    private long generation;

    public ReloadCommand(Plugin plugin, TableMenus menus, Settings settings, File file) {
        this(plugin, menus, settings, file, new ConfigFileStore(file.toPath()));
    }

    ReloadCommand(Plugin plugin, TableMenus menus, Settings settings, File file, ConfigFileStore store) {
        this.plugin = plugin;
        this.menus = menus;
        this.settings = settings;
        this.file = file;
        this.store = store;
        this.scheduler = plugin.getServer().getScheduler();
        this.logger = plugin.getLogger();
        this.editor = new ConfigEditor(plugin, this);
    }

    ConfigEditor editor() {
        return editor;
    }

    public Listener editorListener() {
        return editor;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        requireMainThread();
        if (args.length != 1 || (!args[0].equalsIgnoreCase("config") && !args[0].equalsIgnoreCase("reload"))) {
            help(sender);
        } else if (args[0].equalsIgnoreCase("config")) {
            if (!sender.hasPermission(ConfigEditor.PERMISSION)) {
                send(sender, "config-denied");
            } else if (!(sender instanceof Player)) {
                send(sender, "config-player-only");
            } else if (!active) {
                send(sender, "reload-stopped");
            } else {
                open((Player) sender);
            }
        } else if (!sender.hasPermission(PERMISSION)) {
            send(sender, "reload-denied");
        } else if (!active) {
            send(sender, "reload-stopped");
        } else if (busy.get() != null) {
            send(sender, "reload-busy");
        } else {
            Object owner = new Object();
            busy.set(owner);
            long request = ++operation;
            executeAsync(sender, () -> readReload(sender, request, owner), owner);
        }
        return true;
    }

    private void help(CommandSender sender) {
        List<String> commands = new ArrayList<>();
        commands.add("/enchantxp help");
        if (sender instanceof Player && sender.hasPermission(ConfigEditor.PERMISSION)) {
            commands.add("/enchantxp config (in-game)");
        }
        if (sender.hasPermission(PERMISSION)) {
            commands.add("/enchantxp reload (console or in-game)");
        }
        settings.text().send(sender, "reload-usage", Collections.singletonMap("commands", String.join(", ", commands)));
    }

    private void readReload(CommandSender sender, long request, Object owner) {
        if (!active) {
            release(owner);
            return;
        }
        String contents = null;
        Exception problem = null;
        try {
            contents = ConfigFileStore.decode(store.read());
        } catch (Exception failure) {
            problem = failure;
        }
        final String candidate = contents;
        final Exception readFailure = problem;
        dispatch(() -> completeReload(sender, request, candidate, readFailure, owner), "reload", owner);
    }

    private void completeReload(CommandSender sender, long request, String contents, Exception problem, Object owner) {
        requireMainThread();
        if (!active || request != operation) {
            return;
        }
        // Duplicate scheduler deliveries cannot republish a completed operation.
        operation++;
        try {
            if (!sender.hasPermission(PERMISSION)) {
                send(sender, "reload-denied");
                return;
            }
            if (!connected(sender)) {
                logger.info("Discarded EnchantXP reload: player requester is no longer connected.");
                return;
            }
            if (problem != null) {
                failure(sender, problem);
                return;
            }
            Settings replacement = parse(contents);
            menus.replace(replacement, () -> active);
            if (active) {
                settings = replacement;
                generation++;
                editor.invalidate();
                logger.info("Reloaded " + file.getPath());
                send(sender, "reload-success");
            }
        } catch (InvalidConfigurationException | RuntimeException failure) {
            if (active) {
                failure(sender, failure);
            }
        } finally {
            release(owner);
        }
    }

    private void open(Player player) {
        if (editor.resume(player)) {
            return;
        }
        if (busy.get() != null) {
            send(player, "reload-busy");
            return;
        }
        Object owner = new Object();
        busy.set(owner);
        long baseGeneration = generation;
        InventoryView expectedView = player.getOpenInventory();
        executeAsync(player, () -> {
            byte[] bytes = null;
            Exception problem = null;
            try {
                bytes = store.read();
            } catch (Exception failure) {
                problem = failure;
            }
            final byte[] original = bytes;
            final Exception readFailure = problem;
            dispatch(() -> {
                try {
                    if (!active || !connected(player) || !player.hasPermission(ConfigEditor.PERMISSION)) {
                        return;
                    }
                    if (player.getOpenInventory() != expectedView) {
                        status(player, "config-error", "Settings were not opened because another inventory took over. Run /enchantxp config when ready.");
                        return;
                    }
                    if (readFailure != null) {
                        throw new IllegalArgumentException("Could not read config.yml", readFailure);
                    }
                    YamlConfiguration document = new YamlConfiguration();
                    document.loadFromString(ConfigFileStore.decode(original));
                    Settings.read(document);
                    editor.open(player, document, ConfigFileStore.hash(original), baseGeneration);
                } catch (Exception failure) {
                    status(player, "config-error", failure.getMessage());
                    logger.log(Level.WARNING, "Cannot open EnchantXP settings", failure);
                } finally {
                    release(owner);
                }
            }, "settings read", owner);
        }, owner);
    }

    void save(ConfigEditor.Draft draft) {
        requireMainThread();
        Player player = draft.player;
        if (!allowed(draft)) {
            status(player, "config-error", "This draft has expired or your access changed. Reopen /enchantxp config.");
            return;
        }
        if (busy.get() != null) {
            send(player, "reload-busy");
            return;
        }
        if (draft.generation != generation) {
            status(player, "config-error", "Settings changed. Discard this draft and reopen /enchantxp config.");
            return;
        }
        final String contents = draft.document.saveToString();
        final Settings replacement;
        try {
            replacement = parse(contents);
            menus.validateReplacement(replacement);
        } catch (Exception failure) {
            status(player, "config-error", failure.getMessage());
            return;
        }
        SaveTicket ticket = new SaveTicket();
        draft.ticket = ticket;
        busy.set(ticket);
        status(player, "config-saving", "");
        try {
            scheduler.runTaskAsynchronously(plugin, () -> stage(draft, ticket, contents, replacement));
        } catch (RuntimeException failure) {
            saveFailed(draft, ticket, failure, false);
        }
    }

    private void stage(ConfigEditor.Draft draft, SaveTicket ticket, String contents, Settings replacement) {
        ticket.workers.incrementAndGet();
        Path staged = null;
        try {
            if (!active || ticket.aborted.get()) {
                release(ticket);
                return;
            }
            staged = store.stage(contents, draft.hash);
            ticket.staged = staged;
            final Path ready = staged;
            if (!dispatch(() -> gate(draft, ticket, contents, replacement, ready), "save gate", ticket)) {
                ticket.abort();
                store.delete(staged);
            }
        } catch (Exception failure) {
            cleanup(staged);
            if (!dispatch(() -> saveFailed(draft, ticket, failure, false), "save failure", ticket)) {
                ticket.abort();
            }
        } finally {
            if (ticket.workers.decrementAndGet() == 0 && (!active || ticket.aborted.get())) {
                cleanupTicket(ticket);
            }
        }
    }

    private void gate(ConfigEditor.Draft draft, SaveTicket ticket, String contents, Settings replacement, Path staged) {
        requireMainThread();
        try {
            if (!allowed(draft) || ticket.aborted.get() || draft.generation != generation) {
                ticket.abort();
                throw new IllegalStateException("Save cancelled: your draft, connection or access changed.");
            }
            menus.validateReplacement(replacement);
            scheduler.runTaskAsynchronously(plugin, () -> commit(draft, ticket, contents, replacement, staged));
        } catch (RuntimeException failure) {
            ticket.abort();
            requestCleanup(ticket);
            saveFailed(draft, ticket, failure, false);
        }
    }

    private void commit(ConfigEditor.Draft draft, SaveTicket ticket, String contents, Settings replacement, Path staged) {
        ticket.workers.incrementAndGet();
        boolean saved = false;
        try {
            if (!active || ticket.aborted.get()) {
                return;
            }
            Path backup = store.commit(staged, draft.hash, contents, () -> active && ticket.beginCommit());
            saved = true;
            logger.info("Saved " + file.getPath() + "; original bytes backed up to " + backup.getFileName());
            if (!dispatch(() -> publish(draft, ticket, replacement), "saved configuration publication", ticket)) {
                ticket.abort();
                logger.severe("Saved, but not applied. Run /enchantxp reload when EnchantXP is enabled.");
            }
        } catch (Exception failure) {
            boolean diskSaved = saved || failure instanceof ConfigFileStore.SavedException;
            if (!dispatch(() -> saveFailed(draft, ticket, failure, diskSaved), "save failure", ticket)) {
                ticket.abort();
                logger.log(Level.SEVERE, diskSaved ? "Saved, but not applied. Run /enchantxp reload." :
                        "Cannot save EnchantXP settings", failure);
            }
        } finally {
            cleanup(staged);
            ticket.cleaned = true;
            ticket.cleanupDone.countDown();
            ticket.workers.decrementAndGet();
            if (!active || ticket.aborted.get()) {
                release(ticket);
            }
        }
    }

    private void publish(ConfigEditor.Draft draft, SaveTicket ticket, Settings replacement) {
        requireMainThread();
        boolean applied = false;
        try {
            if (!allowed(draft) || ticket.aborted.get()) {
                throw new IllegalStateException("Requester disconnected, cancelled or lost access after disk save");
            }
            menus.replace(replacement, () -> active);
            applied = true;
            settings = replacement;
            generation++;
            editor.invalidate();
            logger.info("Applied saved EnchantXP settings from " + file.getPath());
            if (active && connected(draft.player)) {
                send(draft.player, "config-saved");
            }
        } catch (RuntimeException failure) {
            if (applied) {
                logger.log(Level.SEVERE, "Settings saved and applied, but post-apply feedback failed", failure);
            } else {
                saveFailed(draft, ticket, failure, true);
            }
        } finally {
            if (ticket.staged == null || ticket.cleaned) {
                release(ticket);
                if (ticket.staged == null) {
                    ticket.cleanupDone.countDown();
                }
            }
        }
    }

    private void saveFailed(ConfigEditor.Draft draft, SaveTicket ticket, Exception failure, boolean saved) {
        requireMainThread();
        ticket.abort();
        if (ticket.staged == null || ticket.cleaned) {
            release(ticket);
        }
        logger.log(Level.WARNING, saved ? "Saved, but not applied. Run /enchantxp reload." : "Cannot save EnchantXP settings", failure);
        if (active && connected(draft.player)) {
            status(draft.player, saved ? "config-saved-not-applied" : "config-error", failure.getMessage());
        }
    }

    private void cleanup(Path staged) {
        try {
            store.delete(staged);
        } catch (Exception failure) {
            logger.log(Level.WARNING, "Cannot remove staged EnchantXP config " + staged, failure);
        }
    }

    private void requestCleanup(SaveTicket ticket) {
        if (!cleanupExecutor.isShutdown() && ticket.cleanupQueued.compareAndSet(false, true)) {
            try {
                cleanupExecutor.execute(() -> {
                    if (ticket.workers.get() == 0) {
                        cleanupTicket(ticket);
                    }
                });
            } catch (RuntimeException failure) {
                logger.log(Level.SEVERE, "Could not schedule cleanup of staged EnchantXP config " + ticket.staged, failure);
            }
        }
    }

    private void cleanupTicket(SaveTicket ticket) {
        cleanup(ticket.staged);
        ticket.cleaned = true;
        ticket.cleanupDone.countDown();
        release(ticket);
    }

    private Settings parse(String contents) throws InvalidConfigurationException {
        if (contents.getBytes(StandardCharsets.UTF_8).length > ConfigFileStore.MAX_BYTES) {
            throw new IllegalArgumentException("Draft exceeds 1 MiB. Shorten it before applying.");
        }
        YamlConfiguration candidate = new YamlConfiguration();
        candidate.loadFromString(contents);
        return Settings.read(candidate);
    }

    private boolean allowed(ConfigEditor.Draft draft) {
        return active && editor.current(draft) && connected(draft.player) &&
                draft.player.hasPermission(ConfigEditor.PERMISSION);
    }

    private boolean connected(CommandSender sender) {
        if (!(sender instanceof Player)) {
            return true;
        }
        Player player = (Player) sender;
        return player.isOnline() && plugin.getServer().getPlayer(player.getUniqueId()) == player;
    }

    private void executeAsync(CommandSender sender, Runnable action, Object owner) {
        try {
            scheduler.runTaskAsynchronously(plugin, action);
        } catch (RuntimeException failure) {
            release(owner);
            failure(sender, failure);
        }
    }

    private boolean dispatch(Runnable action, String label, Object owner) {
        if (!active) {
            release(owner);
            return false;
        }
        try {
            scheduler.runTask(plugin, action);
            return true;
        } catch (RuntimeException failure) {
            release(owner);
            logger.log(Level.SEVERE, "Cannot schedule EnchantXP " + label, failure);
            return false;
        }
    }

    private void failure(CommandSender sender, Exception failure) {
        logger.warning("Cannot reload " + file.getPath() + ": " + failure.getMessage());
        settings.text().send(sender, "reload-failure", Collections.singletonMap("path", file.getPath()));
    }

    private void release(Object owner) {
        busy.compareAndSet(owner, null);
    }

    void status(CommandSender sender, String key, String detail) {
        settings.text().send(sender, key, Collections.singletonMap("detail", detail == null ? "Check the server log." : detail));
    }

    private void send(CommandSender sender, String key) {
        settings.text().send(sender, key, Collections.emptyMap());
    }

    public void disable() {
        requireMainThread();
        active = false;
        generation++;
        operation++;
        editor.invalidate();
        Object owner = busy.get();
        if (owner instanceof SaveTicket) {
            SaveTicket ticket = (SaveTicket) owner;
            ticket.abort();
            requestCleanup(ticket);
        }
        cleanupExecutor.shutdown();
        // An in-progress worker retains ownership until it actually finishes.
    }

    boolean awaitCleanup(long timeout, TimeUnit unit) throws InterruptedException {
        return cleanupExecutor.awaitTermination(timeout, unit);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<String>();
        if (active && args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            if ("help".startsWith(prefix)) {
                result.add("help");
            }
            if (sender instanceof Player && sender.hasPermission(ConfigEditor.PERMISSION) && "config".startsWith(prefix)) {
                result.add("config");
            }
            if (sender.hasPermission(PERMISSION) && "reload".startsWith(prefix)) {
                result.add("reload");
            }
        }
        return result;
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("EnchantXP configuration requires the server thread");
        }
    }

    static final class SaveTicket {
        final AtomicBoolean aborted = new AtomicBoolean();
        final AtomicInteger workers = new AtomicInteger();
        final AtomicBoolean cleanupQueued = new AtomicBoolean();
        final CountDownLatch cleanupDone = new CountDownLatch(1);
        volatile Path staged;
        volatile boolean cleaned;
        private final AtomicBoolean started = new AtomicBoolean();

        void abort() {
            aborted.set(true);
        }

        boolean beginCommit() {
            return !aborted.get() && started.compareAndSet(false, true) && !aborted.get();
        }
    }
}
