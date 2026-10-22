package dev.alexreyes.securevault.storage;

/**
 * Storage failure or a key that resolves outside the storage root. The
 * message is for server logs only; clients get a generic 500.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
