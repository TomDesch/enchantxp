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
        try {
            Files.write(staged, bytes, StandardOpenOption.CREATE_NEW);
            return staged;
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(staged);
            throw failure;
        }
    }

    Path commit(Path staged, byte[] expectedHash, String contents, BooleanSupplier beginCommit) throws IOException {
        byte[] original = unchanged(expectedHash);
        retainLatestBackup();
        Path backup = sibling(".backup-");
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
        boolean created = false;
        try {
            try (OutputStream output = Files.newOutputStream(candidate, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                output.write(original);
            }
            publishBackup(candidate, backup);
        } catch (IOException failure) {
            if (created) {
                try {
                    delete(candidate);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    void publishBackup(Path candidate, Path backup) throws IOException {
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to replace an existing config backup: " + backup.getFileName());
        }
        Files.move(candidate, backup, StandardCopyOption.ATOMIC_MOVE);
    }

    // Prune before creating, so failed/cancelled attempts cannot grow an unbounded archive.
    private void retainLatestBackup() throws IOException {
        List<Backup> backups = new ArrayList<>();
        Path directory = file.getParent() == null ? file.toAbsolutePath()
                                                        .getParent() : file.getParent();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (ownedBackup(child)) {
                    BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (regular(attributes)) {
                        backups.add(new Backup(child, attributes));
                    }
                }
            }
            backups.sort(Comparator.comparing((Backup backup) -> backup.attributes.creationTime())
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
        } catch (IOException failure) {
            throw new IOException("Could not clean old config.yml backups; save was not started.", failure);
        }
    }

    private boolean ownedBackup(Path path) {
        String prefix = file.getFileName() + ".backup-";
        String name = path.getFileName()
                          .toString();
        if (!name.startsWith(prefix)) {
            return false;
        }
        String suffix = name.substring(prefix.length());
        try {
            return UUID.fromString(suffix)
                       .toString()
                       .equals(suffix);
        } catch (IllegalArgumentException notOwned) {
            return false;
        }
    }

    private static boolean regular(BasicFileAttributes attributes) {
        return attributes.isRegularFile() && !attributes.isSymbolicLink() && !attributes.isOther();
    }

    private static final class Backup {

        private final Path path;
        private final BasicFileAttributes attributes;

        private Backup(Path path, BasicFileAttributes attributes) {
            this.path = path;
            this.attributes = attributes;
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
