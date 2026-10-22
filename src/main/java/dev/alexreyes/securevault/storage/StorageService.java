package dev.alexreyes.securevault.storage;

import org.springframework.core.io.Resource;

import java.io.InputStream;

/**
 * Stores file bytes under opaque, server-generated keys (never derived from
 * user input). Authorization is not this layer's job: callers must check
 * document ownership in the database before calling any of these methods.
 */
public interface StorageService {

    /** Writes a new file; fails if the key already exists. */
    void store(String key, InputStream content);

    Resource load(String key);

    /** Deletes the file if present; a missing file is not an error. */
    void delete(String key);
}
