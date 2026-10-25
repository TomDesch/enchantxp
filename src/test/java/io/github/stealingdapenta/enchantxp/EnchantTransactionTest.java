package io.github.stealingdapenta.enchantxp;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EnchantTransactionTest {
    private ServerFixture fixture;
    private EnchantTransaction transaction;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new ServerFixture();
        transaction = new EnchantTransaction(fixture.prices);
    }

    @Test
    void plainBookOffersUseExplicitDestinationCapRatherThanSourceCap() throws Exception {
        Settings settings = Settings.read(PricesTest.yaml("base-xp: 25\nmax-cost: 1000000\nitems:\n" +
                "  BOOK:\n    max-levels:\n      DAMAGE_ALL: 8\n" +
                "  ENCHANTED_BOOK:\n    max-levels:\n      DAMAGE_ALL: 2\n"));
        ItemStack book = new ItemStack(Material.BOOK);
        assertEquals(Arrays.asList(1, 2), Enchantments.levels(book, ServerFixture.SHARPNESS,
                settings.prices, settings.policy));
        assertEquals(2, settings.policy.maximum(Material.BOOK, ServerFixture.SHARPNESS));
        assertFalse(Enchantments.legal(book, ServerFixture.SHARPNESS, 6, settings.policy));
        assertEquals(100, settings.prices.upgradeCost(ServerFixture.SHARPNESS, 0, 2));
        assertEquals(900, settings.prices.upgradeCost(ServerFixture.SHARPNESS, 0, 6));
        fixture.items.put(0, book);
        assertTrue(new EnchantTransaction(settings.prices, settings.policy).commit(
                fixture.player, fixture.session(), ServerFixture.SHARPNESS, 2, () -> true));
        assertEquals(900, Experience.total(fixture.level, fixture.progress));
        assertTrue(Enchantments.supported(fixture.items.get(0), settings.policy));
    }

    @Test
    void plainBookCommitRejectsDestinationOvercapOrDisabledPurchaseBeforeAnyMutation() throws Exception {
        for (int cap : new int[]{2, 0}) {
            Settings settings = Settings.read(PricesTest.yaml("base-xp: 25\nmax-cost: 1000000\nitems:\n" +
                    "  BOOK:\n    max-levels:\n      DAMAGE_ALL: 8\n" +
                    "  ENCHANTED_BOOK:\n    max-levels:\n      DAMAGE_ALL: " + cap + "\n"));
            ItemStack book = new ItemStack(Material.BOOK);
            fixture.items.put(0, book);
            EnchantSession session = fixture.session();
            assertFalse(new EnchantTransaction(settings.prices, settings.policy).commit(
                    fixture.player, session, ServerFixture.SHARPNESS, 6, () -> true));
            assertSame(book, fixture.items.get(0));
            assertEquals(Material.BOOK, book.getType());
            assertEquals(1000, Experience.total(fixture.level, fixture.progress));
            assertEquals(0, fixture.writes);
            verify(fixture.player, never()).setLevel(anyInt());
        }
    }

    @Test
    void bookSnapshotChangesAndRejectedStorageMetadataCannotChargeOrPublish() {
        fixture.items.put(0, new ItemStack(Material.ENCHANTED_BOOK));
        EnchantSession stale = fixture.session();
        EnchantmentStorageMeta changed = (EnchantmentStorageMeta) fixture.items.get(0).getItemMeta();
        changed.addStoredEnchant(ServerFixture.UNBREAKING, 1, true);
        fixture.items.get(0).setItemMeta(changed);
        assertFalse(commit(stale, 2));
        ItemStack snapshot = fixture.items.get(0).clone();
        EnchantmentStorageMeta rejected = mock(EnchantmentStorageMeta.class);
        when(Bukkit.getItemFactory().asMetaFor(any(ItemMeta.class), eq(Material.ENCHANTED_BOOK)))
                .thenReturn(rejected);
        assertThrows(IllegalStateException.class, () -> commit(fixture.session(), 2));
        assertEquals(snapshot, fixture.items.get(0));
        assertEquals(0, fixture.writes);
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void bookMetadataSetterAndStoredLevelReadbackRejectionsHappenBeforeXpWrites() {
        for (boolean setterRejects : new boolean[]{true, false}) {
            fixture.items.put(0, new ItemStack(Material.BOOK));
            EnchantSession session = fixture.session();
            EnchantmentStorageMeta meta = mock(EnchantmentStorageMeta.class);
            when(meta.clone()).thenReturn(meta);
            when(meta.addStoredEnchant(ServerFixture.SHARPNESS, 2, true)).thenReturn(true);
            when(Bukkit.getItemFactory().asMetaFor(any(ItemMeta.class), eq(Material.ENCHANTED_BOOK))).thenReturn(meta);
            when(Bukkit.getItemFactory().isApplicable(any(ItemMeta.class), eq(Material.ENCHANTED_BOOK)))
                    .thenReturn(!setterRejects);
            assertThrows(IllegalStateException.class, () -> commit(session, 2));
            assertEquals(Material.BOOK, fixture.items.get(0).getType());
            assertEquals(1000, Experience.total(fixture.level, fixture.progress));
            assertEquals(0, fixture.writes);
            verify(fixture.player, never()).setLevel(anyInt());
        }
    }

    @Test
    void storedBookUpgradePreservesOtherStoredEnchantAndRejectsConflictsUnsafeAndStacks() {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        meta.addStoredEnchant(ServerFixture.SHARPNESS, 2, true);
        meta.addStoredEnchant(ServerFixture.UNBREAKING, 1, true);
        meta.setDisplayName("Stored original");
        book.setItemMeta(meta);
        fixture.items.put(0, book);
        assertFalse(Enchantments.legal(book, ServerFixture.SMITE, 1));
        assertFalse(Enchantments.legal(book, ServerFixture.CUSTOM, 1));
        Enchantment impostor = mock(Enchantment.class);
        when(impostor.getName()).thenReturn("DAMAGE_ALL");
        assertFalse(Enchantments.legal(book, impostor, 3));
        assertTrue(commit(fixture.session(), 3));
        assertEquals(875, Experience.total(fixture.level, fixture.progress));
        assertEquals(1, ((EnchantmentStorageMeta) fixture.items.get(0).getItemMeta())
                .getStoredEnchantLevel(ServerFixture.UNBREAKING));
        assertEquals(2, ((EnchantmentStorageMeta) book.getItemMeta()).getStoredEnchantLevel(ServerFixture.SHARPNESS));
        assertEquals("Stored original", fixture.items.get(0).getItemMeta().getDisplayName());
        meta.addStoredEnchant(ServerFixture.SHARPNESS, 6, true);
        book.setItemMeta(meta);
        assertFalse(Enchantments.supported(book));
        book = new ItemStack(Material.BOOK, 2);
        assertTrue(Enchantments.supported(book));
        assertFalse(Enchantments.supported(new ItemStack(Material.ENCHANTED_BOOK, 2)));
        assertFalse(Enchantments.supported(new ItemStack(Material.BOOK_AND_QUILL)));
        assertFalse(Enchantments.supported(new ItemStack(Material.WRITTEN_BOOK)));
    }

    @Test
    void ordinaryBookStackEligibilityUsesNativeMaximumAndOtherStacksRemainRejected() {
        for (int amount : new int[]{1, 2, 64}) {
            assertTrue(Enchantments.supported(new ItemStack(Material.BOOK, amount)));
        }
        for (int amount : new int[]{0, 65}) {
            assertFalse(Enchantments.supported(new ItemStack(Material.BOOK, amount)));
        }
        assertFalse(Enchantments.supported(new ItemStack(Material.DIAMOND_SWORD, 2)));
    }

    @Test
    void stackedBookPurchaseSplitsExactlyOnePreservesMetadataAndClaimsOnlyOnce() {
        ItemStack original = new ItemStack(Material.BOOK, 64);
        ItemMeta meta = original.getItemMeta();
        meta.setDisplayName("Trading stack");
        meta.setLore(Arrays.asList("Kept lore"));
        original.setItemMeta(meta);
        fixture.items.put(0, original);
        fixture.fillStorage();
        fixture.items.put(35, new ItemStack(Material.AIR, 0));
        EnchantSession session = fixture.session();
        assertTrue(commit(session, 2));
        assertEquals(64, original.getAmount());
        ItemStack remainder = original.clone();
        remainder.setAmount(63);
        assertEquals(remainder, fixture.items.get(0));
        ItemStack result = fixture.items.get(35);
        assertEquals(Material.ENCHANTED_BOOK, result.getType());
        assertEquals(1, result.getAmount());
        assertEquals(2, Enchantments.level(result, ServerFixture.SHARPNESS));
        assertEquals(meta.getDisplayName(), result.getItemMeta().getDisplayName());
        assertEquals(meta.getLore(), result.getItemMeta().getLore());
        assertEquals(900, Experience.total(fixture.level, fixture.progress));
        assertFalse(commit(session, 2));
        assertEquals(2, fixture.writes);
        verify(fixture.inventory, never()).addItem(any(ItemStack[].class));
    }

    @Test
    void stackedBookFromLastStorageSlotCanUseEmptySlotZeroWithoutTreatingSourceAsOutput() {
        fixture.fillStorage();
        fixture.items.put(0, new ItemStack(Material.AIR, 0));
        fixture.items.put(35, new ItemStack(Material.BOOK, 2));
        EnchantSession session = new EnchantSession(fixture.player.getUniqueId(), 0, 35,
                fixture.items.get(35), fixture.block.getLocation());
        assertTrue(commit(session, 2));
        assertEquals(Material.BOOK, fixture.items.get(35).getType());
        assertEquals(1, fixture.items.get(35).getAmount());
        assertEquals(Material.ENCHANTED_BOOK, fixture.items.get(0).getType());
        assertEquals(1, fixture.items.get(0).getAmount());
        assertEquals(2, Enchantments.level(fixture.items.get(0), ServerFixture.SHARPNESS));
    }

    @Test
    void stackedBookMetadataChangeInvalidatesSnapshotAndNullDestinationIsRestoredOnFailure() {
        fixture.items.put(0, new ItemStack(Material.BOOK, 2));
        EnchantSession stale = fixture.session();
        ItemMeta meta = fixture.items.get(0).getItemMeta();
        meta.setDisplayName("Changed stack name");
        fixture.items.get(0).setItemMeta(meta);
        assertFalse(commit(stale, 2));
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            fixture.items.put(1, call.getArgument(1));
            if (first.getAndSet(false)) { throw new IllegalStateException("mutated then failed"); }
            return null;
        }).when(fixture.inventory).setItem(eq(1), nullable(ItemStack.class));
        ItemStack before = fixture.items.get(0).clone();
        assertThrows(IllegalStateException.class, () -> commit(fixture.session(), 2));
        assertEquals(before, fixture.items.get(0));
        assertNull(fixture.items.get(1));
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
        verify(fixture.inventory).setItem(1, null);
    }

    @Test
    void bookOutputSpaceExcludesEquipmentAndSingleBookCanReplaceSourceInFullStorage() {
        fixture.items.put(0, new ItemStack(Material.BOOK, 2));
        fixture.fillStorage();
        EnchantSession blocked = fixture.session();
        assertThrows(EnchantTransaction.OutputSpaceException.class, () -> commit(blocked, 2));
        assertEquals(0, fixture.writes);
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
        assertFalse(commit(blocked, 2));
        fixture.items.put(0, new ItemStack(Material.BOOK));
        assertTrue(commit(fixture.session(), 2));
        assertEquals(Material.ENCHANTED_BOOK, fixture.items.get(0).getType());
        assertEquals(1, fixture.writes);
    }

    @Test
    void stackedBookAmountAndDestinationAreRevalidatedBeforeAnyPublication() {
        fixture.items.put(0, new ItemStack(Material.BOOK, 64));
        EnchantSession stale = fixture.session();
        fixture.items.get(0).setAmount(63);
        assertFalse(commit(stale, 2));
        fixture.fillStorage();
        fixture.items.remove(35);
        AtomicBoolean firstContext = new AtomicBoolean(true);
        assertThrows(EnchantTransaction.OutputSpaceException.class, () -> transaction.commit(
                fixture.player, fixture.session(), ServerFixture.SHARPNESS, 2, () -> {
                    if (!firstContext.getAndSet(false)) {
                        fixture.items.put(35, new ItemStack(Material.STONE));
                    }
                    return true;
                }));
        assertEquals(63, fixture.items.get(0).getAmount());
        assertEquals(Material.STONE, fixture.items.get(35).getType());
        assertEquals(0, fixture.writes);
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void stackedBookRollbackRestoresBothSlotsAndXpAfterEveryPublishingSetterFailure() throws Exception {
        for (int stage = 0; stage < 5; stage++) {
            fixture = new ServerFixture();
            fixture.items.put(0, new ItemStack(Material.BOOK, 64));
            ItemStack original = fixture.items.get(0).clone();
            fixture.fillStorage();
            ItemStack empty = new ItemStack(Material.AIR, 0);
            fixture.items.put(35, empty);
            AtomicBoolean once = new AtomicBoolean(true);
            IllegalStateException failure = new IllegalStateException("setter after mutation " + stage);
            if (stage == 0) {
                doAnswer(call -> {
                    fixture.level = call.getArgument(0);
                    if (once.getAndSet(false)) { throw failure; }
                    return null;
                }).when(fixture.player).setLevel(anyInt());
            } else if (stage == 1) {
                doAnswer(call -> {
                    fixture.progress = call.getArgument(0);
                    if (once.getAndSet(false)) { throw failure; }
                    return null;
                }).when(fixture.player).setExp(anyFloat());
            } else if (stage == 2) {
                doAnswer(call -> {
                    fixture.totalScore = call.getArgument(0);
                    if (once.getAndSet(false)) { throw failure; }
                    return null;
                }).when(fixture.player).setTotalExperience(anyInt());
            } else {
                int failSlot = stage == 3 ? 0 : 35;
                doAnswer(call -> {
                    int slot = call.getArgument(0);
                    fixture.items.put(slot, call.getArgument(1));
                    if (slot == failSlot && once.getAndSet(false)) { throw failure; }
                    return null;
                }).when(fixture.inventory).setItem(anyInt(), nullable(ItemStack.class));
            }
            EnchantSession session = fixture.session();
            assertSame(failure, assertThrows(IllegalStateException.class, () -> commit(session, 2)));
            assertEquals(original, fixture.items.get(0), "stage " + stage);
            assertEquals(empty, fixture.items.get(35), "stage " + stage);
            assertEquals(1000, Experience.total(fixture.level, fixture.progress), "stage " + stage);
            assertEquals(1000, fixture.totalScore);
            verify(fixture.inventory).setItem(0, original);
            verify(fixture.inventory).setItem(35, empty);
            assertFalse(commit(session, 2));
        }
    }

    @Test
    void nonAirZeroAmountOutputDoesNotProveCompensationRemovedEnchantedBook() {
        fixture.items.put(0, new ItemStack(Material.BOOK, 64));
        IllegalStateException failure = new IllegalStateException("published then failed");
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            if (first.getAndSet(false)) {
                fixture.items.put(1, call.getArgument(1));
                throw failure;
            }
            fixture.items.get(1).setAmount(0);
            return null;
        }).when(fixture.inventory).setItem(eq(1), nullable(ItemStack.class));
        EnchantSession session = fixture.session();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> commit(session, 2)));
        assertEquals(Material.ENCHANTED_BOOK, fixture.items.get(1).getType());
        assertEquals(0, fixture.items.get(1).getAmount());
        assertEquals(63, fixture.items.get(0).getAmount());
        assertEquals(900, Experience.total(fixture.level, fixture.progress));
        verify(fixture.player, never()).setTotalExperience(1000);
        assertFalse(commit(session, 2));
    }

    @Test
    void retainedSingleItemResultAfterFailedOriginalRestoreCannotRefundOrRetry() throws Exception {
        for (Material material : new Material[]{Material.DIAMOND_SWORD, Material.BOOK, Material.ENCHANTED_BOOK}) {
            fixture = new ServerFixture();
            fixture.items.put(0, new ItemStack(material));
            IllegalStateException failure = new IllegalStateException("source published then failed");
            IllegalStateException rollback = new IllegalStateException("original restore failed before write");
            AtomicBoolean first = new AtomicBoolean(true);
            doAnswer(call -> {
                if (first.getAndSet(false)) {
                    fixture.items.put(0, call.getArgument(1));
                    throw failure;
                }
                throw rollback;
            }).when(fixture.inventory).setItem(eq(0), nullable(ItemStack.class));
            EnchantSession session = fixture.session();
            assertSame(failure, assertThrows(IllegalStateException.class, () -> commit(session, 2)));
            assertEquals(2, Enchantments.level(fixture.items.get(0), ServerFixture.SHARPNESS));
            assertEquals(900, Experience.total(fixture.level, fixture.progress), material.name());
            assertTrue(Arrays.asList(failure.getSuppressed()).contains(rollback));
            verify(fixture.player, never()).setTotalExperience(1000);
            verify(fixture.inventory, never()).setItem(eq(0), isNull());
            assertFalse(commit(session, 2));
        }
    }

    @Test
    void singleItemOriginalRestoreThenThrowStillPermitsRefundAfterExactReadback() throws Exception {
        for (Material material : new Material[]{Material.DIAMOND_SWORD, Material.BOOK, Material.ENCHANTED_BOOK}) {
            fixture = new ServerFixture();
            ItemStack original = new ItemStack(material);
            ItemMeta meta = original.getItemMeta();
            meta.setDisplayName("Original named item");
            meta.setLore(Arrays.asList("Original lore"));
            original.setItemMeta(meta);
            fixture.items.put(0, original);
            IllegalStateException failure = new IllegalStateException("published then failed");
            IllegalStateException rollback = new IllegalStateException("original restored then failed");
            AtomicBoolean first = new AtomicBoolean(true);
            doAnswer(call -> {
                fixture.items.put(0, call.getArgument(1));
                if (first.getAndSet(false)) { throw failure; }
                throw rollback;
            }).when(fixture.inventory).setItem(eq(0), nullable(ItemStack.class));
            EnchantSession session = fixture.session();
            assertSame(failure, assertThrows(IllegalStateException.class, () -> commit(session, 2)));
            assertEquals(original, fixture.items.get(0));
            assertEquals(1000, Experience.total(fixture.level, fixture.progress));
            assertArrayEquals(new Throwable[]{rollback}, failure.getSuppressed());
            verify(fixture.inventory, never()).setItem(eq(0), isNull());
            assertFalse(commit(session, 2));
        }
    }

    @Test
    void unreadableSingleItemSourceRetainsChargeEvenWhenSetterAppearsToRestoreOriginal() throws Exception {
        for (boolean restoreOriginal : new boolean[]{false, true}) {
            fixture = new ServerFixture();
            ItemStack original = fixture.items.get(0).clone();
            IllegalStateException failure = new IllegalStateException("source published then failed");
            IllegalStateException readback = new IllegalStateException("source readback unavailable");
            AtomicBoolean first = new AtomicBoolean(true);
            doAnswer(call -> {
                if (first.getAndSet(false)) {
                    fixture.items.put(0, call.getArgument(1));
                    throw failure;
                }
                if (restoreOriginal) { fixture.items.put(0, call.getArgument(1)); }
                return null;
            }).when(fixture.inventory).setItem(eq(0), nullable(ItemStack.class));
            when(fixture.inventory.getItem(0)).thenAnswer(call -> {
                if (!first.get()) { throw readback; }
                return fixture.items.get(0);
            });
            EnchantSession session = fixture.session();
            assertSame(failure, assertThrows(IllegalStateException.class, () -> commit(session, 2)));
            assertEquals(900, Experience.total(fixture.level, fixture.progress));
            assertTrue(Arrays.asList(failure.getSuppressed()).contains(readback));
            if (restoreOriginal) { assertEquals(original, fixture.items.get(0)); }
            else { assertEquals(2, Enchantments.level(fixture.items.get(0), ServerFixture.SHARPNESS)); }
            verify(fixture.player, never()).setTotalExperience(1000);
            assertFalse(commit(session, 2));
        }
    }

    @Test
    void provenSingleItemRestoreStillAttemptsEveryFailingXpRollbackSetter() {
        ItemStack original = fixture.items.get(0).clone();
        IllegalStateException failure = new IllegalStateException("source published then failed");
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            fixture.items.put(0, call.getArgument(1));
            if (first.getAndSet(false)) { throw failure; }
            throw new IllegalStateException("source restored then failed");
        }).when(fixture.inventory).setItem(eq(0), nullable(ItemStack.class));
        doAnswer(call -> {
            if (!first.get()) { throw new IllegalStateException("level restoration failed"); }
            fixture.level = call.getArgument(0);
            return null;
        }).when(fixture.player).setLevel(anyInt());
        doAnswer(call -> {
            if (!first.get()) { throw new IllegalStateException("progress restoration failed"); }
            fixture.progress = call.getArgument(0);
            return null;
        }).when(fixture.player).setExp(anyFloat());
        doAnswer(call -> {
            if (!first.get()) { throw new IllegalStateException("total restoration failed"); }
            fixture.totalScore = call.getArgument(0);
            return null;
        }).when(fixture.player).setTotalExperience(anyInt());
        assertSame(failure, assertThrows(IllegalStateException.class, () -> commit(fixture.session(), 2)));
        assertEquals(original, fixture.items.get(0));
        assertEquals(4, failure.getSuppressed().length);
        assertEquals("source restored then failed", failure.getSuppressed()[0].getMessage());
        assertEquals("total restoration failed", failure.getSuppressed()[3].getMessage());
        verify(fixture.player).setTotalExperience(1000);
    }

    @Test
    void retainedBookOutputAfterRollbackFailureCannotRefundXpOrRestoreConsumedBook() {
        fixture.items.put(0, new ItemStack(Material.BOOK, 64));
        IllegalStateException failure = new IllegalStateException("destination published then failed");
        IllegalStateException rollback = new IllegalStateException("destination rollback failed before clearing");
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            if (first.getAndSet(false)) {
                fixture.items.put(1, call.getArgument(1));
                throw failure;
            }
            throw rollback;
        }).when(fixture.inventory).setItem(eq(1), nullable(ItemStack.class));
        EnchantSession session = fixture.session();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> commit(session, 2)));
        assertEquals(63, fixture.items.get(0).getAmount());
        assertEquals(Material.ENCHANTED_BOOK, fixture.items.get(1).getType());
        assertEquals(1, fixture.items.get(1).getAmount());
        assertEquals(2, Enchantments.level(fixture.items.get(1), ServerFixture.SHARPNESS));
        assertEquals(900, Experience.total(fixture.level, fixture.progress));
        assertTrue(Arrays.asList(failure.getSuppressed()).contains(rollback));
        verify(fixture.inventory, times(1)).setItem(eq(0), any(ItemStack.class));
        verify(fixture.player, never()).setTotalExperience(1000);
        assertFalse(commit(session, 2));
    }

    @Test
    void clearedOutputAfterSetterThrowsStillAllowsFullSourceAndXpRollback() {
        fixture.items.put(0, new ItemStack(Material.BOOK, 64));
        IllegalStateException failure = new IllegalStateException("destination published then failed");
        IllegalStateException rollback = new IllegalStateException("destination cleared then failed");
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            fixture.items.put(1, call.getArgument(1));
            if (first.getAndSet(false)) { throw failure; }
            throw rollback;
        }).when(fixture.inventory).setItem(eq(1), nullable(ItemStack.class));
        ItemStack original = fixture.items.get(0).clone();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> commit(fixture.session(), 2)));
        assertNull(fixture.items.get(1));
        assertEquals(original, fixture.items.get(0));
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
        assertArrayEquals(new Throwable[]{rollback}, failure.getSuppressed());
    }

    @Test
    void unverifiableOutputRollbackOrSilentlyIgnoredClearCannotRefundResources() throws Exception {
        for (boolean unreadable : new boolean[]{false, true}) {
            fixture = new ServerFixture();
            fixture.items.put(0, new ItemStack(Material.BOOK, 64));
            IllegalStateException failure = new IllegalStateException("destination published then failed");
            IllegalStateException readback = new IllegalStateException("destination readback failed");
            AtomicBoolean first = new AtomicBoolean(true);
            doAnswer(call -> {
                if (first.getAndSet(false)) {
                    fixture.items.put(1, call.getArgument(1));
                    throw failure;
                }
                // A silent no-op must not count as successfully clearing the output.
                return null;
            }).when(fixture.inventory).setItem(eq(1), nullable(ItemStack.class));
            if (unreadable) {
                when(fixture.inventory.getItem(1)).thenAnswer(call -> {
                    if (!first.get()) { throw readback; }
                    return fixture.items.get(1);
                });
            }
            assertSame(failure, assertThrows(IllegalStateException.class, () -> commit(fixture.session(), 2)));
            assertEquals(63, fixture.items.get(0).getAmount());
            assertEquals(Material.ENCHANTED_BOOK, fixture.items.get(1).getType());
            assertEquals(900, Experience.total(fixture.level, fixture.progress));
            assertTrue(Arrays.stream(failure.getSuppressed())
                    .anyMatch(suppressed -> suppressed.getMessage().contains("were not restored")));
            if (unreadable) { assertTrue(Arrays.asList(failure.getSuppressed()).contains(readback)); }
            verify(fixture.player, never()).setTotalExperience(1000);
        }
    }

    @Test
    void stackedBookRollbackAttemptsAllSettersAndRetainsEveryIndependentFailure() {
        fixture.items.put(0, new ItemStack(Material.BOOK, 2));
        IllegalStateException failure = new IllegalStateException("result publication");
        AtomicBoolean publishing = new AtomicBoolean(true);
        doAnswer(call -> {
            if (!publishing.get()) { throw new IllegalStateException("source rollback"); }
            fixture.items.put(0, call.getArgument(1));
            return null;
        }).when(fixture.inventory).setItem(eq(0), nullable(ItemStack.class));
        doAnswer(call -> {
            if (publishing.getAndSet(false)) {
                fixture.items.put(1, call.getArgument(1));
                throw failure;
            }
            fixture.items.put(1, call.getArgument(1));
            throw new IllegalStateException("destination rollback");
        }).when(fixture.inventory).setItem(eq(1), nullable(ItemStack.class));
        doAnswer(call -> {
            if (!publishing.get()) { throw new IllegalStateException("level rollback"); }
            fixture.level = call.getArgument(0);
            return null;
        }).when(fixture.player).setLevel(anyInt());
        doAnswer(call -> {
            if (!publishing.get()) { throw new IllegalStateException("progress rollback"); }
            fixture.progress = call.getArgument(0);
            return null;
        }).when(fixture.player).setExp(anyFloat());
        doAnswer(call -> {
            if (!publishing.get()) { throw new IllegalStateException("total rollback"); }
            fixture.totalScore = call.getArgument(0);
            return null;
        }).when(fixture.player).setTotalExperience(anyInt());
        EnchantSession session = fixture.session();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> commit(session, 2)));
        assertEquals(5, failure.getSuppressed().length);
        assertEquals("destination rollback", failure.getSuppressed()[0].getMessage());
        assertEquals("source rollback", failure.getSuppressed()[4].getMessage());
        assertNull(fixture.items.get(1));
        assertFalse(commit(session, 2));
    }

    @Test
    void plainBookRollbackRestoresOriginalTypeAfterPublicationOrXpFailureAndCannotRetry() {
        for (boolean failXp : new boolean[]{false, true}) {
            ItemStack original = new ItemStack(Material.BOOK);
            fixture.items.put(0, original);
            fixture.balance(1000);
            EnchantSession session = fixture.session();
            AtomicBoolean once = new AtomicBoolean(true);
            if (failXp) {
                doAnswer(call -> {
                    if (once.getAndSet(false)) { throw new IllegalStateException("XP failed"); }
                    fixture.level = call.getArgument(0);
                    return null;
                }).when(fixture.player).setLevel(anyInt());
            } else {
                doAnswer(call -> {
                    fixture.items.put(call.getArgument(0), call.getArgument(1));
                    if (once.getAndSet(false)) { throw new IllegalStateException("publish failed"); }
                    return null;
                }).when(fixture.inventory).setItem(anyInt(), any(ItemStack.class));
            }
            assertThrows(IllegalStateException.class, () -> commit(session, 2));
            assertEquals(Material.BOOK, fixture.items.get(0).getType());
            assertEquals(original, fixture.items.get(0));
            assertEquals(1000, Experience.total(fixture.level, fixture.progress));
            assertFalse(commit(session, 2));
        }
    }

    @Test
    void bookCapsSurviveConversionWithPerEnchantEnchantedBookOverrideAndGlobalDisable() throws Exception {
        Settings settings = Settings.read(PricesTest.yaml("base-xp: 25\nmax-cost: 1000000\n" +
                "enchantments:\n  DURABILITY:\n    enabled: false\nitems:\n" +
                "  BOOK:\n    max-levels:\n      DAMAGE_ALL: 8\n      DAMAGE_UNDEAD: 8\n      DURABILITY: 9\n" +
                "  ENCHANTED_BOOK:\n    max-levels:\n      DAMAGE_UNDEAD: 2\n"));
        fixture.items.put(0, new ItemStack(Material.BOOK));
        fixture.balance(10000);
        EnchantTransaction configured = new EnchantTransaction(settings.prices, settings.policy);
        assertTrue(configured.commit(fixture.player, fixture.session(), ServerFixture.SHARPNESS, 6, () -> true));
        assertTrue(Enchantments.supported(fixture.items.get(0), settings.policy));
        assertTrue(configured.commit(fixture.player, fixture.session(), ServerFixture.SHARPNESS, 8, () -> true));
        assertEquals(8400, Experience.total(fixture.level, fixture.progress));
        assertEquals(8, settings.policy.maximum(Material.ENCHANTED_BOOK, ServerFixture.SHARPNESS));
        assertEquals(2, settings.policy.maximum(Material.ENCHANTED_BOOK, ServerFixture.SMITE));
        assertEquals(0, settings.policy.maximum(Material.ENCHANTED_BOOK, ServerFixture.UNBREAKING));
    }

    @Test
    void plainBookPurchasePreservesMetadataAndUpgradesStoredEnchantmentsWithCredit() {
        ItemStack original = new ItemStack(Material.BOOK);
        ItemMeta meta = original.getItemMeta();
        meta.setDisplayName("Trading book");
        meta.setLore(Arrays.asList("Keep this lore"));
        original.setItemMeta(meta);
        fixture.items.put(0, original);
        assertTrue(Enchantments.supported(original));
        assertTrue(commit(fixture.session(), 2));
        ItemStack purchased = fixture.items.get(0);
        assertEquals(Material.BOOK, original.getType());
        assertEquals(Material.ENCHANTED_BOOK, purchased.getType());
        assertEquals("Trading book", purchased.getItemMeta().getDisplayName());
        assertEquals(Arrays.asList("Keep this lore"), purchased.getItemMeta().getLore());
        assertEquals(2, ((EnchantmentStorageMeta) purchased.getItemMeta())
                .getStoredEnchantLevel(ServerFixture.SHARPNESS));
        assertTrue(commit(fixture.session(), 3));
        assertEquals(775, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void commitChargesRawPointsOnceAndPreservesOriginalMetadataOnClone() {
        ItemStack original = fixture.items.get(0);
        original.setDurability((short) 123);
        ItemMeta meta = original.getItemMeta();
        meta.setDisplayName("Named sword");
        meta.setLore(Arrays.asList("Kept lore"));
        original.setItemMeta(meta);
        original.addEnchantment(ServerFixture.UNBREAKING, 1);
        EnchantSession session = fixture.session();
        assertTrue(commit(session, 2));
        assertEquals(900, Experience.total(fixture.level, fixture.progress));
        assertEquals(900, fixture.totalScore);
        assertEquals(2, fixture.items.get(0).getEnchantmentLevel(ServerFixture.SHARPNESS));
        assertEquals(1, fixture.items.get(0).getEnchantmentLevel(ServerFixture.UNBREAKING));
        assertEquals(123, fixture.items.get(0).getDurability());
        assertEquals("Named sword", fixture.items.get(0).getItemMeta().getDisplayName());
        assertEquals(Arrays.asList("Kept lore"), fixture.items.get(0).getItemMeta().getLore());
        assertEquals(0, original.getEnchantmentLevel(ServerFixture.SHARPNESS));
        assertFalse(commit(session, 2));
        assertEquals(1, fixture.writes);
        verify(fixture.player, never()).getTotalExperience();
    }

    @Test
    void commitRejectsUnselectedArmorAndOffhandSourcesEvenWhenContextClaimsValid() {
        EnchantSession unselected = new EnchantSession(fixture.player.getUniqueId(), 0, fixture.block.getLocation());
        assertFalse(commit(unselected, 2));
        for (int slot : new int[] {-1, 36, 40}) {
            fixture.items.put(slot, new ItemStack(Material.DIAMOND_SWORD));
            EnchantSession invalid = new EnchantSession(fixture.player.getUniqueId(), 0, slot,
                    fixture.items.get(slot), fixture.block.getLocation());
            assertFalse(commit(invalid, 2));
        }
        assertEquals(0, fixture.writes);
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void commitUsesCurrentBalanceAndAcceptsExactCostAtZeroRemainder() {
        EnchantSession session = fixture.session();
        fixture.balance(100);
        assertTrue(commit(session, 2));
        assertEquals(0, fixture.level);
        assertEquals(0, fixture.progress);
        assertEquals(0, fixture.totalScore);
    }

    @Test
    void commitRejectsInsufficientExperienceWithoutUsingLifetimeScore() {
        fixture.balance(99);
        assertFalse(commit(fixture.session(), 2));
        assertEquals(99, Experience.total(fixture.level, fixture.progress));
        assertEquals(0, fixture.writes);
        verify(fixture.player, never()).setTotalExperience(anyInt());
    }

    @Test
    void commitRejectsChangedSlotAmountDurabilityNameAndSessionOwnership() throws Exception {
        for (int change = 0; change < 6; change++) {
            fixture = new ServerFixture();
            EnchantSession session = fixture.session();
            if (change == 0) { when(fixture.inventory.getHeldItemSlot()).thenReturn(1); }
            if (change == 1) { fixture.items.get(0).setAmount(2); }
            if (change == 2) { fixture.items.get(0).setDurability((short) 1); }
            if (change == 3) {
                ItemMeta meta = fixture.items.get(0).getItemMeta();
                meta.setDisplayName("Changed");
                fixture.items.get(0).setItemMeta(meta);
            }
            if (change == 4) { when(fixture.player.getUniqueId()).thenReturn(UUID.randomUUID()); }
            if (change == 5) { fixture.items.remove(0); }
            assertFalse(commit(session, 2), "change " + change);
            assertEquals(0, fixture.writes);
        }
    }

    @Test
    void commitRejectsStaleContextAndInvalidatedSessionBeforeAnyWrite() {
        assertFalse(transaction.commit(fixture.player, fixture.session(), ServerFixture.SHARPNESS, 2, () -> false));
        EnchantSession session = fixture.session();
        session.invalidate();
        assertFalse(commit(session, 2));
        assertEquals(0, fixture.writes);
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void legalityRejectsBooksWrongItemUnsafeCustomAndBothDirectionsOfConflict() {
        assertTrue(Enchantments.legal(new ItemStack(Material.BOOK), ServerFixture.SHARPNESS, 1));
        assertTrue(Enchantments.legal(new ItemStack(Material.ENCHANTED_BOOK), ServerFixture.SHARPNESS, 1));
        assertFalse(Enchantments.legal(new ItemStack(Material.DIRT), ServerFixture.SHARPNESS, 1));
        assertFalse(Enchantments.legal(fixture.items.get(0), ServerFixture.CUSTOM, 1));
        ItemStack sword = fixture.items.get(0);
        sword.addEnchantment(ServerFixture.SMITE, 1);
        assertFalse(Enchantments.legal(sword, ServerFixture.SHARPNESS, 1));
        sword.removeEnchantment(ServerFixture.SMITE);
        sword.addEnchantment(ServerFixture.SHARPNESS, 1);
        assertFalse(Enchantments.legal(sword, ServerFixture.SMITE, 1));
        assertFalse(Enchantments.legal(sword, ServerFixture.SHARPNESS, 1));
        assertTrue(Enchantments.legal(sword, ServerFixture.SHARPNESS, 2));
        sword.addEnchantment(ServerFixture.CUSTOM, 1);
        assertFalse(Enchantments.supported(sword));
        sword.removeEnchantment(ServerFixture.CUSTOM);
        sword.addUnsafeEnchantment(ServerFixture.SHARPNESS, 6);
        assertFalse(Enchantments.supported(sword));
    }

    @Test
    void commitRejectsIllegalLevelAndOverLimitPriceAndLeavesMaximumBalanceIntact() throws Exception {
        fixture.balance(Integer.MAX_VALUE);
        assertFalse(commit(fixture.session(), 6));
        assertFalse(commit(fixture.session(), 0));
        Prices capped = Prices.read(PricesTest.yaml("base-xp: 25\nmax-cost: 25\n"));
        assertFalse(new EnchantTransaction(capped).commit(fixture.player, fixture.session(), ServerFixture.SHARPNESS, 2, () -> true));
        assertEquals(Integer.MAX_VALUE, Experience.total(fixture.level, fixture.progress));
        assertTrue(commit(fixture.session(), 1));
        assertEquals(Integer.MAX_VALUE - 25, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void commitRollsBackExperienceAndItemIfPublishingFailsAndCannotRetry() {
        EnchantSession session = fixture.session();
        AtomicBoolean fail = new AtomicBoolean(true);
        doAnswer(call -> {
            if (fail.getAndSet(false)) { throw new IllegalStateException("simulated write failure"); }
            fixture.items.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(fixture.inventory).setItem(anyInt(), any(ItemStack.class));
        assertThrows(IllegalStateException.class, () -> commit(session, 2));
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
        assertEquals(1000, fixture.totalScore);
        assertEquals(0, fixture.items.get(0).getEnchantmentLevel(ServerFixture.SHARPNESS));
        assertFalse(commit(session, 2));
    }

    @Test
    void failedXpRollbackStillAttemptsToRestoreTheItem() {
        AtomicBoolean firstXpWrite = new AtomicBoolean(true);
        doAnswer(call -> {
            if (firstXpWrite.getAndSet(false)) {
                fixture.progress = call.getArgument(0);
                return null;
            }
            throw new IllegalStateException("XP restoration failed");
        }).when(fixture.player).setExp(anyFloat());
        AtomicBoolean firstItemWrite = new AtomicBoolean(true);
        IllegalStateException failure = new IllegalStateException("publish failed after mutation");
        doAnswer(call -> {
            fixture.items.put(call.getArgument(0), call.getArgument(1));
            if (firstItemWrite.getAndSet(false)) { throw failure; }
            return null;
        }).when(fixture.inventory).setItem(anyInt(), any(ItemStack.class));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> commit(fixture.session(), 2)));
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(0, fixture.items.get(0).getEnchantmentLevel(ServerFixture.SHARPNESS));
        verify(fixture.player).setTotalExperience(1000);
    }

    @Test
    void repeatedIdenticalSetterExceptionDoesNotReplaceTheOriginalFailure() {
        IllegalStateException failure = new IllegalStateException("persistent setter failure");
        doThrow(failure).when(fixture.inventory).setItem(anyInt(), any(ItemStack.class));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> commit(fixture.session(), 2)));
        assertEquals(1000, Experience.total(fixture.level, fixture.progress));
    }

    @Test
    void raisedLevelUsesOnlyValidatedOriginalCloneAndExactExplicitQuote() throws Exception {
        Settings settings = Settings.read(PricesTest.yaml("base-xp: 1000\nmax-cost: 1000000\n" +
                "enchantments:\n  DAMAGE_ALL:\n    max-level: 9\n    levels:\n      '7': 49001\n"));
        ItemStack original = fixture.items.get(0);
        ItemMeta meta = original.getItemMeta();
        meta.setDisplayName("Unchanged name");
        meta.setLore(Arrays.asList("Unchanged lore"));
        original.setItemMeta(meta);
        original.setDurability((short) 43);
        fixture.balance(100000);
        EnchantTransaction configured = new EnchantTransaction(settings.prices, settings.policy);
        assertTrue(configured.commit(fixture.player, fixture.session(), ServerFixture.SHARPNESS, 7, () -> true));
        assertEquals(50999, Experience.total(fixture.level, fixture.progress));
        assertEquals(7, fixture.items.get(0).getEnchantmentLevel(ServerFixture.SHARPNESS));
        assertEquals(0, original.getEnchantmentLevel(ServerFixture.SHARPNESS));
        assertEquals("Unchanged name", fixture.items.get(0).getItemMeta().getDisplayName());
        assertEquals(Arrays.asList("Unchanged lore"), fixture.items.get(0).getItemMeta().getLore());
        assertEquals(43, fixture.items.get(0).getDurability());
    }

    @Test
    void transactionReusesItemCapDisabledAndExplicitCostPoliciesWithoutAnyGuiTrust() throws Exception {
        for (String optional : Arrays.asList(
                "items:\n  DIAMOND_SWORD:\n    max-levels:\n      DAMAGE_ALL: 0\n",
                "enchantments:\n  DAMAGE_ALL:\n    max-level: 7\nitems:\n  DIAMOND_SWORD:\n    max-levels:\n      DAMAGE_ALL: 3\n",
                "enchantments:\n  DAMAGE_ALL:\n    enabled: false\nitems:\n  DIAMOND_SWORD:\n    max-levels:\n      DAMAGE_ALL: 255\n",
                "enchantments:\n  DAMAGE_ALL:\n    base-xp: 1001\n")) {
            Settings settings = Settings.read(PricesTest.yaml("base-xp: 1\nmax-cost: 1000\n" + optional));
            EnchantTransaction configured = new EnchantTransaction(settings.prices, settings.policy);
            assertFalse(configured.commit(fixture.player, fixture.session(), ServerFixture.SHARPNESS, 4, () -> true));
            assertEquals(0, fixture.writes);
            assertEquals(1000, Experience.total(fixture.level, fixture.progress));
        }
    }

    @Test
    void disabledExistingVanillaEnchantSurvivesPurchaseOfDifferentEnchantment() throws Exception {
        Settings settings = Settings.read(PricesTest.yaml("base-xp: 25\nmax-cost: 1000000\n" +
                "enchantments:\n  DAMAGE_ALL:\n    enabled: false\n    max-level: 0\n"));
        fixture.items.get(0).addEnchantment(ServerFixture.SHARPNESS, 5);
        EnchantTransaction configured = new EnchantTransaction(settings.prices, settings.policy);
        assertTrue(configured.commit(fixture.player, fixture.session(), ServerFixture.UNBREAKING, 2, () -> true));
        assertEquals(5, fixture.items.get(0).getEnchantmentLevel(ServerFixture.SHARPNESS));
        assertEquals(2, fixture.items.get(0).getEnchantmentLevel(ServerFixture.UNBREAKING));
        assertEquals(900, Experience.total(fixture.level, fixture.progress));
    }

    private boolean commit(EnchantSession session, int level) {
        return transaction.commit(fixture.player, session, ServerFixture.SHARPNESS, level, () -> true);
    }
}
