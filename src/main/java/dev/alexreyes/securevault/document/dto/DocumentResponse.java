package dev.alexreyes.securevault.document.dto;

import dev.alexreyes.securevault.document.entity.Document;

import java.time.Instant;
import java.util.UUID;

/** Document metadata for clients - deliberately excludes the storage key and owner id. */
public record DocumentResponse(UUID id, String filename, String contentType, long sizeBytes, Instant createdAt) {

    public static DocumentResponse from(Document document) {
        return new DocumentResponse(document.getId(), document.getOriginalFilename(), document.getContentType(),
                document.getSizeBytes(), document.getCreatedAt());
    }
}
