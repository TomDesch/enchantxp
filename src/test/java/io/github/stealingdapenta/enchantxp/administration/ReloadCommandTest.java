package io.github.stealingdapenta.enchantxp.administration;

import io.github.stealingdapenta.enchantxp.configuration.Settings;
import io.github.stealingdapenta.enchantxp.enchanting.Experience;
import io.github.stealingdapenta.enchantxp.menu.ServerFixture;
import static io.github.stealingdapenta.enchantxp.testsupport.YamlDocuments.yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Arrays;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReloadCommandTest {
    private static final String ORIGINAL = ServerFixture.LEGACY_PRICING + "base-xp: 25\nmax-cost: 1000000\n";
    private ServerFixture fixture;
    private Path directory;
    private Path file;
    private ReloadCommand reload;
    private Command command;
    private ConsoleCommandSender console;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new ServerFixture(ORIGINAL);
        directory = Files.createDirectory(Paths.get("target", "reload-test-" + UUID.randomUUID()));
        file = directory.resolve("config.yml");
        write(ORIGINAL);
        reload = new ReloadCommand(fixture.plugin, fixture.menus, Settings.read(yaml(ORIGINAL)), file.toFile());
        command = new Command("enchantxp") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                return reload.onCommand(sender, this, label, args);
            }
        };
        console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(ReloadCommand.PERMISSION)).thenReturn(true);
        when(console.getName()).thenReturn("CONSOLE");
    }

    @AfterEach
    void cleanUp() throws Exception {
        reload.disable();
        Files.deleteIfExists(file);
        Files.delete(directory);
    }

    @Test
    void permissionUsageConsoleAndCompletionUseLegacyCommandContract() {
        command.execute(fixture.player, "enchantxp", new String[]{"reload"});
        verify(fixture.player).sendMessage(contains("need enchantxp.reload"));
        assertTrue(fixture.asyncTasks.isEmpty());
        assertTrue(reload.onTabComplete(fixture.player, command, "enchantxp", new String[]{"r"}).isEmpty());
        when(fixture.player.hasPermission(ReloadCommand.PERMISSION)).thenReturn(true);
        assertEquals(Collections.singletonList("reload"),
                reload.onTabComplete(fixture.player, command, "enchantxp", new String[]{"RE"}));
        assertTrue(reload.onTabComplete(console, command, "enchantxp", new String[]{"reload", ""}).isEmpty());
        for (String[] args : new String[][]{{}, {"bad"}, {"reload", "bad"}}) {
            assertTrue(command.execute(console, "enchantxp", args));
        }
        verify(console, times(3)).sendMessage(contains("Commands you can use: /enchantxp help, /enchantxp reload"));
        assertTrue(fixture.asyncTasks.isEmpty());
        request(console);
        assertEquals(1, fixture.asyncTasks.size());
        complete();
        verify(console).sendMessage(contains("Configuration reloaded"));
    }

    @Test
    void successFencesDispatchedConfirmationAndReopensWithNewHeightTextAndPricesWithoutRewriting() throws Exception {
        fixture.confirmPage();
        fixture.click(fixture.confirmSlot(), ClickType.LEFT);
        Runnable oldConfirm = fixture.tasks.remove();
        String replacement = ServerFixture.LEGACY_PRICING + "base-xp: 10\nmax-cost: 1000000\nui:\n  rows: 3\n" +
                "  icons:\n    info:\n      name: 'New {balance}'\nmessages:\n  reload-success: 'Applied'\n";
        write(replacement);
        request(console);
        complete();
        assertNull(fixture.menuIdentity());
        oldConfirm.run();
        assertEquals(0, fixture.writes);
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
        verify(console).sendMessage(contains("Applied"));
        assertEquals(replacement, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        fixture.confirmPage();
        assertEquals(27, fixture.view.getTopInventory().getSize());
        assertEquals("New 1000", fixture.view.getTopInventory().getItem(23).getItemMeta().getDisplayName());
        assertFalse(fixture.view.getTopInventory().getItem(23).getItemMeta().hasLore());
        fixture.click(24, ClickType.LEFT);
        fixture.tick();
        assertEquals(960, Experience.total(fixture.level, fixture.progress));
        assertEquals(2, fixture.items.get(0).getEnchantmentLevel(ServerFixture.SHARPNESS));
    }

    @Test
    void invalidYamlValuesAndReadFailureKeepLiveMenusAndOldPurchaseWorking() throws Exception {
        for (String invalid : new String[]{"ui: [broken\n", "base-xp: 0\nmax-cost: 1000\n",
                "base-xp: 1\nmax-cost: 1000\nui:\n  rows: 2\n",
                "base-xp: 1\nmax-cost: 1000\nmessages:\n  reload-success: '{typo}'\n", null}) {
            fixture.items.get(0).removeEnchantment(ServerFixture.SHARPNESS);
            fixture.balance(1000);
            fixture.confirmPage();
            Object previous = fixture.menuIdentity();
            fixture.click(fixture.confirmSlot(), ClickType.LEFT);
            Runnable pendingConfirm = fixture.tasks.remove();
            if (invalid == null) { Files.delete(file); } else { write("config-version: 1\n" + invalid); }
            request(console);
            complete();
            assertSame(previous, fixture.menuIdentity());
            pendingConfirm.run();
            assertEquals(900, Experience.total(fixture.level, fixture.progress));
            assertEquals(2, fixture.items.get(0).getEnchantmentLevel(ServerFixture.SHARPNESS));
        }
        verify(console, times(5)).sendMessage(contains(file.toFile().getPath()));
    }

    @Test
    void invalidFeatureCandidatesAndForeignPermissionConflictPreservePendingConfirmation() throws Exception {
        Permission foreign = new Permission("enchantxp.enchant.sharpness.*", PermissionDefault.TRUE);
        fixture.manager.addPermission(foreign);
        for (String extra : new String[]{"sounds:\n  click:\n    pitch: .NaN\n",
                "bookshelf-pricing:\n  max-bookshelves: 16\n", "permissions:\n  enabled: true\n"}) {
            fixture.confirmPage();
            Object old = fixture.menuIdentity();
            fixture.click(fixture.confirmSlot(), ClickType.LEFT);
            Runnable confirm = fixture.tasks.remove();
            String candidate = "config-version: 1\nbase-xp: 7\nmax-cost: 1000000\n" + extra;
            write(candidate);
            request(console);
            complete();
            assertSame(old, fixture.menuIdentity());
            assertSame(foreign, fixture.manager.getPermission(foreign.getName()));
            assertEquals(Collections.singleton(foreign), fixture.manager.getPermissions());
            assertEquals(candidate, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            confirm.run();
            assertNull(fixture.menuIdentity());
            fixture.items.put(0, new ItemStack(Material.DIAMOND_SWORD));
            fixture.balance(1000);
        }
        assertEquals(3, fixture.writes);
    }

    @Test
    void reloadLeavesExternalInventoryAndInvalidatesQueuedSelectionAndDeferredOpen() throws Exception {
        for (int stage = 0; stage < 3; stage++) {
            if (stage == 0) {
                fixture.menus.interact(new PlayerInteractEvent(fixture.player, Action.RIGHT_CLICK_BLOCK,
                        fixture.items.get(0), fixture.block, BlockFace.UP));
            } else {
                fixture.openTable();
                fixture.select(0, ClickType.LEFT);
            }
            Runnable old = fixture.tasks.remove();
            if (stage == 2) { fixture.player.closeInventory(); }
            Inventory external = fixture.view.getTopInventory();
            clearInvocations(fixture.player);
            request(console);
            complete();
            old.run();
            if (stage == 2 || stage == 0) {
                assertSame(external, fixture.view.getTopInventory());
                verify(fixture.player, never()).closeInventory();
            }
            assertNull(fixture.menuIdentity());
            assertEquals(0, fixture.writes);
        }
    }

    @Test
    void repeatedRequestsAreSerializedAndOldCompletionCannotOverwriteLaterReload() throws Exception {
        write(ServerFixture.LEGACY_PRICING + "base-xp: 10\nmax-cost: 1000000\nui:\n  rows: 3\n");
        request(console);
        request(console);
        verify(console).sendMessage(contains("already in progress"));
        assertEquals(1, fixture.asyncTasks.size());
        fixture.asyncTasks.remove().run();
        Runnable firstCompletion = fixture.tasks.remove();
        firstCompletion.run();
        write(ServerFixture.LEGACY_PRICING + "base-xp: 7\nmax-cost: 1000000\nui:\n  rows: 4\n");
        request(console);
        complete();
        firstCompletion.run();
        fixture.confirmPage();
        assertEquals(36, fixture.view.getTopInventory().getSize());
        fixture.click(33, ClickType.LEFT);
        fixture.tick();
        assertEquals(972, Experience.total(fixture.level, fixture.progress));
        verify(console, times(2)).sendMessage(contains("Configuration reloaded"));
    }

    @Test
    void permissionRevokedDuringReadDeniesPublicationWithoutClosingOrInvalidatingOldMenu() throws Exception {
        when(fixture.player.hasPermission(ReloadCommand.PERMISSION)).thenReturn(true);
        fixture.confirmPage();
        Object old = fixture.menuIdentity();
        fixture.click(fixture.confirmSlot(), ClickType.LEFT);
        Runnable confirmation = fixture.tasks.remove();
        write("config-version: 1\nbase-xp: 10\nmax-cost: 1000000\nui:\n  rows: 3\n");
        request(fixture.player);
        fixture.asyncTasks.remove().run();
        when(fixture.player.hasPermission(ReloadCommand.PERMISSION)).thenReturn(false);
        fixture.tick();
        assertSame(old, fixture.menuIdentity());
        verify(fixture.player).sendMessage(contains("need enchantxp.reload"));
        verify(fixture.player, never()).sendMessage(contains("Configuration reloaded"));
        verify(fixture.player, never()).closeInventory();
        assertEquals(0, fixture.writes);
        confirmation.run();
        assertEquals(900, Experience.total(fixture.level, fixture.progress));
        assertEquals(2, fixture.items.get(0).getEnchantmentLevel(ServerFixture.SHARPNESS));
    }

    @Test
    void disconnectedOrReplacedPlayerRequesterCannotPublishAnInflightReload() throws Exception {
        when(fixture.player.hasPermission(ReloadCommand.PERMISSION)).thenReturn(true);
        for (boolean disconnected : new boolean[]{true, false}) {
            when(fixture.player.isOnline()).thenReturn(true);
            when(Bukkit.getServer().getPlayer(fixture.player.getUniqueId())).thenReturn(fixture.player);
            fixture.openTable();
            Object old = fixture.menuIdentity();
            request(fixture.player);
            fixture.asyncTasks.remove().run();
            if (disconnected) {
                when(fixture.player.isOnline()).thenReturn(false);
            } else {
                when(Bukkit.getServer().getPlayer(fixture.player.getUniqueId()))
                        .thenReturn(mock(Player.class));
            }
            fixture.tick();
            assertSame(old, fixture.menuIdentity());
            assertEquals(0, fixture.writes);
        }
        verify(fixture.player, never()).sendMessage(contains("Configuration reloaded"));
        verify(fixture.player, never()).closeInventory();
    }

    @Test
    void disableFencesBothInflightReadAndAlreadyDispatchedCompletion() throws Exception {
        request(console);
        fixture.asyncTasks.remove().run();
        Runnable completion = fixture.tasks.remove();
        reload.disable();
        fixture.menus.shutdown();
        completion.run();
        verify(console, never()).sendMessage(contains("Configuration reloaded"));
        assertNull(fixture.menuIdentity());
        assertEquals(0, fixture.writes);
        command.execute(console, "enchantxp", new String[]{"reload"});
        verify(console).sendMessage(contains("stopped"));
        assertTrue(fixture.asyncTasks.isEmpty());
    }

    @Test
    void disabledBeforeFileReadDoesNotScheduleCompletionOrReadBukkitState() {
        request(console);
        Runnable read = fixture.asyncTasks.remove();
        reload.disable();
        clearInvocations(fixture.player, fixture.plugin);
        read.run();
        assertTrue(fixture.tasks.isEmpty());
        verifyNoInteractions(fixture.player, fixture.plugin);
    }

    @Test
    void disableDuringMenuCloseCannotPublishReplacementOrReportSuccess() throws Exception {
        fixture.open();
        write("config-version: 1\nbase-xp: 10\nmax-cost: 1000000\nui:\n  rows: 3\n");
        // Avoid a recursive close on shutdown, just as a native close event replaces the view.
        doAnswer(call -> {
            Inventory external = mock(Inventory.class);
            when(external.getSize()).thenReturn(9);
            fixture.view = fixture.new View(external);
            reload.disable();
            fixture.menus.shutdown();
            return null;
        }).when(fixture.player).closeInventory();
        request(console);
        complete();
        assertNull(fixture.menuIdentity());
        verify(console, never()).sendMessage(contains("Configuration reloaded"));
        assertEquals(0, fixture.writes);
    }

    @Test
    void fileReadRunsWithoutPrimaryThreadBukkitAccessAndPublishesOnMainThread() {
        request(console);
        when(Bukkit.getServer().isPrimaryThread()).thenReturn(false);
        clearInvocations(fixture.player, fixture.plugin);
        fixture.asyncTasks.remove().run();
        verifyNoInteractions(fixture.player, fixture.plugin);
        when(Bukkit.getServer().isPrimaryThread()).thenReturn(true);
        fixture.tick();
        verify(console).sendMessage(contains("Configuration reloaded"));
    }

    @Test
    void missingAndIncompatibleSchemasKeepHealthySnapshotAndExactOperatorFile() throws Exception {
        for (String version : new String[]{"", "config-version: 2\n", "config-version: '1'\n"}) {
            String invalid = version + "base-xp: 1\nmax-cost: -1\n";
            write(invalid);
            request(console);
            complete();
            assertArrayEquals(invalid.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
            int balance = Experience.total(fixture.level, fixture.progress);
            fixture.confirmPage();
            fixture.click(fixture.confirmSlot(), ClickType.LEFT);
            fixture.tick();
            assertEquals(balance - 100, Experience.total(fixture.level, fixture.progress));
            fixture.items.get(0).removeEnchantment(ServerFixture.SHARPNESS);
        }
        verify(console, times(3)).sendMessage(contains("Previous settings kept"));
    }

    @Test
    void helpUnknownAndNoArgumentsRevealOnlyUsableCommandsWithoutReloadPermission() {
        for (String[] arguments : new String[][]{{}, {"help"}, {"unknown"}, {"config", "extra"}}) {
            command.execute(fixture.player, "enchantxp", arguments);
        }
        verify(fixture.player, times(4)).sendMessage(contains("Commands you can use: /enchantxp help"));
        verify(fixture.player, never()).sendMessage(contains("/enchantxp reload"));
        verify(fixture.player, never()).sendMessage(contains("/enchantxp config"));
        assertEquals(Collections.singletonList("help"),
                reload.onTabComplete(fixture.player, command, "enchantxp", new String[]{""}));
        when(fixture.player.hasPermission(ConfigEditor.PERMISSION)).thenReturn(true);
        command.execute(fixture.player, "enchantxp", new String[]{"HELP"});
        verify(fixture.player).sendMessage(contains("/enchantxp config (in-game)"));
        assertEquals(Arrays.asList("help", "config"),
                reload.onTabComplete(fixture.player, command, "enchantxp", new String[]{""}));
        when(console.hasPermission(ConfigEditor.PERMISSION)).thenReturn(true);
        command.execute(console, "enchantxp", new String[]{"help"});
        verify(console, never()).sendMessage(contains("/enchantxp config"));
        assertEquals(Arrays.asList("help", "reload"),
                reload.onTabComplete(console, command, "enchantxp", new String[]{""}));
        assertTrue(fixture.asyncTasks.isEmpty());
    }

    private void request(CommandSender sender) {
        assertTrue(command.execute(sender, "enchantxp", new String[]{"reload"}));
    }

    private void complete() {
        fixture.asyncTasks.remove().run();
        fixture.tick();
    }

    private void write(String contents) throws Exception {
        Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
    }
}
