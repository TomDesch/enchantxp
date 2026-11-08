package io.github.stealingdapenta.enchantxp.administration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * File-only worker operations. No Bukkit objects or YAML documents belong here.
 */
class ConfigFileStore {

    static final int MAX_BYTES = 1024 * 1024;
    private static final long UUID_EPOCH_OFFSET = 0x01b21dd213814000L;
    private static final long UUID_MAX_TIMESTAMP = 0x0fffffffffffffffL;
    private static final long UUID_TIME_LOW_MASK = 0xffffffffL;
    private static final long UUID_TIME_MID_MASK = 0xffffL;
    private static final long UUID_TIME_HIGH_MASK = 0xfffL;
    private static final long UUID_VERSION_ONE = 0x1000L;
    private static final long TIMESTAMP_UNITS_PER_MILLISECOND = 10_000L;
    private final Path file;

    ConfigFileStore(Path file) {
        this.file = file;
    }

    byte[] read() throws IOException {
        try (InputStream input = Files.newInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_BYTES) {
                    throw new IOException("config.yml exceeds 1 MiB");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                                     .onMalformedInput(CodingErrorAction.REPORT)
                                     .onUnmappableCharacter(CodingErrorAction.REPORT)
                                     .decode(ByteBuffer.wrap(bytes))
                                     .toString();
    }

    static byte[] hash(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256")
                                .digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    Path stage(String contents, byte[] expectedHash) throws IOException {
        byte[] bytes = contents.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Draft exceeds 1 MiB");
        }
        unchanged(expectedHash);
        Path staged = sibling(".stage-");
        writeNew(staged, bytes);
        return staged;
    }

    Path commit(Path staged, byte[] expectedHash, String contents, BooleanSupplier beginCommit) throws IOException {
        byte[] original = unchanged(expectedHash);
        long previousTimestamp = retainLatestBackup();
        Path backup = nextBackup(previousTimestamp);
        writeBackup(original, backup);
        if (!beginCommit.getAsBoolean()) {
            throw new IOException("Save cancelled before replacing config.yml.");
        }
        // No non-atomic fallback: an unsupported filesystem must leave the original in place.
        replace(staged);
        try {
            if (!Arrays.equals(read(), contents.getBytes(StandardCharsets.UTF_8))) {
                throw new IOException("File differs from the saved draft");
            }
        } catch (IOException | RuntimeException failure) {
            throw new SavedException("Saved file could not be verified; backup: " + backup.getFileName(), failure);
        }
        return backup;
    }

    private void writeBackup(byte[] original, Path backup) throws IOException {
        Path candidate = sibling(".stage-");
        writeNew(candidate, original);
        try {
            publishBackup(candidate, backup);
        } catch (IOException | RuntimeException failure) {
            cleanupOwned(candidate, failure);
            throw failure;
        }
    }

    /**
     * Acquires exclusive file ownership before entering cleanup scope; write and close failures remove only the acquired file.
     * Failed exclusive creation conveys no ownership, so a pre-existing collision must survive.
     */
    private void writeNew(Path candidate, byte[] bytes) throws IOException {
        OutputStream acquired = Files.newOutputStream(candidate, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try (OutputStream output = acquired) {
            output.write(bytes);
        } catch (IOException | RuntimeException failure) {
            cleanupOwned(candidate, failure);
            throw failure;
        }
    }

    private void cleanupOwned(Path candidate, Exception failure) {
        try {
            delete(candidate);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    void publishBackup(Path candidate, Path backup) throws IOException {
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to replace an existing config backup: " + backup.getFileName());
        }
        Files.move(candidate, backup, StandardCopyOption.ATOMIC_MOVE);
    }

    // Prune before creating, so failed/canceled attempts cannot grow an unbounded archive.
    private long retainLatestBackup() throws IOException {
        List<Backup> backups = new ArrayList<>();
        Path directory = file.getParent() == null ? file.toAbsolutePath()
                                                        .getParent() : file.getParent();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                UUID id = ownedBackup(child);
                if (id != null) {
                    BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (regular(attributes)) {
                        backups.add(new Backup(child, attributes, id));
                    }
                }
            }
            backups.sort(Comparator.comparingLong((Backup backup) -> backup.timestamp)
                                   .thenComparing(backup -> backup.attributes.creationTime())
                                   .thenComparing(backup -> backup.attributes.lastModifiedTime())
                                   .thenComparing(backup -> backup.path.getFileName()
                                                                       .toString())
                                   .reversed());
            for (int index = 1; index < backups.size(); index++) {
                Backup backup = backups.get(index);
                BasicFileAttributes current = Files.readAttributes(backup.path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!regular(current) || !Objects.equals(current.fileKey(), backup.attributes.fileKey())) {
                    throw new IOException("Backup changed during retention: " + backup.path.getFileName());
                }
                delete(backup.path);
            }
            return backups.isEmpty() ? 0 : backups.get(0).timestamp;
        } catch (IOException failure) {
            throw new IOException("Could not clean old config.yml backups; save was not started.", failure);
        }
    }

    private UUID ownedBackup(Path path) {
        String prefix = file.getFileName() + ".backup-";
        String name = path.getFileName()
                          .toString();
        if (!name.startsWith(prefix)) {
            return null;
        }
        String suffix = name.substring(prefix.length());
        try {
            UUID id = UUID.fromString(suffix);
            return id.toString().equals(suffix) ? id : null;
        } catch (IllegalArgumentException notOwned) {
            return null;
        }
    }

    /**
     * Version-one UUID timestamps persist save order across reopened stores, timestamp collisions and clock rollback.
     * Newly sequenced generations sort ahead of legacy random UUID backups; filesystem times order legacy generations.
     */
    private Path nextBackup(long previousTimestamp) throws IOException {
        if (previousTimestamp == UUID_MAX_TIMESTAMP) {
            throw new IOException("Config backup sequence is exhausted.");
        }
        long timestamp = Math.max(previousTimestamp + 1,
                UUID_EPOCH_OFFSET + System.currentTimeMillis() * TIMESTAMP_UNITS_PER_MILLISECOND);
        if (timestamp > UUID_MAX_TIMESTAMP || timestamp <= 0) {
            throw new IOException("Config backup timestamp is outside the UUID range.");
        }
        long timeFields = ((timestamp & UUID_TIME_LOW_MASK) << 32) |
                (((timestamp >>> 32) & UUID_TIME_MID_MASK) << 16) |
                ((timestamp >>> 48) & UUID_TIME_HIGH_MASK) | UUID_VERSION_ONE;
        UUID id = new UUID(timeFields, UUID.randomUUID().getLeastSignificantBits());
        return file.resolveSibling(file.getFileName() + ".backup-" + id);
    }

    private static boolean regular(BasicFileAttributes attributes) {
        return attributes.isRegularFile() && !attributes.isSymbolicLink() && !attributes.isOther();
    }

    private static final class Backup {

        private final Path path;
        private final BasicFileAttributes attributes;
        private final long timestamp;

        private Backup(Path path, BasicFileAttributes attributes, UUID id) {
            this.path = path;
            this.attributes = attributes;
            this.timestamp = id.version() == 1 && id.variant() == 2 ? id.timestamp() : 0;
        }
    }

    void replace(Path staged) throws IOException {
        Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private byte[] unchanged(byte[] expectedHash) throws IOException {
        byte[] bytes = read();
        if (!Arrays.equals(hash(bytes), expectedHash)) {
            throw new IOException("config.yml changed on disk. Discard this draft and reopen /enchantxp config.");
        }
        return bytes;
    }

    void delete(Path staged) throws IOException {
        if (staged != null) {
            Files.deleteIfExists(staged);
        }
    }

    private Path sibling(String suffix) {
        return file.resolveSibling(file.getFileName() + suffix + UUID.randomUUID());
    }

    static final class SavedException extends IOException {

        SavedException(String message, Exception cause) {
            super(message, cause);
        }
    }
}
