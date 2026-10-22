package dev.alexreyes.securevault.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LocalFilesystemStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void storeLoadDeleteRoundTrip() throws Exception {
        LocalFilesystemStorageService storage = new LocalFilesystemStorageService(tempDir.resolve("root").toString());

        storage.store("owner/doc.pdf", bytes("hello"));

        try (InputStream in = storage.load("owner/doc.pdf").getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo("hello".getBytes());
        }
        assertThat(tempDir.resolve("root/owner/doc.pdf")).exists();

        storage.delete("owner/doc.pdf");
        assertThat(tempDir.resolve("root/owner/doc.pdf")).doesNotExist();
        storage.delete("owner/doc.pdf"); // idempotent
    }

    @Test
    void storeRefusesToOverwrite() {
        LocalFilesystemStorageService storage = new LocalFilesystemStorageService(tempDir.toString());
        storage.store("owner/doc.pdf", bytes("first"));

        assertThatThrownBy(() -> storage.store("owner/doc.pdf", bytes("second")))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void traversalAndAbsoluteKeysAreRejected() throws Exception {
        Path root = tempDir.resolve("root");
        LocalFilesystemStorageService storage = new LocalFilesystemStorageService(root.toString());
        Files.writeString(tempDir.resolve("secret.txt"), "secret");

        for (String key : new String[] {"../secret.txt", "owner/../../secret.txt", "..", "",
                tempDir.resolve("secret.txt").toAbsolutePath().toString()}) {
            assertThatThrownBy(() -> storage.load(key)).as(key).isInstanceOf(StorageException.class);
            assertThatThrownBy(() -> storage.store(key, bytes("x"))).as(key).isInstanceOf(StorageException.class);
            assertThatThrownBy(() -> storage.delete(key)).as(key).isInstanceOf(StorageException.class);
        }
        assertThat(Files.readString(tempDir.resolve("secret.txt"))).isEqualTo("secret");
    }

    @Test
    void symlinkInsideRootPointingOutsideIsRejected() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("secret.pdf"), "secret");
        assumeTrue(tryCreateSymlink(root.resolve("owner"), outside),
                "could not create a symlink or junction");
        LocalFilesystemStorageService storage = new LocalFilesystemStorageService(root.toString());

        assertThatThrownBy(() -> storage.load("owner/secret.pdf")).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> storage.store("owner/new.pdf", bytes("x"))).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> storage.delete("owner/secret.pdf")).isInstanceOf(StorageException.class);
        assertThat(outside.resolve("new.pdf")).doesNotExist();
        assertThat(outside.resolve("secret.pdf")).exists();
    }

    @Test
    void missingFileFailsToLoad() {
        LocalFilesystemStorageService storage = new LocalFilesystemStorageService(tempDir.toString());

        assertThatThrownBy(() -> storage.load("owner/missing.pdf")).isInstanceOf(StorageException.class);
    }

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes());
    }

    /**
     * Real symlink where permitted (Linux/macOS, CI). Unprivileged Windows
     * accounts can't create symlinks, so fall back to a directory junction,
     * which toRealPath() resolves the same way.
     */
    private static boolean tryCreateSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
                return false;
            }
        }
        try {
            Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    link.toAbsolutePath().toString(), target.toAbsolutePath().toString())
                    .redirectErrorStream(true).start();
            mklink.getInputStream().readAllBytes();
            return mklink.waitFor() == 0 && Files.exists(link);
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
