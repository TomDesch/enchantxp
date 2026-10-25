package io.github.stealingdapenta.enchantxp.administration;

import io.github.stealingdapenta.enchantxp.configuration.Settings;
import io.github.stealingdapenta.enchantxp.enchanting.Enchantments;
import io.github.stealingdapenta.enchantxp.enchanting.Experience;
import io.github.stealingdapenta.enchantxp.menu.ServerFixture;
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
        assertTrue(coordinator.awaitCleanup(5, TimeUnit.SECONDS));
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
        assertEquals(Arrays.asList("config", "reload"), coordinator.onTabComplete(fixture.player, command, "enchantxp", new String[]{""}));
        assertEquals(Collections.singletonList("config"), coordinator.onTabComplete(fixture.player, command, "enchantxp", new String[]{"CoN"}));
        assertEquals(Collections.singletonList("reload"), coordinator.onTabComplete(fixture.player, command, "enchantxp", new String[]{"RE"}));
        for (String[] args : new String[][]{{"other"}, {"config", ""}, {"reload", ""}, {}}) {
            assertTrue(coordinator.onTabComplete(fixture.player, command, "enchantxp", args).isEmpty());
        }
        when(fixture.player.hasPermission(ConfigEditor.PERMISSION)).thenReturn(false);
        request("config");
        verify(fixture.player).sendMessage(contains("need enchantxp.config"));
        assertTrue(fixture.asyncTasks.isEmpty());
        assertEquals(Collections.singletonList("reload"), coordinator.onTabComplete(fixture.player, command, "enchantxp", new String[]{""}));
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
        verify(fixture.player).sendMessage(contains("whole number"));
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
    void loreEditorSupportsBlankLinesDeletionAndExplicitEmptyWhileResetRestoresOmission() throws Exception {
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
        assertFalse(draft().document.contains("ui.icons.fill.lore", true));
        assertFalse(draft().document.contains("ui.icons.confirm", true));
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
        assertEquals(Collections.singletonList(ChatColor.GRAY + "Return without paying."),
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
            Path staged = previous.ticket.staged;
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
            assertTrue(coordinator.awaitCleanup(5, TimeUnit.SECONDS));
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
            assertTrue(coordinator.awaitCleanup(5, TimeUnit.SECONDS));
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
