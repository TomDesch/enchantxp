package io.github.stealingdapenta.enchantxp.administration;

import io.github.stealingdapenta.enchantxp.configuration.Settings;
import io.github.stealingdapenta.enchantxp.configuration.values.ConfigValues;
import io.github.stealingdapenta.enchantxp.enchanting.BookshelfPricing;
import io.github.stealingdapenta.enchantxp.enchanting.Prices;
import io.github.stealingdapenta.enchantxp.enchanting.Enchantments;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import io.github.stealingdapenta.enchantxp.enchanting.Experience;
import io.github.stealingdapenta.enchantxp.menu.ServerFixture;
import io.github.stealingdapenta.enchantxp.testsupport.MemoryItemMeta;
import static io.github.stealingdapenta.enchantxp.testsupport.YamlDocuments.yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConfigEditorTest {
    private static final String ORIGINAL = "# My comments stay until Apply.\n" + ServerFixture.LEGACY_PRICING +
            "base-xp: 25\nmax-cost: 1000000\n";
    private ServerFixture fixture;
    private Path directory;
    private Path file;
    private ConfigFileStore store;
    private ReloadCommand coordinator;
    private ConfigEditor editor;
    private Command command;
    private final List<Runnable> expiry = new ArrayList<Runnable>();

    @BeforeEach
    void setUp() throws Exception {
        fixture = new ServerFixture(ORIGINAL);
        directory = Files.createDirectory(Paths.get("target", "editor-test-" + UUID.randomUUID()));
        file = directory.resolve("config.yml");
        Files.write(file, ORIGINAL.getBytes(StandardCharsets.UTF_8));
        store = spy(new ConfigFileStore(file));
        coordinator = new ReloadCommand(fixture.plugin, fixture.menus, Settings.read(yaml(ORIGINAL)), file.toFile(), store);
        editor = coordinator.editor();
        command = mock(Command.class);
        when(fixture.player.hasPermission(ConfigEditor.PERMISSION)).thenReturn(true);
        when(fixture.player.hasPermission(ReloadCommand.PERMISSION)).thenReturn(true);
        when(fixture.scheduler.runTaskLater(eq(fixture.plugin), any(Runnable.class), anyLong())).thenAnswer(call -> {
            expiry.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        when(fixture.player.openInventory(any(Inventory.class))).thenAnswer(call -> {
            editor.close(new InventoryCloseEvent(fixture.view));
            fixture.menus.close(new InventoryCloseEvent(fixture.view));
            fixture.view = fixture.new View(call.getArgument(0));
            return fixture.view;
        });
        doAnswer(call -> {
            editor.close(new InventoryCloseEvent(fixture.view));
            fixture.menus.close(new InventoryCloseEvent(fixture.view));
            Inventory external = mock(Inventory.class);
            when(external.getSize()).thenReturn(9);
            fixture.view = fixture.new View(external);
            return null;
        }).when(fixture.player).closeInventory();
    }

    @AfterEach
    void cleanUp() throws Exception {
        coordinator.disable();
        assertTrue(coordinator.awaitCleanup());
        try (Stream<Path> files = Files.list(directory)) {
            for (Path path : files.collect(Collectors.toList())) {
                Files.delete(path);
            }
        }
        Files.delete(directory);
    }

    @Test
    void cancelledEditorOpenDiscardsOnlyItsDraftAndLeavesForeignInventoryAlone() {
        InventoryView foreign = fixture.view;
        List<ConfigEditor.Draft> attempted = new ArrayList<ConfigEditor.Draft>();
        when(fixture.player.openInventory(any(Inventory.class))).thenAnswer(call -> {
            Inventory inventory = call.getArgument(0);
            attempted.add(((ConfigEditor.EditorHolder) inventory.getHolder()).draft);
            return null;
        });
        request("config");
        worker();
        fixture.tick();
        assertFalse(editor.current(attempted.get(0)));
        assertFalse(editor.resume(fixture.player));
        assertSame(foreign, fixture.view);
        verify(fixture.player, never()).closeInventory();
        verify(fixture.player, times(1)).openInventory(any(Inventory.class));
    }

    @Test
    void reentrantEditorOpenKeepsNewerDraftAndDoesNotCloseItsView() {
        List<ConfigEditor.Draft> attempted = new ArrayList<ConfigEditor.Draft>();
        when(fixture.player.openInventory(any(Inventory.class))).thenAnswer(call -> {
            Inventory inventory = call.getArgument(0);
            ConfigEditor.Draft draft = ((ConfigEditor.EditorHolder) inventory.getHolder()).draft;
            attempted.add(draft);
            if (attempted.size() == 1) {
                editor.open(fixture.player, yaml(ORIGINAL), new byte[32], draft.generation);
                return null;
            }
            fixture.view = fixture.new View(inventory);
            return fixture.view;
        });
        request("config");
        worker();
        fixture.tick();
        assertFalse(editor.current(attempted.get(0)));
        assertTrue(editor.current(attempted.get(1)));
        assertSame(attempted.get(1), draft());
        verify(fixture.player, never()).closeInventory();
    }

    @Test
    void reentrantResumeKeepsItsNewerViewWhenOuterOpenIsCancelledOrThrows() {
        for (boolean throwsAfterResume : new boolean[]{false, true}) {
            List<ConfigEditor.Draft> attempted = new ArrayList<ConfigEditor.Draft>();
            when(fixture.player.openInventory(any(Inventory.class))).thenAnswer(call -> {
                Inventory inventory = call.getArgument(0);
                ConfigEditor.Draft draft = ((ConfigEditor.EditorHolder) inventory.getHolder()).draft;
                attempted.add(draft);
                if (attempted.size() == 1) {
                    assertTrue(editor.resume(fixture.player));
                    if (throwsAfterResume) {
                        throw new IllegalStateException("Outer open failed after resuming");
                    }
                    return null;
                }
                fixture.view = fixture.new View(inventory);
                return fixture.view;
            });
            request("config");
            worker();
            fixture.tick();
            assertEquals(2, attempted.size());
            assertSame(attempted.get(0), attempted.get(1));
            assertTrue(editor.current(attempted.get(1)));
            assertSame(draft().view, fixture.view.getTopInventory().getHolder());
            fixture.player.closeInventory();
        }
    }

    @Test
    void reentrantInvalidationDuringEditorOpenCannotPublishAnInertEditor() {
        List<ConfigEditor.Draft> attempted = new ArrayList<ConfigEditor.Draft>();
        when(fixture.player.openInventory(any(Inventory.class))).thenAnswer(call -> {
            Inventory inventory = call.getArgument(0);
            attempted.add(((ConfigEditor.EditorHolder) inventory.getHolder()).draft);
            editor.invalidate();
            fixture.view = fixture.new View(inventory);
            return fixture.view;
        });
        request("config");
        worker();
        fixture.tick();
        assertFalse(editor.current(attempted.get(0)));
        assertNull(fixture.view.getTopInventory().getHolder());
        verify(fixture.player).closeInventory();
    }

    @Test
    void reentrantForeignOpenDiscardsDraftWithoutClosingForeignView() {
        InventoryView foreign = foreignView(fixture.player);
        List<ConfigEditor.Draft> attempted = new ArrayList<ConfigEditor.Draft>();
        when(fixture.player.openInventory(any(Inventory.class))).thenAnswer(call -> {
            Inventory inventory = call.getArgument(0);
            attempted.add(((ConfigEditor.EditorHolder) inventory.getHolder()).draft);
            fixture.view = foreign;
            return fixture.new View(inventory);
        });
        request("config");
        worker();
        fixture.tick();
        assertFalse(editor.current(attempted.get(0)));
        assertSame(foreign, fixture.view);
        verify(fixture.player, never()).closeInventory();
    }

    @Test
    void commandCompletesEverySupportedSubcommandByPermissionAndNeverFallsBackToPlayers() {
        assertEquals(Arrays.asList("help", "config", "reload"), coordinator.onTabComplete(fixture.player, command, "enchantxp", new String[]{""}));
        assertEquals(Collections.singletonList("config"), coordinator.onTabComplete(fixture.player, command, "enchantxp", new String[]{"CoN"}));
        assertEquals(Collections.singletonList("reload"), coordinator.onTabComplete(fixture.player, command, "enchantxp", new String[]{"RE"}));
        for (String[] args : new String[][]{{"other"}, {"config", ""}, {"reload", ""}, {}}) {
            assertTrue(coordinator.onTabComplete(fixture.player, command, "enchantxp", args).isEmpty());
        }
        when(fixture.player.hasPermission(ConfigEditor.PERMISSION)).thenReturn(false);
        request("config");
        verify(fixture.player).sendMessage(contains("need enchantxp.config"));
        assertTrue(fixture.asyncTasks.isEmpty());
        assertEquals(Arrays.asList("help", "reload"), coordinator.onTabComplete(fixture.player, command, "enchantxp", new String[]{""}));
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(ConfigEditor.PERMISSION)).thenReturn(true);
        coordinator.onCommand(console, command, "enchantxp", new String[]{"config"});
        verify(console).sendMessage(contains("in-game"));
    }

    @Test
    void editingAndSavingReopensPersistedSparseDocumentAndKeepsByteExactBackup() throws Exception {
        open();
        ConfigEditor.Draft original = draft();
        clickNamed("General and prices");
        assertEquals("general", draft().route);
        clickNamed("Starting price");
        assertNull(fixture.view.getTopInventory().getHolder());
        input("40");
        assertSame(original, draft());
        assertEquals(40, draft().document.getInt("base-xp"));
        assertEquals(ORIGINAL, contents());
        click(ConfigEditor.APPLY, ClickType.LEFT);
        finishSave();
        assertNull(fixture.view.getTopInventory().getHolder());
        verify(fixture.player).sendMessage(contains("saved and applied"));
        assertEquals(40, yaml(contents()).getInt("base-xp"));
        assertFalse(yaml(contents()).contains("ui", true));
        assertFalse(yaml(contents()).contains("enchantments", true));
        List<Path> backups = children(".backup-");
        assertEquals(1, backups.size());
        assertArrayEquals(ORIGINAL.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(backups.get(0)));
        open();
        assertEquals(40, draft().document.getInt("base-xp"));
    }

    @Test
    void discardAndEscapeNeverWriteOrPublishAndFenceQueuedApply() throws Exception {
        for (boolean escape : new boolean[]{false, true}) {
            open();
            ConfigEditor.Draft previous = draft();
            previous.document.set("base-xp", 123);
            if (escape) {
                fixture.player.closeInventory();
            } else {
                rawClick(ConfigEditor.APPLY, ClickType.LEFT);
                rawClick(ConfigEditor.DISCARD, ClickType.LEFT);
                fixture.tick();
            }
            assertFalse(editor.current(previous));
            assertEquals(ORIGINAL, contents());
            assertTrue(fixture.asyncTasks.isEmpty());
        }
        fixture.confirmPage();
        fixture.click(fixture.confirmSlot(), ClickType.LEFT);
        fixture.tick();
        assertEquals(900, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void invalidCandidateKeepsEditableDraftAndNoFilesUntilCoherentApply() throws Exception {
        open();
        ConfigEditor.Draft previous = draft();
        previous.document.set("base-xp", 900);
        previous.document.set("max-cost", 100);
        click(ConfigEditor.APPLY, ClickType.LEFT);
        verify(fixture.player).sendMessage(contains("must not exceed"));
        assertSame(previous, draft());
        assertTrue(fixture.asyncTasks.isEmpty());
        assertEquals(ORIGINAL, contents());
        assertTrue(children(".backup-").isEmpty());
        clickNamed("General and prices");
        clickNamed("Purchase limit");
        input("1000");
        click(ConfigEditor.APPLY, ClickType.LEFT);
        finishSave();
        assertEquals(1000, yaml(contents()).getInt("max-cost"));
    }

    @Test
    void staleManualEditIsNeverOverwrittenAndBusyStateReleases() throws Exception {
        open();
        draft().document.set("base-xp", 40);
        String manual = ORIGINAL + "permissions:\n  enabled: false\n";
        Files.write(file, manual.getBytes(StandardCharsets.UTF_8));
        click(ConfigEditor.APPLY, ClickType.LEFT);
        worker();
        fixture.tick();
        verify(fixture.player).sendMessage(contains("changed on disk"));
        assertEquals(manual, contents());
        assertTrue(children(".backup-").isEmpty());
        request("reload");
        worker();
        fixture.tick();
        verify(fixture.player).sendMessage(contains("Configuration reloaded"));
    }

    @Test
    void successfulSameByteReloadInvalidatesOldDraftAndPendingInput() throws Exception {
        open();
        ConfigEditor.Draft previous = draft();
        clickNamed("General and prices");
        clickNamed("Starting price");
        request("reload");
        worker();
        fixture.tick();
        assertFalse(editor.current(previous));
        coordinator.save(previous);
        assertTrue(fixture.asyncTasks.isEmpty());
        AsyncPlayerChatEvent chat = input("75");
        assertFalse(chat.isCancelled());
        assertEquals(ORIGINAL, contents());
    }

    @Test
    void anotherOperatorsSaveInvalidatesAllDraftsAndSharedBusyRejectsReload() throws Exception {
        open();
        ConfigEditor.Draft first = draft();
        Player second = mock(Player.class);
        when(second.getUniqueId()).thenReturn(UUID.randomUUID());
        when(second.isOnline()).thenReturn(true);
        when(second.hasPermission(ConfigEditor.PERMISSION)).thenReturn(true);
        when(fixture.plugin.getServer().getPlayer(second.getUniqueId())).thenReturn(second);
        InventoryView secondView = mock(InventoryView.class);
        when(second.getOpenInventory()).thenReturn(secondView);
        when(second.openInventory(any(Inventory.class))).thenAnswer(call -> {
            when(secondView.getTopInventory()).thenReturn(call.getArgument(0));
            return secondView;
        });
        editor.open(second, yaml(ORIGINAL), ConfigFileStore.hash(Files.readAllBytes(file)), first.generation);
        ArgumentCaptor<Inventory> opened = ArgumentCaptor.forClass(Inventory.class);
        verify(second).openInventory(opened.capture());
        ConfigEditor.Draft secondDraft = ((ConfigEditor.EditorHolder) opened.getValue().getHolder()).draft;
        assertTrue(editor.current(secondDraft));
        first.document.set("base-xp", 45);
        coordinator.save(first);
        request("reload");
        verify(fixture.player).sendMessage(contains("already in progress"));
        assertEquals(1, fixture.asyncTasks.size());
        finishSave();
        assertFalse(editor.current(first));
        assertFalse(editor.current(secondDraft));
        coordinator.save(first);
        coordinator.save(secondDraft);
        assertTrue(fixture.asyncTasks.isEmpty());
        assertEquals(45, yaml(contents()).getInt("base-xp"));
    }

    @Test
    void reloadAlreadyInFlightPreventsSaveAndDraftCreation() throws Exception {
        open();
        ConfigEditor.Draft previous = draft();
        request("reload");
        coordinator.save(previous);
        assertEquals(1, fixture.asyncTasks.size());
        worker();
        fixture.tick();
        assertEquals(ORIGINAL, contents());
        assertTrue(children(".backup-").isEmpty());
    }

    @Test
    void stagedFileIsRecheckedAgainstManualChangesImmediatelyBeforeCommit() throws Exception {
        open();
        draft().document.set("base-xp", 60);
        click(ConfigEditor.APPLY, ClickType.LEFT);
        worker();
        fixture.tick();
        String manual = ORIGINAL + "# manually changed while staging\n";
        Files.write(file, manual.getBytes(StandardCharsets.UTF_8));
        worker();
        fixture.tick();
        assertEquals(manual, contents());
        verify(fixture.player).sendMessage(contains("changed on disk"));
        assertTrue(children(".stage-").isEmpty());
    }

    @Test
    void permissionRevocationAndDisconnectAtReadOrStagedGateDoNotWrite() throws Exception {
        for (int stage = 0; stage < 3; stage++) {
            when(fixture.player.hasPermission(ConfigEditor.PERMISSION)).thenReturn(true);
            when(fixture.player.isOnline()).thenReturn(true);
            open();
            ConfigEditor.Draft previous = draft();
            previous.document.set("base-xp", 55);
            coordinator.save(previous);
            if (stage > 0) {
                worker();
            }
            if (stage == 2) {
                when(fixture.player.isOnline()).thenReturn(false);
            } else {
                when(fixture.player.hasPermission(ConfigEditor.PERMISSION)).thenReturn(false);
            }
            if (stage == 0) {
                worker();
            }
            fixture.tick();
            while (!fixture.asyncTasks.isEmpty()) { worker(); }
            assertTrue(previous.ticket.cleanupDone.await(5, TimeUnit.SECONDS));
            assertEquals(ORIGINAL, contents());
            assertTrue(children(".stage-").isEmpty());
            when(fixture.player.hasPermission(ConfigEditor.PERMISSION)).thenReturn(true);
            when(fixture.player.isOnline()).thenReturn(true);
            fixture.player.closeInventory();
        }
    }

    @Test
    void quitKickAndDisableBeforeCommitAbortAlreadyStagedWork() throws Exception {
        open();
        ConfigEditor.Draft previous = draft();
        previous.document.set("base-xp", 55);
        coordinator.save(previous);
        worker();
        fixture.tick();
        editor.quit(new PlayerQuitEvent(fixture.player, ""));
        worker();
        assertEquals(ORIGINAL, contents());
        assertTrue(children(".stage-").isEmpty());
        open();
        previous = draft();
        editor.kick(new PlayerKickEvent(fixture.player, "", ""));
        assertFalse(editor.current(previous));
        open();
        draft().document.set("base-xp", 55);
        click(ConfigEditor.APPLY, ClickType.LEFT);
        worker();
        fixture.tick();
        coordinator.disable();
        worker();
        assertEquals(ORIGINAL, contents());
        assertTrue(children(".stage-").isEmpty());
    }

    @Test
    void disableDuringFinalByteCheckPreventsKnownLateCommit() throws Exception {
        open();
        draft().document.set("base-xp", 55);
        click(ConfigEditor.APPLY, ClickType.LEFT);
        worker();
        fixture.tick();
        doAnswer(call -> {
            coordinator.disable();
            return Files.readAllBytes(file);
        }).when(store).read();
        worker();
        assertEquals(ORIGINAL, contents());
        assertTrue(children(".stage-").isEmpty());
    }

    @Test
    void cancellationAfterDiskSaveReportsSavedButNotAppliedRatherThanSuccess() throws Exception {
        open();
        draft().document.set("base-xp", 55);
        click(ConfigEditor.APPLY, ClickType.LEFT);
        worker();
        fixture.tick();
        worker();
        editor.quit(new PlayerQuitEvent(fixture.player, ""));
        fixture.tick();
        assertEquals(55, yaml(contents()).getInt("base-xp"));
        verify(fixture.player).sendMessage(contains("Saved, but not applied"));
        verify(fixture.player, never()).sendMessage(contains("saved and applied"));
        assertEquals(1, children(".backup-").size());
    }

    @Test
    void publicationFailureAfterDiskSaveKeepsBackupAndGivesHonestRecovery() throws Exception {
        open();
        draft().document.set("base-xp", 55);
        click(ConfigEditor.APPLY, ClickType.LEFT);
        worker();
        fixture.tick();
        worker();
        Runnable publication = fixture.tasks.remove();
        fixture.menus.shutdown();
        publication.run();
        assertEquals(55, yaml(contents()).getInt("base-xp"));
        verify(fixture.player).sendMessage(contains("Saved, but not applied"));
        verify(fixture.player, never()).sendMessage(contains("saved and applied"));
    }

    @Test
    void fileIoAndUnsupportedAtomicMoveFailExplicitlyWithoutReplacingOriginal() throws Exception {
        open();
        ConfigEditor.Draft previous = draft();
        previous.document.set("base-xp", 55);
        doThrow(new IOException("Disk full")).when(store).stage(anyString(), any(byte[].class));
        coordinator.save(previous);
        worker();
        fixture.tick();
        verify(fixture.player).sendMessage(contains("Disk full"));
        assertEquals(ORIGINAL, contents());
        doCallRealMethod().when(store).stage(anyString(), any(byte[].class));
        doThrow(new AtomicMoveNotSupportedException("stage", "config", "Atomic replacement unavailable")).when(store).replace(any(Path.class));
        coordinator.save(previous);
        finishSave();
        assertEquals(ORIGINAL, contents());
        verify(fixture.player).sendMessage(contains("Atomic replacement unavailable"));
        assertTrue(children(".stage-").isEmpty());
        assertEquals(1, children(".backup-").size());
    }

    @Test
    void initialAndGateSchedulingFailuresReleaseBusyAndKeepDraftEditable() throws Exception {
        open();
        ConfigEditor.Draft previous = draft();
        doThrow(new IllegalStateException("Scheduler unavailable")).when(fixture.scheduler)
                .runTaskAsynchronously(eq(fixture.plugin), any(Runnable.class));
        coordinator.save(previous);
        assertTrue(previous.ticket.aborted.get());
        assertEquals(ORIGINAL, contents());
        when(fixture.scheduler.runTaskAsynchronously(eq(fixture.plugin), any(Runnable.class))).thenAnswer(call -> {
            fixture.asyncTasks.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
        coordinator.save(previous);
        worker();
        doThrow(new IllegalStateException("Scheduler unavailable")).when(fixture.scheduler)
                .runTaskAsynchronously(eq(fixture.plugin), any(Runnable.class));
        fixture.tick();
        assertTrue(previous.ticket.aborted.get());
        assertEquals(ORIGINAL, contents());
    }

    @Test
    void callbackSchedulingFailureReleasesBusyAndCleansStagedFile() throws Exception {
        open();
        coordinator.save(draft());
        doThrow(new IllegalStateException("Main scheduler unavailable")).when(fixture.scheduler)
                .runTask(eq(fixture.plugin), any(Runnable.class));
        worker();
        assertTrue(children(".stage-").isEmpty());
        assertTrue(draft().ticket.aborted.get());
        assertEquals(ORIGINAL, contents());
        // A new request can run even though the first callback could not be dispatched.
        request("reload");
        assertEquals(1, fixture.asyncTasks.size());
    }

    @Test
    void cancelledWorkerLateCallbackCannotReleaseNewerReloadsBusyOwnership() throws Exception {
        open();
        ConfigEditor.Draft previous = draft();
        previous.document.set("base-xp", 55);
        coordinator.save(previous);
        worker();
        fixture.tick();
        doAnswer(call -> {
            Path backup = (Path) call.callRealMethod();
            previous.ticket.abort();
            return backup;
        }).when(store).commit(any(Path.class), any(byte[].class), anyString(), any());
        worker();
        Runnable oldCompletion = fixture.tasks.remove();
        request("reload");
        oldCompletion.run();
        request("reload");
        assertEquals(1, fixture.asyncTasks.size());
        verify(fixture.player).sendMessage(contains("already in progress"));
        worker();
        fixture.tick();
    }

    @Test
    void failedReadbackIsSavedButNotAppliedAndNeverRollsBackOverExternalWrites() throws Exception {
        open();
        draft().document.set("base-xp", 55);
        doAnswer(call -> {
            call.callRealMethod();
            Files.write(file, (ORIGINAL + "# external edit after replacement\n").getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(store).replace(any(Path.class));
        click(ConfigEditor.APPLY, ClickType.LEFT);
        finishSave();
        assertTrue(contents().endsWith("# external edit after replacement\n"));
        verify(fixture.player).sendMessage(contains("Saved, but not applied"));
        assertEquals(1, children(".backup-").size());
    }

    @Test
    void registryConflictBeforeSaveOrAtStagedGateKeepsOldMenusAndFile() throws Exception {
        open();
        ConfigEditor.Draft previous = draft();
        previous.document.set("permissions.enabled", true);
        Permission foreign = new Permission("enchantxp.enchant.sharpness.*", PermissionDefault.TRUE);
        fixture.manager.addPermission(foreign);
        coordinator.save(previous);
        assertTrue(fixture.asyncTasks.isEmpty());
        assertSame(foreign, fixture.manager.getPermission(foreign.getName()));
        assertEquals(ORIGINAL, contents());
        fixture.manager.removePermission(foreign);
        coordinator.save(previous);
        worker();
        fixture.manager.addPermission(foreign);
        fixture.tick();
        assertTrue(previous.ticket.cleanupDone.await(5, TimeUnit.SECONDS));
        assertEquals(ORIGINAL, contents());
        assertTrue(children(".stage-").isEmpty());
    }

    @Test
    void registryConflictAfterCommitReportsSavedButNotAppliedWithoutClosingOldDraft() throws Exception {
        open();
        ConfigEditor.Draft previous = draft();
        previous.document.set("permissions.enabled", true);
        coordinator.save(previous);
        worker();
        fixture.tick();
        worker();
        fixture.manager.addPermission(new Permission("enchantxp.enchant.sharpness.*", PermissionDefault.TRUE));
        fixture.tick();
        assertTrue(yaml(contents()).getBoolean("permissions.enabled"));
        assertSame(previous, draft());
        verify(fixture.player).sendMessage(contains("Saved, but not applied"));
        assertFalse(fixture.manager.getPermissions().stream().anyMatch(permission -> permission.getName().equals("enchantxp.enchant.*")));
    }

    @Test
    void asyncChatAndFileWorkerOnlyUseCachedSchedulingAndImmutableData() throws Exception {
        open();
        clickNamed("General and prices");
        clickNamed("Starting price");
        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(true, fixture.player, "75", Collections.emptySet());
        clearInvocations(fixture.player, fixture.plugin);
        when(Bukkit.getServer().isPrimaryThread()).thenReturn(false);
        editor.chat(event);
        assertTrue(event.isCancelled());
        verifyNoInteractions(fixture.player, fixture.plugin);
        when(Bukkit.getServer().isPrimaryThread()).thenReturn(true);
        fixture.tick();
        coordinator.save(draft());
        clearInvocations(fixture.player, fixture.plugin);
        when(Bukkit.getServer().isPrimaryThread()).thenReturn(false);
        worker();
        verifyNoInteractions(fixture.player, fixture.plugin);
        when(Bukkit.getServer().isPrimaryThread()).thenReturn(true);
        fixture.tick();
        clearInvocations(fixture.player, fixture.plugin);
        when(Bukkit.getServer().isPrimaryThread()).thenReturn(false);
        worker();
        verifyNoInteractions(fixture.player, fixture.plugin);
        when(Bukkit.getServer().isPrimaryThread()).thenReturn(true);
        fixture.tick();
    }

    @Test
    void revokedPermissionBeforePublicationLeavesSavedFileWithRecovery() throws Exception {
        open();
        ConfigEditor.Draft previous = draft();
        previous.document.set("base-xp", 55);
        coordinator.save(previous);
        worker();
        fixture.tick();
        worker();
        when(fixture.player.hasPermission(ConfigEditor.PERMISSION)).thenReturn(false);
        fixture.tick();
        assertEquals(55, yaml(contents()).getInt("base-xp"));
        verify(fixture.player).sendMessage(contains("Saved, but not applied"));
        assertTrue(previous.ticket.aborted.get());
    }

    @Test
    void oversizeDraftFailsBeforeYamlParsingAndKeepsOriginalWithoutBackup() throws Exception {
        open();
        ConfigEditor.Draft previous = draft();
        previous.document.set("messages.context", String.join("", Collections.nCopies(ConfigFileStore.MAX_BYTES, "x")));
        coordinator.save(previous);
        assertTrue(fixture.asyncTasks.isEmpty());
        assertEquals(ORIGINAL, contents());
        verify(fixture.player).sendMessage(contains("exceeds 1 MiB"));
        assertTrue(children(".backup-").isEmpty());
        assertNull(previous.ticket);
    }

    @Test
    void inputIsPrivateBoundedAndRetainedAcrossIntentionalCloseAndCancel() throws Exception {
        open();
        ConfigEditor.Draft previous = draft();
        clickNamed("General and prices");
        clickNamed("Starting price");
        assertTrue(editor.current(previous));
        AsyncPlayerChatEvent oversized = input(String.join("", Collections.nCopies(257, "1")));
        assertTrue(oversized.isCancelled());
        assertTrue(editor.current(previous));
        input("not a number");
        verify(fixture.player, times(2)).sendMessage(contains("whole number"));
        input("cancel");
        assertSame(previous, draft());
        assertEquals(25, draft().document.getInt("base-xp"));
        clickNamed("Starting price");
        input("75");
        assertEquals(75, draft().document.getInt("base-xp"));
        assertEquals(ORIGINAL, contents());
    }

    @Test
    void chatPermissionRevocationAndExpiryInvalidatePromptWithoutLeakingIt() throws Exception {
        open();
        ConfigEditor.Draft previous = draft();
        clickNamed("General and prices");
        clickNamed("Starting price");
        when(fixture.player.hasPermission(ConfigEditor.PERMISSION)).thenReturn(false);
        assertTrue(input("75").isCancelled());
        assertFalse(editor.current(previous));
        when(fixture.player.hasPermission(ConfigEditor.PERMISSION)).thenReturn(true);
        open();
        previous = draft();
        expiry.get(expiry.size() - 1).run();
        assertFalse(editor.current(previous));
        assertFalse(input("hello").isCancelled());
    }

    @Test
    void allCategoriesAndPaginatedEnchantmentsMessagesAndIconsAreReachable() throws Exception {
        open();
        clickNamed("Enchantments");
        assertTrue(findSlot("Next page") >= 0);
        int count = 0;
        for (int slot : draft().view.entries.keySet()) {
            if (slot >= 10 && slot < 45) { count++; }
        }
        assertEquals(28, count);
        click(ConfigEditor.NEXT, ClickType.LEFT);
        assertEquals(1, draft().page);
        click(ConfigEditor.BACK, ClickType.LEFT);
        clickNamed("Chat messages");
        assertTrue(findSlot("Draft expired") >= 0);
        click(ConfigEditor.BACK, ClickType.LEFT);
        clickNamed("Menu appearance");
        clickNamed("Buttons and decorations");
        clickNamed("Confirm");
        assertTrue(findSlot("Item") >= 0);
        assertTrue(findSlot("Item data") >= 0);
        assertTrue(findSlot("Name") >= 0);
        assertTrue(findSlot("Lore") >= 0);
        click(ConfigEditor.BACK, ClickType.LEFT);
        click(ConfigEditor.BACK, ClickType.LEFT);
        click(ConfigEditor.BACK, ClickType.LEFT);
        clickNamed("Sounds");
        clickNamed("Click");
        assertTrue(findSlot("Sound") >= 0);
        assertTrue(findSlot("Volume") >= 0);
        assertTrue(findSlot("Pitch") >= 0);
    }

    @Test
    void sparseLevelAndItemOverridesCanBeAddedEditedRemovedWithoutExpandingDefaults() throws Exception {
        open();
        draft().route = "enchant:DAMAGE_ALL";
        editor.resume(fixture.player);
        clickNamed("Level prices");
        clickNamed("Add level price");
        input("2");
        clickNamed("Level 2 total XP");
        input("150");
        assertEquals(150, draft().document.getInt("enchantments.DAMAGE_ALL.levels.2"));
        assertEquals(1, draft().document.getConfigurationSection("enchantments.DAMAGE_ALL.levels").getKeys(false).size());
        click(findSlot("Level 2 total XP"), ClickType.RIGHT);
        assertFalse(draft().document.contains("enchantments.DAMAGE_ALL.levels.2", true));
        draft().route = "items";
        editor.resume(fixture.player);
        clickNamed("Add item type");
        input("not-a-material");
        verify(fixture.player, atLeastOnce()).sendMessage(contains("legacy item"));
        input("diamond_sword");
        clickNamed("Sharpness");
        input("3");
        assertEquals(3, draft().document.getInt("items.DIAMOND_SWORD.max-levels.DAMAGE_ALL"));
        click(findSlot("Sharpness"), ClickType.RIGHT);
        assertFalse(draft().document.contains("items.DIAMOND_SWORD.max-levels.DAMAGE_ALL", true));
        assertFalse(draft().document.contains("items.BOOK", true));
    }

    @Test
    void loreEditorSupportsBlankLinesDeletionAndExplicitEmptyWhileResetCopiesShippedLore() throws Exception {
        open();
        draft().route = "icon:fill";
        editor.resume(fixture.player);
        clickNamed("Lore");
        clickNamed("Add line");
        input("<blank>");
        assertEquals(Collections.singletonList(""), draft().document.getStringList("ui.icons.fill.lore"));
        clickNamed("Add line");
        input("&dHello");
        clickNamed("Line 2");
        input("\\cancel");
        assertEquals(Arrays.asList("", "cancel"), draft().document.getStringList("ui.icons.fill.lore"));
        click(findSlot("Line 1"), ClickType.RIGHT);
        assertEquals(Collections.singletonList("cancel"), draft().document.getStringList("ui.icons.fill.lore"));
        clickNamed("Clear all lines");
        assertTrue(draft().document.contains("ui.icons.fill.lore", true));
        assertTrue(draft().document.getStringList("ui.icons.fill.lore").isEmpty());
        click(ConfigEditor.BACK, ClickType.LEFT);
        click(findSlot("Lore"), ClickType.RIGHT);
        assertTrue(draft().document.contains("ui.icons.fill.lore", true));
        assertEquals(Collections.emptyList(), draft().document.getStringList("ui.icons.fill.lore"));
        assertFalse(draft().document.contains("ui.icons.confirm", true));
    }

    @Test
    void deletingADuplicateLoreLineRemovesItsPositionRatherThanTheFirstEqualValue() throws Exception {
        open();
        draft().document.set("ui.icons.fill.lore", Arrays.asList("same", "middle", "same"));
        draft().route = "lines:ui.icons.fill.lore";
        editor.resume(fixture.player);
        click(findSlot("Line 3"), ClickType.RIGHT);
        assertEquals(Arrays.asList("same", "middle"), draft().document.getStringList("ui.icons.fill.lore"));
    }

    @Test
    void togglesSoundNumbersAndLegacyIconFieldsEditThroughTheMenuAndSaveTogether() throws Exception {
        open();
        clickNamed("Sounds");
        clickNamed("Play sounds");
        assertFalse(draft().document.getBoolean("sounds.enabled"));
        clickNamed("Click");
        clickNamed("Sound");
        input("NOT_A_SOUND");
        input("<blank>");
        assertEquals("", draft().document.getString("sounds.click.sound"));
        clickNamed("Volume");
        input("NaN");
        input("2.5");
        clickNamed("Pitch");
        input("2.1");
        input("1.5");
        assertEquals(2.5, draft().document.getDouble("sounds.click.volume"));
        assertEquals(1.5, draft().document.getDouble("sounds.click.pitch"));
        click(ConfigEditor.BACK, ClickType.LEFT);
        click(ConfigEditor.BACK, ClickType.LEFT);
        clickNamed("Menu appearance");
        clickNamed("Buttons and decorations");
        clickNamed("Back");
        clickNamed("Item");
        input("stone");
        clickNamed("Item data");
        input("5");
        clickNamed("Name");
        input("&bGo back");
        assertEquals("STONE", draft().document.getString("ui.icons.back.material"));
        assertEquals(5, draft().document.getInt("ui.icons.back.data"));
        assertFalse(draft().document.contains("enchantments", true));
        click(ConfigEditor.APPLY, ClickType.LEFT);
        finishSave();
        Settings applied = Settings.read(yaml(contents()));
        ItemStack back = applied.text().icon("back", Collections.emptyMap());
        assertEquals(Material.STONE, back.getType());
        assertEquals(5, back.getDurability());
        assertEquals(ChatColor.AQUA + "Go back", back.getItemMeta().getDisplayName());
    }

    @Test
    void defaultIconResetRemovesTheWholeSectionRatherThanFreezingInheritedLore() throws Exception {
        open();
        draft().document.set("ui.icons.back.name", "Custom");
        draft().route = "icon:back";
        editor.resume(fixture.player);
        clickNamed("Use default icon");
        assertFalse(draft().document.contains("ui.icons.back", true));
        click(ConfigEditor.APPLY, ClickType.LEFT);
        finishSave();
        Settings applied = Settings.read(yaml(contents()));
        assertEquals(Collections.singletonList(ChatColor.GREEN + "\u00bb Return without paying."),
                applied.text().icon("back", Collections.emptyMap()).getItemMeta().getLore());
    }

    @Test
    void ownedEditorCancelsMovementAndRejectsForeignCancelledPendingAndStaleClicks() throws Exception {
        open();
        ConfigEditor.Draft previous = draft();
        InventoryClickEvent bottom = rawClick(60, ClickType.SHIFT_LEFT);
        assertTrue(bottom.isCancelled());
        InventoryDragEvent drag = new InventoryDragEvent(fixture.view, new ItemStack(Material.STONE),
                new ItemStack(Material.STONE), false, Collections.singletonMap(10, new ItemStack(Material.STONE)));
        editor.drag(drag);
        assertTrue(drag.isCancelled());
        InventoryClickEvent cancelled = new InventoryClickEvent(fixture.view, InventoryType.SlotType.CONTAINER, 10,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
        cancelled.setCancelled(true);
        editor.click(cancelled);
        assertTrue(fixture.tasks.isEmpty());
        rawClick(10, ClickType.LEFT);
        rawClick(11, ClickType.LEFT);
        assertEquals(1, fixture.tasks.size());
        fixture.tick();
        assertEquals("general", previous.route);
        editor.click(cancelled);
        assertTrue(fixture.tasks.isEmpty());
        when(fixture.player.hasPermission(ConfigEditor.PERMISSION)).thenReturn(false);
        rawClick(10, ClickType.LEFT);
        assertTrue(fixture.tasks.isEmpty());
    }

    @Test
    void filePayloadBoundsAndStrictUtf8RejectUnsafeReadsWithoutOpeningDraft() throws Exception {
        Files.write(file, new byte[ConfigFileStore.MAX_BYTES + 1]);
        request("config");
        worker();
        fixture.tick();
        assertNull(fixture.view.getTopInventory().getHolder());
        Files.write(file, new byte[]{(byte) 0xc0, (byte) 0xaf});
        request("config");
        worker();
        fixture.tick();
        assertNull(fixture.view.getTopInventory().getHolder());
        assertTrue(children(".backup-").isEmpty());
    }

    @Test
    void deferredEditorReadNeverReplacesAnInventoryOpenedAfterTheCommand() {
        request("config");
        InventoryView foreign = foreignView(fixture.player);
        fixture.view = foreign;
        worker();
        fixture.tick();
        assertSame(foreign, fixture.player.getOpenInventory());
        verify(fixture.player, never()).openInventory(any(Inventory.class));
        verify(fixture.player).sendMessage(contains("another inventory took over"));
    }

    @Test
    void deferredChatInputNeverEditsOrReopensOverAnInventoryOpenedBeforeItsCallback() throws Exception {
        open();
        ConfigEditor.Draft previous = draft();
        clickNamed("General and prices");
        clickNamed("Starting price");
        AsyncPlayerChatEvent reply = new AsyncPlayerChatEvent(true, fixture.player, "75", Collections.emptySet());
        editor.chat(reply);
        assertTrue(reply.isCancelled());
        InventoryView foreign = foreignView(fixture.player);
        fixture.view = foreign;
        clearInvocations(fixture.player);
        fixture.tick();
        assertSame(foreign, fixture.player.getOpenInventory());
        assertEquals(25, previous.document.getInt("base-xp"));
        assertFalse(editor.current(previous));
        verify(fixture.player, never()).openInventory(any(Inventory.class));
        verify(fixture.player, never()).closeInventory();
        assertEquals(ORIGINAL, contents());
    }

    @Test
    void reentrantOtherPlayersTableCloseRevokingAdminPermissionKeepsAllPublishedStateConsistent() throws Exception {
        assertReentrantPublication(false);
    }

    @Test
    void reentrantOtherPlayersTableCloseDisconnectingAdminKeepsAllPublishedStateConsistent() throws Exception {
        assertReentrantPublication(true);
    }

    @Test
    void disableCleansStagedFilesWhenSchedulerCancelsQueuedGateOrCommit() throws Exception {
        for (boolean gateAlreadyRan : new boolean[]{false, true}) {
            open();
            ConfigEditor.Draft previous = draft();
            coordinator.save(previous);
            worker();
            Path staged = previous.ticket.stagedPath();
            assertTrue(Files.exists(staged));
            if (gateAlreadyRan) {
                fixture.tick();
            }
            doAnswer(call -> {
                fixture.tasks.clear();
                fixture.asyncTasks.clear();
                return null;
            }).when(fixture.scheduler).cancelTasks(fixture.plugin);
            coordinator.disable();
            fixture.menus.shutdown();
            assertTrue(coordinator.awaitCleanup());
            assertTrue(previous.ticket.cleanupDone.await(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(staged));
            assertEquals(ORIGINAL, contents());
            assertTrue(fixture.tasks.isEmpty());
            assertTrue(fixture.asyncTasks.isEmpty());
            if (!gateAlreadyRan) {
                fixture = new ServerFixture(ORIGINAL);
                coordinator = new ReloadCommand(fixture.plugin, fixture.menus, Settings.read(yaml(ORIGINAL)), file.toFile(), store);
                editor = coordinator.editor();
                when(fixture.player.hasPermission(ConfigEditor.PERMISSION)).thenReturn(true);
            }
        }
    }

    @Test
    void stagingWorkerFinishingAfterDisableDeletesItsOwnFileWithoutACancelledCallback() throws Exception {
        open();
        ConfigEditor.Draft previous = draft();
        CountDownLatch staged = new CountDownLatch(1);
        CountDownLatch finishStage = new CountDownLatch(1);
        doAnswer(call -> {
            Path path = (Path) call.callRealMethod();
            staged.countDown();
            assertTrue(finishStage.await(5, TimeUnit.SECONDS));
            return path;
        }).when(store).stage(anyString(), any(byte[].class));
        coordinator.save(previous);
        Runnable stageWorker = fixture.asyncTasks.remove();
        ExecutorService workerThread = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = workerThread.submit(stageWorker);
            assertTrue(staged.await(5, TimeUnit.SECONDS));
            assertEquals(1, children(".stage-").size());
            coordinator.disable();
            fixture.menus.shutdown();
            assertTrue(coordinator.awaitCleanup());
            finishStage.countDown();
            future.get(5, TimeUnit.SECONDS);
            assertTrue(previous.ticket.cleanupDone.await(5, TimeUnit.SECONDS));
            assertTrue(children(".stage-").isEmpty());
            assertTrue(fixture.tasks.isEmpty());
            assertEquals(ORIGINAL, contents());
        } finally {
            finishStage.countDown();
            workerThread.shutdown();
            assertTrue(workerThread.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void individualUiLoreResetRestoresShippedListWhileClearStillMeansEmpty() {
        open();
        for (String[] target : new String[][]{{"icon:instructions", "Lore", "ui.icons.instructions.lore"},
                {"ui", "Level choice lore", "ui.option-lore"}}) {
            draft().document.set(target[2], Arrays.asList("Custom"));
            editor.navigate(draft(), target[0]);
            clickNamed(target[1]);
            clickNamed("Clear all lines");
            assertEquals(Collections.emptyList(), draft().document.getStringList(target[2]));
            assertTrue(Settings.read(draft().document).text().lore(target[2], Collections.emptyMap()).isEmpty());
            editor.navigate(draft(), target[0]);
            click(findSlot(target[1]), ClickType.RIGHT);
            assertTrue(draft().document.contains(target[2], true));
            assertEquals(ConfigValues.defaults().getStringList(target[2]), draft().document.getStringList(target[2]));
            assertFalse(Settings.read(draft().document).text().lore(target[2], Collections.emptyMap()).isEmpty());
            String help = ChatColor.stripColor(String.join(" ", fixture.view.getTopInventory().getItem(findSlot(target[1])).getItemMeta().getLore()));
            assertTrue(help.contains("shipped lore"));
            assertTrue(help.contains("Right-click to reset to default."));
        }
    }

    @Test
    void wholeAppearanceResetOwnsSharedStyleButIndividualIconResetDoesNot() {
        open();
        draft().document.set("ui.rows", 3);
        draft().document.set("ui.icons.back.name", "Custom");
        draft().document.set("lore-style.action-prefix", "Go: ");
        draft().document.set("sounds.enabled", false);
        editor.navigate(draft(), "icon:back");
        clickNamed("Use default icon");
        assertFalse(draft().document.contains("ui.icons.back", true));
        assertEquals("Go: ", draft().document.getString("lore-style.action-prefix"));
        assertEquals(3, draft().document.getInt("ui.rows"));
        editor.navigate(draft(), "ui");
        clickNamed("Use default appearance");
        assertFalse(draft().document.contains("ui", true));
        assertFalse(draft().document.contains("lore-style", true));
        assertFalse(draft().document.getBoolean("sounds.enabled"));
    }

    @Test
    void sharedLoreFormattingCanBeEditedWithoutCreatingUiOverridesAndAppliesToFooters() {
        open();
        clickNamed("Menu appearance");
        clickNamed("Lore formatting");
        clickNamed("Action prefix");
        verify(fixture.player).sendMessage(contains("16 visible characters"));
        input("12345678901234567");
        verify(fixture.player).sendMessage(contains("at most 16 visible characters"));
        input("Go: ");
        assertEquals("Go: ", draft().document.getString("lore-style.action-prefix"));
        assertFalse(draft().document.contains("ui", true));
        List<String> footer = fixture.view.getTopInventory().getItem(ConfigEditor.DISCARD).getItemMeta().getLore();
        assertTrue(footer.stream().map(ChatColor::stripColor).anyMatch(line -> line.equals("Go: Close without saving your draft.")));
        assertTrue(footer.stream().anyMatch(line -> line.contains(ChatColor.GREEN.toString())));
        ItemStack tableInput = Settings.read(draft().document).text().icon("instructions", Collections.emptyMap());
        assertEquals("Go: Left-click one compatible item below.", ChatColor.stripColor(tableInput.getItemMeta().getLore().get(0)));
        clickNamed("Action prefix");
        input("<blank>");
        footer = fixture.view.getTopInventory().getItem(ConfigEditor.DISCARD).getItemMeta().getLore();
        assertTrue(footer.stream().map(ChatColor::stripColor).anyMatch(line -> line.equals("Close without saving your draft.")));
        assertFalse(String.join("", footer).contains("{action}"));
        clickNamed("Action color");
        input("not a color");
        verify(fixture.player).sendMessage(contains("lore-style.action-color must contain only & color"));
        input("&d");
        assertTrue(fixture.view.getTopInventory().getItem(ConfigEditor.DISCARD).getItemMeta().getLore().get(0).contains(ChatColor.LIGHT_PURPLE.toString()));
    }

    @Test
    void itemLimitFlowExplainsLevelsAndOverrideInheritanceWithoutChangingPrices() {
        open();
        List<String> home = fixture.view.getTopInventory().getItem(findSlot("Item limits")).getItemMeta().getLore();
        String copy = ChatColor.stripColor(String.join(" ", home));
        assertTrue(copy.contains("maximum enchantment level"));
        assertTrue(copy.contains("not its XP price"));
        clickNamed("Item limits");
        List<String> add = fixture.view.getTopInventory().getItem(findSlot("Add item type")).getItemMeta().getLore();
        assertTrue(ChatColor.stripColor(String.join(" ", add)).contains("Sharpness -> 10"));
        clickNamed("Add item type");
        verify(fixture.player).sendMessage(contains("not XP price"));
        input("DIAMOND_SWORD");
        clickNamed("Sharpness");
        verify(fixture.player).sendMessage(contains("Diamond sword - Sharpness maximum level"));
        input("10");
        assertEquals(10, Settings.read(draft().document).policy().maximum(Material.DIAMOND_SWORD, ServerFixture.SHARPNESS));
        assertEquals(25, Settings.read(draft().document).prices().cost(ServerFixture.SHARPNESS, 1));
        List<String> cap = fixture.view.getTopInventory().getItem(findSlot("Sharpness")).getItemMeta().getLore();
        assertTrue(ChatColor.stripColor(String.join(" ", cap)).contains("Reset inherits"));
        click(findSlot("Sharpness"), ClickType.RIGHT);
        assertFalse(draft().document.contains("items.DIAMOND_SWORD.max-levels.DAMAGE_ALL", true));
        assertEquals(5, Settings.read(draft().document).policy().maximum(Material.DIAMOND_SWORD, ServerFixture.SHARPNESS));
    }

    @Test
    void numericEditorFieldsAndRuntimeAgreeAtBoundsWithoutYamlCoercion() throws Exception {
        ConfigFields fields = new ConfigFields();
        assertNumericAgreement(fields.general().get(0), new String[]{"1", "1000000"}, new String[]{"0", "1000001", "2147483648", "1.5"});
        assertNumericAgreement(fields.general().get(1), new String[]{"-1", "1", "1000000"}, new String[]{"-2", "0", "1000001"});
        assertNumericAgreement(fields.general().get(2), new String[]{"3", "6"}, new String[]{"2", "7"});
        assertNumericAgreement(fields.enchantment("DAMAGE_ALL").get(2), new String[]{"1", "1000000"}, new String[]{"0", "1000001"});
        assertNumericAgreement(fields.enchantment("DAMAGE_ALL").get(3), new String[]{"0", "70", "255"}, new String[]{"-1", "256"});
        for (ConfigFields.Field field : fields.shelves()) {
            if (field.kind == ConfigFields.Kind.INTEGER) {
                boolean shelfCount = field.path.endsWith("max-bookshelves");
                assertNumericAgreement(field, shelfCount ? new String[]{"1", "15"} : new String[]{"0", "1000000"},
                        shelfCount ? new String[]{"0", "16"} : new String[]{"-1", "1000001"});
            }
        }
        ConfigFields.Field data = fields.leaves("ui.icons.confirm").stream().filter(field -> field.path.endsWith(".data")).findFirst().get();
        assertNumericAgreement(data, new String[]{"0", "32767"}, new String[]{"-1", "32768"});
    }

    @Test
    void decimalEditorFieldsAndRuntimeRejectNonfiniteNumbersAndAcceptNumericYamlOnly() throws Exception {
        ConfigFields fields = new ConfigFields();
        assertNumericAgreement(fields.sounds("click").get(1), new String[]{"0", "0.5", "4"}, new String[]{"-0.01", "4.01", "NaN", "Infinity", "-Infinity", "1e309"});
        assertNumericAgreement(fields.sounds("click").get(2), new String[]{"0", "0.5", "2"}, new String[]{"-0.01", "2.01", "NaN", "Infinity"});
    }

    private void assertNumericAgreement(ConfigFields.Field field, String[] accepted, String[] rejected) throws Exception {
        for (String input : accepted) {
            YamlConfiguration document = yaml("config-version: 1\nbase-xp: 1\nmax-cost: -1\n");
            Object parsed = field.parse(input);
            document.set(field.path, parsed);
            assertDoesNotThrow(() -> Settings.read(document), field.path + "=" + input);
            document.set(field.path, input);
            assertThrows(IllegalArgumentException.class, () -> Settings.read(document), "YAML numeric string: " + field.path);
            if (field.kind == ConfigFields.Kind.INTEGER) {
                document.set(field.path, ((Number) parsed).longValue());
                assertDoesNotThrow(() -> Settings.read(document));
                document.set(field.path, ((Number) parsed).doubleValue());
                assertThrows(IllegalArgumentException.class, () -> Settings.read(document));
            }
        }
        for (String input : rejected) {
            assertThrows(IllegalArgumentException.class, () -> field.parse(input), field.path + "=" + input);
            YamlConfiguration document = yaml("config-version: 1\nbase-xp: 1\nmax-cost: -1\n");
            if (field.kind == ConfigFields.Kind.INTEGER && !input.contains(".")) {
                document.set(field.path, Long.parseLong(input));
            } else {
                document.set(field.path, Double.parseDouble(input));
            }
            assertThrows(IllegalArgumentException.class, () -> Settings.read(document), field.path + "=" + input);
        }
    }

    @Test
    void inheritedEditorCumulativePricesMatchRuntimeQuotesAndPreserveExplicitTotals() throws Exception {
        ConfigFields fields = new ConfigFields();
        YamlConfiguration document = yaml("config-version: 1\nbase-xp: 1000000\nmax-cost: -1\n");
        String path = "enchantments.DAMAGE_ALL.levels.255";
        Prices prices = Prices.read(document);
        long displayed = ((Number) fields.effective(document, path)).longValue();
        assertEquals(65025000000L, displayed);
        assertEquals(displayed, BookshelfPricing.disabled().quote(prices, ServerFixture.SHARPNESS, 0, 255, 0));
        assertFalse(prices.offers(ServerFixture.SHARPNESS, 255));
        document.set("enchantments.DAMAGE_ALL.base-xp", 100);
        document.set("enchantments.DAMAGE_ALL.levels.3", 700);
        prices = Prices.read(document);
        assertEquals(400L, fields.effective(document, "enchantments.DAMAGE_ALL.levels.2"));
        assertEquals(700, fields.effective(document, "enchantments.DAMAGE_ALL.levels.3"));
        assertEquals(300, prices.upgradeCost(ServerFixture.SHARPNESS, 2, 3));
        document.set("enchantments.DAMAGE_ALL.levels.3", 1600);
        assertThrows(IllegalArgumentException.class, () -> Prices.read(document));
        document.set("base-xp", Long.MAX_VALUE);
        document.set("enchantments.DAMAGE_ALL", null);
        assertThrows(ArithmeticException.class, () -> fields.effective(document, path));
    }

    @Test
    void percentagePromptsAndInfoLabelsUseFieldMetadataAndDistinctRoleColors() {
        open();
        clickNamed("Bookshelves");
        ItemStack percentage = fixture.view.getTopInventory().getItem(findSlot("Discount per shelf"));
        List<String> lore = percentage.getItemMeta().getLore();
        assertTrue(lore.stream().anyMatch(line -> line.contains(ChatColor.AQUA + "Value: " + ChatColor.GOLD + "10%")));
        assertTrue(lore.stream().anyMatch(line -> line.contains(ChatColor.AQUA + "Source: " + ChatColor.GRAY)));
        clickNamed("Discount per shelf");
        verify(fixture.player).sendMessage(contains("whole number from 0% to 1000000% inclusive"));
        input("1000001");
        verify(fixture.player, times(2)).sendMessage(contains("whole number from 0% to 1000000% inclusive"));
        input("10");
        assertEquals(10, draft().document.getInt("bookshelf-pricing.per-bookshelf-reduction-percent"));
    }

    @Test
    void percentageValuesAndResetInstructionsUseUnitsAndActionFormatting() {
        open();
        clickNamed("Bookshelves");
        ConfigEditor.Entry percentage = draft().view.entries.values().stream()
                .filter(entry -> entry.name.startsWith("Discount per shelf")).findFirst().get();
        assertFalse(percentage.name.contains("%"));
        List<String> lore = fixture.view.getTopInventory().getItem(findSlot(percentage.name)).getItemMeta().getLore();
        assertTrue(lore.stream().map(ChatColor::stripColor).anyMatch(line -> line.equals("Value: 10%")));
        assertTrue(lore.stream().map(ChatColor::stripColor).anyMatch(line -> line.equals("\u00bb Right-click to reset to default.")));
    }

    @Test
    void placeholderHelpListsSortedLiteralTokensOnSeparateInformationLines() {
        open();
        editor.navigate(draft(), "messages");
        List<String> lore = fixture.view.getTopInventory().getItem(findSlot("Enchantment complete")).getItemMeta().getLore();
        List<String> bullets = lore.stream().map(ChatColor::stripColor).filter(line -> line.startsWith("- {")).collect(Collectors.toList());
        assertEquals(Arrays.asList("- {balance}", "- {cost}", "- {enchantment}", "- {level}", "- {player}", "- {prefix}"), bullets);
        assertTrue(lore.stream().map(ChatColor::stripColor).anyMatch(line -> line.equals("Placeholders:")));
    }

    @Test
    void messageSettingsExplainWhenTheyAppearWithoutChangingTheirPathsOrTokens() throws Exception {
        open();
        clickNamed("Chat messages");
        int context = findSlot("Enchanting cancelled");
        String help = ChatColor.stripColor(String.join(" ", fixture.view.getTopInventory().getItem(context).getItemMeta().getLore()));
        assertTrue(help.contains("selected item"));
        assertTrue(help.contains("table"));
        clickNamed("Cannot use the table");
        input("Access changed for {player}");
        assertEquals("Access changed for {player}", draft().document.getString("messages.eligibility"));
        click(ConfigEditor.BACK, ClickType.LEFT);
        clickNamed("Sounds");
        assertTrue(findSlot("Enchantment complete") >= 0);
        assertTrue(findSlot("Not enough XP") >= 0);
    }

    private void assertReentrantPublication(boolean disconnect) throws Exception {
        open();
        ConfigEditor.Draft previous = draft();
        previous.document.set("base-xp", 40);
        previous.document.set("messages.config-error", "New settings: {detail}");
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        when(other.isOnline()).thenReturn(true);
        InventoryView otherView = fixture.orphanView(other);
        when(other.getOpenInventory()).thenReturn(otherView);
        when(fixture.plugin.getServer().getOnlinePlayers()).thenAnswer(call -> Arrays.asList(fixture.player, other));
        InventoryView otherClosed = foreignView(other);
        doAnswer(call -> {
            fixture.menus.close(new InventoryCloseEvent(otherView));
            when(other.getOpenInventory()).thenReturn(otherClosed);
            if (disconnect) {
                when(fixture.player.isOnline()).thenReturn(false);
            } else {
                when(fixture.player.hasPermission(ConfigEditor.PERMISSION)).thenReturn(false);
            }
            return null;
        }).when(other).closeInventory();
        coordinator.save(previous);
        finishSave();
        verify(other).closeInventory();
        assertEquals(40, yaml(contents()).getInt("base-xp"));
        assertFalse(editor.current(previous));
        coordinator.status(fixture.player, "config-error", "coordinator snapshot");
        verify(fixture.player).sendMessage(contains("New settings: coordinator snapshot"));
        verify(fixture.player, never()).sendMessage(contains("Saved, but not applied"));
        if (disconnect) {
            verify(fixture.player, never()).sendMessage(contains("saved and applied"));
        } else {
            verify(fixture.player).sendMessage(contains("saved and applied"));
        }
        when(fixture.player.isOnline()).thenReturn(true);
        when(fixture.player.hasPermission(ConfigEditor.PERMISSION)).thenReturn(true);
        open();
        assertEquals(previous.generation + 1, draft().generation);
        fixture.confirmPage();
        fixture.click(fixture.confirmSlot(), ClickType.LEFT);
        fixture.tick();
        assertEquals(840, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void typedPromptsNameExactBoundsAndSentinelsAndPreserveLiteralFormattingExamples() {
        open();
        clickNamed("General and prices");
        clearInvocations(fixture.player);
        clickNamed("Purchase limit");
        verify(fixture.player).sendMessage(contains("Purchase limit (max-cost): Enter a whole number from 1 to 1000000 inclusive."));
        verify(fixture.player).sendMessage(contains("Or -1 to disable"));
        verify(fixture.player, never()).sendMessage(contains("<blank>"));
        input("-1");
        assertEquals(-1, draft().document.getInt("max-cost"));
        editor.navigate(draft(), "enchant:LOOT_BONUS_BLOCKS");
        clearInvocations(fixture.player);
        clickNamed("Highest level");
        verify(fixture.player).sendMessage(contains("whole number from 0 to 255 inclusive"));
        verify(fixture.player).sendMessage(contains("70 is valid"));
        input("256");
        verify(fixture.player, times(2)).sendMessage(contains("whole number from 0 to 255 inclusive"));
        input("cancel");
        clearInvocations(fixture.player);
        clickNamed("Display name");
        verify(fixture.player).sendMessage(contains("<blank> for empty text"));
        verify(fixture.player).sendMessage(contains("&5 purple, &6 gold, &l bold, &o italic, &r reset"));
        input("\\cancel");
        assertEquals("cancel", draft().document.getString("enchantments.LOOT_BONUS_BLOCKS.name"));
        editor.navigate(draft(), "icon:confirm");
        clearInvocations(fixture.player);
        clickNamed("Item");
        verify(fixture.player).sendMessage(contains("Blank is not allowed"));
        verify(fixture.player, never()).sendMessage(contains("<blank>"));
        input("cancel");
        editor.navigate(draft(), "items");
        clearInvocations(fixture.player);
        clickNamed("Add item type");
        verify(fixture.player).sendMessage(contains("Blank is not allowed"));
        verify(fixture.player, never()).sendMessage(contains("<blank>"));
    }

    @Test
    void rejectedGlowOrFinalMetadataLeavesThePreviousEditorViewUnchanged() throws Exception {
        open();
        clickNamed("Permissions");
        ConfigEditor.Draft owner = draft();
        ConfigEditor.EditorHolder previous = owner.view;
        InventoryView previousView = fixture.view;
        owner.document.set("permissions.enabled", true);
        for (boolean rejectGlow : new boolean[]{false, true}) {
            when(Bukkit.getItemFactory().isApplicable(any(ItemMeta.class), any(Material.class)))
                    .thenAnswer(call -> {
                        ItemMeta meta = call.getArgument(0);
                        return rejectGlow || !meta.hasEnchant(Enchantment.DURABILITY);
                    });
            if (rejectGlow) {
                when(Bukkit.getItemFactory().getItemMeta(any(Material.class))).thenAnswer(call -> new MemoryItemMeta() {
                    @Override
                    public boolean addEnchant(Enchantment enchantment, int level, boolean ignore) {
                        return false;
                    }
                });
            }
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> editor.show(owner));
            assertTrue(failure.getMessage().contains(rejectGlow ? "glow" : "metadata"));
            assertSame(previousView, fixture.view);
            assertSame(previous, owner.view);
            assertFalse(previous.getInventory().getItem(findSlot("Check level permissions")).getItemMeta().hasEnchants());
        }
        assertEquals(ORIGINAL, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals(0, fixture.writes);
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void booleanStatusAndGlowFollowDraftAndNumbersContrastWithoutChangingInheritance() {
        open();
        clickNamed("Permissions");
        ItemStack disabled = fixture.view.getTopInventory().getItem(findSlot("Check level permissions"));
        assertTrue(disabled.getItemMeta().getLore().contains(ChatColor.GRAY + "" + ChatColor.RED + "Disabled"));
        assertFalse(disabled.getItemMeta().hasEnchants());
        clickNamed("Check level permissions");
        ItemStack enabled = fixture.view.getTopInventory().getItem(findSlot("Check level permissions"));
        assertTrue(enabled.getItemMeta().hasEnchant(Enchantment.DURABILITY));
        assertTrue(enabled.getItemMeta().hasItemFlag(ItemFlag.HIDE_ENCHANTS));
        assertTrue(enabled.getItemMeta().hasItemFlag(ItemFlag.HIDE_ATTRIBUTES));
        String lore = String.join("\n", enabled.getItemMeta().getLore());
        assertTrue(lore.contains(ChatColor.GREEN + "Enabled"));
        assertTrue(ChatColor.stripColor(lore).contains("Source: explicit override."));
        assertFalse(lore.contains("Uses: true"));
        editor.navigate(draft(), "enchant:DAMAGE_ALL");
        ItemStack price = fixture.view.getTopInventory().getItem(findSlot("Starting price"));
        assertTrue(String.join("\n", price.getItemMeta().getLore()).contains("Value: " + ChatColor.GOLD + "25"));
        assertFalse(draft().document.contains("enchantments.DAMAGE_ALL.base-xp", true));
    }

    @Test
    void namedListSortIsGlobalResetsPageAndRemainsPerDraftRouteWithNoHomeBack() {
        open();
        ConfigEditor.Draft owner = draft();
        assertFalse(owner.view.entries.containsKey(ConfigEditor.BACK));
        assertFalse(owner.view.entries.containsKey(ConfigEditor.SORT));
        clickNamed("Enchantments");
        click(ConfigEditor.NEXT, ClickType.LEFT);
        assertEquals(1, draft().page);
        click(ConfigEditor.SORT, ClickType.LEFT);
        assertEquals(0, draft().page);
        List<String> names = new ArrayList<>();
        do {
            for (int offset = 0; offset < 28; offset++) {
                int slot = 10 + offset / 7 * 9 + offset % 7;
                ConfigEditor.Entry entry = draft().view.entries.get(slot);
                if (entry != null) {
                    names.add(ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', entry.name)));
                }
            }
            if (!draft().view.entries.containsKey(ConfigEditor.NEXT)) {
                break;
            }
            click(ConfigEditor.NEXT, ClickType.LEFT);
        } while (true);
        List<String> expected = new ArrayList<>(names);
        expected.sort(String.CASE_INSENSITIVE_ORDER);
        assertEquals(30, names.size());
        assertEquals(expected, names);
        editor.navigate(owner, "messages");
        assertFalse(owner.alphabetical.contains("messages"));
        click(ConfigEditor.SORT, ClickType.LEFT);
        assertTrue(owner.alphabetical.contains("messages"));
        editor.navigate(owner, "enchants");
        assertTrue(owner.alphabetical.contains("enchants"));
        editor.navigate(owner, "items");
        assertFalse(owner.view.entries.containsKey(ConfigEditor.SORT));
        owner.document.set("items.DIAMOND_SWORD.max-levels.DAMAGE_ALL", 4);
        editor.show(owner);
        assertFalse(owner.view.entries.containsKey(ConfigEditor.SORT));
        assertEquals(1, owner.document.getInt("config-version"));
    }

    private InventoryView foreignView(Player player) {
        InventoryView view = mock(InventoryView.class);
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(27);
        when(view.getTopInventory()).thenReturn(inventory);
        when(view.getPlayer()).thenReturn(player);
        return view;
    }

    private void open() {
        request("config");
        if (!fixture.asyncTasks.isEmpty()) {
            worker();
            fixture.tick();
        }
        assertTrue(fixture.view.getTopInventory().getHolder() instanceof ConfigEditor.EditorHolder);
    }

    private void request(String action) {
        coordinator.onCommand(fixture.player, command, "enchantxp", new String[]{action});
    }

    private ConfigEditor.Draft draft() {
        return ((ConfigEditor.EditorHolder) fixture.view.getTopInventory().getHolder()).draft;
    }

    private InventoryClickEvent rawClick(int slot, ClickType click) {
        InventoryClickEvent event = new InventoryClickEvent(fixture.view, InventoryType.SlotType.CONTAINER, slot, click, InventoryAction.PICKUP_ALL);
        editor.click(event);
        return event;
    }

    private void click(int slot, ClickType click) {
        rawClick(slot, click);
        fixture.tick();
    }

    private void clickNamed(String name) {
        click(findSlot(name), ClickType.LEFT);
    }

    private int findSlot(String name) {
        for (int slot = 0; slot < 54; slot++) {
            ItemStack item = fixture.view.getTopInventory().getItem(slot);
            if (item != null && name.equals(ChatColor.stripColor(item.getItemMeta().getDisplayName()))) {
                return slot;
            }
        }
        fail("Missing menu entry: " + name + " in " + draft().route);
        return -1;
    }

    private AsyncPlayerChatEvent input(String message) {
        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(true, fixture.player, message, Collections.emptySet());
        editor.chat(event);
        fixture.tick();
        return event;
    }

    private void worker() {
        fixture.asyncTasks.remove().run();
    }

    private void finishSave() {
        worker();
        fixture.tick();
        worker();
        fixture.tick();
    }

    private String contents() throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private List<Path> children(String marker) throws Exception {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().contains(marker)).collect(Collectors.toList());
        }
    }
}
