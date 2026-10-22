package dev.alexreyes.securevault.exception;

/**
 * Thrown both when a document doesn't exist and when it belongs to someone
 * else, so callers can't probe which document ids exist (BOLA/IDOR).
 */
public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException() {
        super("Document not found");
    }
}
