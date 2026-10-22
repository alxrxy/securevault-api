package dev.alexreyes.securevault.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Stores files on the local filesystem under {@code app.storage.root}.
 *
 * <p>Path safety: every key is resolved and then checked against the
 * <em>real</em> (symlink-resolved) storage root before any read, write, or
 * delete. Comparing real paths, not just normalized strings, also defends
 * against a symlink inside the root that points somewhere else - not only
 * against {@code ../} tricks. Writes additionally refuse to follow a
 * symlink at the final path component.
 */
@Service
public class LocalFilesystemStorageService implements StorageService {

    private final Path root;

    public LocalFilesystemStorageService(@Value("${app.storage.root}") String root) {
        try {
            Path configured = Path.of(root).toAbsolutePath().normalize();
            Files.createDirectories(configured);
            this.root = configured.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot initialize storage root: " + root, e);
        }
    }

    @Override
    public void store(String key, InputStream content) {
        Path target = resolveWithinRoot(key);
        try {
            Files.createDirectories(target.getParent());
            // The parent may itself be (or contain) a symlink; check where it really is.
            Path realParent = target.getParent().toRealPath();
            requireWithinRoot(realParent, key);
            Path realTarget = realParent.resolve(target.getFileName());
            try (OutputStream out = Files.newOutputStream(realTarget,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                content.transferTo(out);
            }
        } catch (IOException e) {
            throw new StorageException("Failed to store " + key, e);
        }
    }

    @Override
    public Resource load(String key) {
        Path real = realPathWithinRoot(key);
        if (!Files.isRegularFile(real)) {
            throw new StorageException("Not a regular file: " + key);
        }
        return new FileSystemResource(real);
    }

    @Override
    public void delete(String key) {
        Path target = resolveWithinRoot(key);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.deleteIfExists(realPathWithinRoot(key));
        } catch (IOException e) {
            throw new StorageException("Failed to delete " + key, e);
        }
    }

    /** Lexical check: rejects absolute keys and {@code ..} escapes before touching the disk. */
    private Path resolveWithinRoot(String key) {
        if (key == null || key.isBlank()) {
            throw new StorageException("Empty storage key");
        }
        Path relative = Path.of(key);
        if (relative.isAbsolute()) {
            throw new StorageException("Absolute storage key rejected: " + key);
        }
        Path resolved = root.resolve(relative).normalize();
        requireWithinRoot(resolved, key);
        return resolved;
    }

    /** Canonical check: follows symlinks and verifies the real location is still under the root. */
    private Path realPathWithinRoot(String key) {
        Path resolved = resolveWithinRoot(key);
        try {
            Path real = resolved.toRealPath();
            requireWithinRoot(real, key);
            return real;
        } catch (NoSuchFileException e) {
            throw new StorageException("File not found: " + key, e);
        } catch (IOException e) {
            throw new StorageException("Failed to resolve " + key, e);
        }
    }

    private void requireWithinRoot(Path path, String key) {
        if (!path.startsWith(root) || path.equals(root)) {
            throw new StorageException("Storage key escapes storage root: " + key);
        }
    }
}
