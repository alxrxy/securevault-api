package dev.alexreyes.securevault.document;

import java.util.Arrays;
import java.util.Optional;

/**
 * The only file types accepted for upload, identified by their leading
 * magic bytes. The stored content type and on-disk extension always come
 * from here - never from the client's Content-Type header or filename.
 */
public enum AllowedFileType {

    PDF("application/pdf", "pdf", new byte[] {'%', 'P', 'D', 'F', '-'}),
    PNG("image/png", "png", new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}),
    JPEG("image/jpeg", "jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});

    /** Enough leading bytes to identify every allowed type. */
    public static final int SNIFF_LENGTH = 8;

    private final String mediaType;
    private final String extension;
    private final byte[] magic;

    AllowedFileType(String mediaType, String extension, byte[] magic) {
        this.mediaType = mediaType;
        this.extension = extension;
        this.magic = magic;
    }

    public String mediaType() {
        return mediaType;
    }

    public String extension() {
        return extension;
    }

    /** Identifies the type from a file's leading bytes, or empty if it isn't an allowed type. */
    public static Optional<AllowedFileType> detect(byte[] leadingBytes) {
        return Arrays.stream(values())
                .filter(type -> leadingBytes.length >= type.magic.length
                        && Arrays.equals(leadingBytes, 0, type.magic.length, type.magic, 0, type.magic.length))
                .findFirst();
    }
}
