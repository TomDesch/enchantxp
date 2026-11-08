package local.enchantqa;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import local.enchantqa.EnchantQA.ConfigOwnership;

public final class OwnershipRegression {
    private static final byte[] ORIGINAL = bytes("# original\r\nconfig-version: 1\r\nbase-xp: 25000\r\n");
    private static final byte[] FIXTURE = bytes("config-version: 1\nbase-xp: 7\n");
    private static final byte[] OWNER = bytes("# original\r\nconfig-version: 1\r\nbase-xp: 25000\r\n# owner edit after SUMMARY\r\n");

    public static void main(String[] args) throws Exception {
        Path directory = Files.createDirectory(Paths.get(args[0]));
        Path file = directory.resolve("config.yml");
        try {
            Files.write(file, ORIGINAL);
            ConfigOwnership completed = new ConfigOwnership(file);
            completed.writeFixture(FIXTURE);
            completed.writeFixture(ORIGINAL);
            completed.releaseRestored();
            Files.write(file, OWNER);
            completed.restoreIfOwned();
            exact(file, OWNER);
            System.out.println("PASS: owner edit after verified phase4 survives shutdown cleanup");
            try {
                completed.writeFixture(FIXTURE);
                throw new AssertionError("Released ownership accepted another fixture write");
            } catch (IllegalStateException expected) {
                exact(file, OWNER);
            }

            Files.write(file, ORIGINAL);
            ConfigOwnership partial = new ConfigOwnership(file);
            partial.writeFixture(FIXTURE);
            partial.writeFixture(bytes("ui: [broken\n"));
            partial.restoreIfOwned();
            exact(file, ORIGINAL);
            System.out.println("PASS: interrupted run restores the exact original from its last fixture");

            ConfigOwnership external = new ConfigOwnership(file);
            external.writeFixture(FIXTURE);
            Files.write(file, OWNER);
            refused(() -> external.restoreIfOwned());
            refused(() -> external.writeFixture(ORIGINAL));
            exact(file, OWNER);
            System.out.println("PASS: partial-run external edit survives restoration and later fixture attempts");

            Files.write(file, ORIGINAL);
            ConfigOwnership unverified = new ConfigOwnership(file);
            unverified.writeFixture(FIXTURE);
            refused(() -> unverified.releaseRestored());
            unverified.restoreIfOwned();
            exact(file, ORIGINAL);
            System.out.println("PASS: failed final verification retains safe compensation ownership");

            ConfigOwnership untouched = new ConfigOwnership(file);
            Files.write(file, OWNER);
            refused(() -> untouched.writeFixture(FIXTURE));
            untouched.restoreIfOwned();
            exact(file, OWNER);
            System.out.println("PASS: failure before first fixture write never owns an operator edit");

            Files.write(file, ORIGINAL);
            ConfigOwnership normal = new ConfigOwnership(file);
            normal.writeFixture(FIXTURE);
            normal.writeFixture(ORIGINAL);
            normal.releaseRestored();
            normal.restoreIfOwned();
            normal.restoreIfOwned();
            exact(file, ORIGINAL);
            System.out.println("PASS: normal final restoration is byte-exact and cleanup is idempotent");

            ConfigOwnership removed = new ConfigOwnership(file);
            removed.writeFixture(FIXTURE);
            Files.delete(file);
            refused(() -> removed.restoreIfOwned());
            if (Files.exists(file)) { throw new AssertionError("Removed operator file was recreated"); }
            System.out.println("PASS: an externally removed file is not recreated");
            System.out.println("OWNERSHIP SUMMARY: 7 scenarios passed; no server or owner config accessed");
        } finally {
            Files.deleteIfExists(file);
            Files.delete(directory);
        }
    }

    private static void refused(IoAction action) throws IOException {
        try {
            action.run();
            throw new AssertionError("Unverified or externally changed bytes were accepted");
        } catch (IOException expected) {
            // The caller also checks that the external bytes remain unchanged.
        }
    }

    private static void exact(Path file, byte[] expected) throws IOException {
        if (!Arrays.equals(expected, Files.readAllBytes(file))) {
            throw new AssertionError("Config bytes differ");
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private interface IoAction {
        void run() throws IOException;
    }
}
