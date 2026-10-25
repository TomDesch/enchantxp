package io.github.stealingdapenta.enchantxp.administration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigFileStoreTest {
    private Path directory;
    private Path file;
    private ConfigFileStore store;

    @BeforeEach
    void setUp() throws IOException {
        directory = Files.createDirectory(Paths.get("target", "backup-test-" + UUID.randomUUID()));
        file = directory.resolve("config.yml");
        Files.write(file, bytes("# original\r\nconfig-version: 1\r\n"));
        store = spy(new ConfigFileStore(file));
    }

    @AfterEach
    void cleanUp() throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.collect(Collectors.toList())) {
                Files.delete(child);
            }
        }
        Files.delete(directory);
    }

    @Test
    void repeatedSavesRetainOnlyTwoNewestExactGenerationsAndIgnoreUnownedEntries() throws Exception {
        Path unrelated = directory.resolve("config.yml.backup-not-owned");
        Files.write(unrelated, bytes("operator archive"));
        Path nonregular = Files.createDirectory(directory.resolve("config.yml.backup-" + UUID.randomUUID()));
        List<Path> history = new ArrayList<>();
        for (int generation = 0; generation < 6; generation++) {
            byte[] before = Files.readAllBytes(file);
            byte[] hash = ConfigFileStore.hash(before);
            String next = "# generation " + generation + "\r\nconfig-version: 1\r\n";
            Path stage = store.stage(next, hash);
            Path backup = store.commit(stage, hash, next, () -> true);
            history.add(backup);
            assertArrayEquals(before, Files.readAllBytes(backup));
            assertTrue(backups().size() <= 2);
            if (generation > 1) {
                assertFalse(Files.exists(history.get(generation - 2)));
            }
        }
        assertTrue(Files.exists(history.get(4)));
        assertTrue(Files.exists(history.get(5)));
        assertEquals("operator archive", new String(Files.readAllBytes(unrelated), StandardCharsets.UTF_8));
        assertTrue(Files.isDirectory(nonregular));
    }

    @Test
    void cancellationAndAtomicReplaceFailuresNeverAccumulateUnboundedBackups() throws Exception {
        byte[] original = Files.readAllBytes(file);
        byte[] hash = ConfigFileStore.hash(original);
        for (int attempt = 0; attempt < 8; attempt++) {
            Path staged = store.stage("new", hash);
            if (attempt >= 4) {
                doThrow(new IOException("atomic replacement unavailable")).when(store).replace(staged);
            }
            boolean begin = attempt >= 4;
            assertThrows(IOException.class, () -> store.commit(staged, hash, "new", () -> begin));
            store.delete(staged);
            assertTrue(backups().size() <= 2);
            for (Path backup : backups()) {
                assertArrayEquals(original, Files.readAllBytes(backup));
            }
            assertArrayEquals(original, Files.readAllBytes(file));
        }
    }

    @Test
    void cleanupFailureIsExplicitAndPreventsNewBackupOrReplacement() throws Exception {
        byte[] hash = ConfigFileStore.hash(Files.readAllBytes(file));
        Path firstStage = store.stage("first", hash);
        Path oldest = store.commit(firstStage, hash, "first", () -> true);
        hash = ConfigFileStore.hash(Files.readAllBytes(file));
        Path secondStage = store.stage("second", hash);
        Path latest = store.commit(secondStage, hash, "second", () -> true);
        doThrow(new IOException("cleanup denied")).when(store).delete(oldest);
        byte[] finalHash = ConfigFileStore.hash(Files.readAllBytes(file));
        Path staged = store.stage("third", finalHash);
        IOException failure = assertThrows(IOException.class,
                () -> store.commit(staged, finalHash, "third", () -> true));
        assertTrue(failure.getMessage().contains("backup"));
        assertEquals(2, backups().size());
        assertTrue(Files.exists(latest));
        assertEquals("second", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    private List<Path> backups() throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            return children.filter(path -> path.getFileName().toString().matches("config\\.yml\\.backup-[0-9a-f-]{36}"))
                    .filter(Files::isRegularFile).collect(Collectors.toList());
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
