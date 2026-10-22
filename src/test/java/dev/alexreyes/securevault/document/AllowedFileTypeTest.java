package dev.alexreyes.securevault.document;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AllowedFileTypeTest {

    static final byte[] PDF_BYTES = "%PDF-1.7\n%âã\n1 0 obj".getBytes(StandardCharsets.ISO_8859_1);
    static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    static final byte[] JPEG_BYTES = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};

    @Test
    void detectsAllowedTypesFromMagicBytes() {
        assertThat(AllowedFileType.detect(PDF_BYTES)).contains(AllowedFileType.PDF);
        assertThat(AllowedFileType.detect(PNG_BYTES)).contains(AllowedFileType.PNG);
        assertThat(AllowedFileType.detect(JPEG_BYTES)).contains(AllowedFileType.JPEG);
    }

    @Test
    void rejectsEverythingElse() {
        assertThat(AllowedFileType.detect("<html><script>alert(1)</script>".getBytes())).isEmpty();
        assertThat(AllowedFileType.detect("GIF89a....".getBytes())).isEmpty();
        assertThat(AllowedFileType.detect(new byte[] {'P', 'K', 3, 4})).isEmpty(); // zip / docx
        assertThat(AllowedFileType.detect(" %PDF-1.7".getBytes())).isEmpty();
        assertThat(AllowedFileType.detect(new byte[0])).isEmpty();
    }

    @Test
    void rejectsTruncatedSignatures() {
        assertThat(AllowedFileType.detect("%PDF".getBytes())).isEmpty();
        assertThat(AllowedFileType.detect(new byte[] {(byte) 0x89, 'P', 'N', 'G'})).isEmpty();
        assertThat(AllowedFileType.detect(new byte[] {(byte) 0xFF, (byte) 0xD8})).isEmpty();
    }
}
