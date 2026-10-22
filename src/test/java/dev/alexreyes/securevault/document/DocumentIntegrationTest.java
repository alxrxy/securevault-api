package dev.alexreyes.securevault.document;

import com.jayway.jsonpath.JsonPath;
import dev.alexreyes.securevault.IntegrationTestBase;
import dev.alexreyes.securevault.audit.AuditAction;
import dev.alexreyes.securevault.audit.AuditLog;
import dev.alexreyes.securevault.audit.AuditLogRepository;
import dev.alexreyes.securevault.audit.AuditOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DocumentIntegrationTest extends IntegrationTestBase {

    private static final long MAX_BYTES = 25L * 1024 * 1024;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void uploadUsesSniffedTypeAndServerGeneratedPath() throws Exception {
        String token = newUserToken();
        UUID ownerId = currentUserId(token);

        // PNG bytes, but the client claims a PDF and supplies a traversal filename.
        String body = upload(token, file("../../etc/evil.pdf", "application/pdf", AllowedFileTypeTest.PNG_BYTES))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.filename").value("evil.pdf.png"))
                .andExpect(jsonPath("$.sizeBytes").value(AllowedFileTypeTest.PNG_BYTES.length))
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        UUID documentId = UUID.fromString(JsonPath.read(body, "$.id"));

        Path stored = STORAGE_ROOT.resolve(ownerId.toString()).resolve(documentId + ".png");
        assertThat(stored).exists();
        assertThat(Files.readAllBytes(stored)).isEqualTo(AllowedFileTypeTest.PNG_BYTES);
        try (var ownerDir = Files.list(stored.getParent())) {
            assertThat(ownerDir).containsExactly(stored);
        }
    }

    @Test
    void disallowedContentIsRejectedWhateverTheClaimedType() throws Exception {
        String token = newUserToken();

        upload(token, file("invoice.pdf", "application/pdf", "<html><script>alert(1)</script>".getBytes()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value("Only PDF, PNG, and JPEG files are allowed"));
        upload(token, file("pic.png", "image/png", "GIF89a......".getBytes()))
                .andExpect(status().isUnsupportedMediaType());
        upload(token, file("empty.pdf", "application/pdf", new byte[0]))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/documents").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void sizeLimitIs25MegabytesInclusive() throws Exception {
        String token = newUserToken();

        upload(token, file("max.pdf", "application/pdf", pdfOfSize(MAX_BYTES)))
                .andExpect(status().isCreated());
        upload(token, file("too-big.pdf", "application/pdf", pdfOfSize(MAX_BYTES + 1)))
                .andExpect(status().is(413));
    }

    @Test
    void listReturnsOnlyOwnDocumentsAndAllowsDuplicateFilenames() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        UUID first = uploadPdf(alice, "report.pdf");
        UUID second = uploadPdf(alice, "report.pdf");
        uploadPdf(bob, "bobs.pdf");

        String body = mockMvc.perform(get("/api/documents").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$[*].id");
        List<String> names = JsonPath.read(body, "$[*].filename");
        assertThat(ids).containsExactlyInAnyOrder(first.toString(), second.toString());
        assertThat(names).containsOnly("report.pdf");
    }

    @Test
    void otherUsersDocumentIsIndistinguishableFromMissing() throws Exception {
        String alice = newUserToken();
        String mallory = newUserToken();
        UUID aliceDoc = uploadPdf(alice, "private.pdf");
        UUID missing = UUID.randomUUID();

        for (UUID id : new UUID[] {aliceDoc, missing}) {
            mockMvc.perform(get("/api/documents/" + id).header("Authorization", bearer(mallory)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Document not found"));
            mockMvc.perform(get("/api/documents/" + id + "/download").header("Authorization", bearer(mallory)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Document not found"));
            mockMvc.perform(delete("/api/documents/" + id).header("Authorization", bearer(mallory)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("Document not found"));
        }

        // Mallory's delete attempt changed nothing.
        mockMvc.perform(get("/api/documents/" + aliceDoc + "/download").header("Authorization", bearer(alice)))
                .andExpect(status().isOk());
    }

    @Test
    void downloadIsAnAttachmentWithDetectedTypeAndNosniff() throws Exception {
        String token = newUserToken();
        byte[] pdf = pdfOfSize(1024);
        UUID id = uploadDocument(token, file("Quarterly \"report\"\r\nX-Evil: 1.pdf", "text/html", pdf));

        mockMvc.perform(get("/api/documents/" + id + "/download").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
                .andExpect(header().string("Content-Disposition", containsString("filename")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().doesNotExist("X-Evil"))
                .andExpect(content().bytes(pdf));

        mockMvc.perform(get("/api/documents/" + id).header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.filename").value("Quarterly \"report\"X-Evil: 1.pdf"));
    }

    @Test
    void deleteRemovesRowAndFile() throws Exception {
        String token = newUserToken();
        UUID ownerId = currentUserId(token);
        UUID id = uploadPdf(token, "gone.pdf");
        Path stored = STORAGE_ROOT.resolve(ownerId.toString()).resolve(id + ".pdf");
        assertThat(stored).exists();

        mockMvc.perform(delete("/api/documents/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        assertThat(stored).doesNotExist();
        mockMvc.perform(get("/api/documents/" + id).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void documentEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/documents")).andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/documents").file(file("a.pdf", "application/pdf", pdfOfSize(10))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/documents/" + UUID.randomUUID() + "/download")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/documents/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void malformedRequestsAreClientErrors() throws Exception {
        String token = newUserToken();

        mockMvc.perform(get("/api/documents/not-a-uuid").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/documents").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void documentEventsAreAudited() throws Exception {
        String alice = newUserToken();
        String mallory = newUserToken();
        UUID aliceId = currentUserId(alice);
        UUID malloryId = currentUserId(mallory);
        String aliceIp = randomIp();
        String malloryIp = randomIp();

        String body = mockMvc.perform(multipart("/api/documents")
                        .file(file("a.pdf", "application/pdf", pdfOfSize(10)))
                        .header("Authorization", bearer(alice)).with(fromIp(aliceIp)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        mockMvc.perform(get("/api/documents/" + id + "/download")
                        .header("Authorization", bearer(alice)).with(fromIp(aliceIp)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/documents/" + id + "/download")
                        .header("Authorization", bearer(mallory)).with(fromIp(malloryIp)))
                .andExpect(status().isNotFound());
        mockMvc.perform(multipart("/api/documents")
                        .file(file("x.pdf", "application/pdf", "not a pdf".getBytes()))
                        .header("Authorization", bearer(mallory)).with(fromIp(malloryIp)))
                .andExpect(status().isUnsupportedMediaType());
        mockMvc.perform(delete("/api/documents/" + id)
                        .header("Authorization", bearer(alice)).with(fromIp(aliceIp)))
                .andExpect(status().isNoContent());

        assertThat(auditLogRepository.findByIp(aliceIp))
                .extracting(AuditLog::getAction, AuditLog::getOutcome, AuditLog::getActorId, AuditLog::getResourceId)
                .containsExactlyInAnyOrder(
                        tuple(AuditAction.DOCUMENT_UPLOAD, AuditOutcome.SUCCESS, aliceId, id),
                        tuple(AuditAction.DOCUMENT_DOWNLOAD, AuditOutcome.SUCCESS, aliceId, id),
                        tuple(AuditAction.DOCUMENT_DELETE, AuditOutcome.SUCCESS, aliceId, id));
        assertThat(auditLogRepository.findByIp(malloryIp))
                .extracting(AuditLog::getAction, AuditLog::getOutcome, AuditLog::getActorId, AuditLog::getResourceId)
                .containsExactlyInAnyOrder(
                        tuple(AuditAction.DOCUMENT_DOWNLOAD, AuditOutcome.FAILURE, malloryId, id),
                        tuple(AuditAction.DOCUMENT_UPLOAD, AuditOutcome.FAILURE, malloryId, null));
    }

    private ResultActions upload(String token, MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart("/api/documents").file(file).header("Authorization", bearer(token)));
    }

    private UUID uploadDocument(String token, MockMultipartFile file) throws Exception {
        String body = upload(token, file).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID uploadPdf(String token, String filename) throws Exception {
        return uploadDocument(token, file(filename, "application/pdf", pdfOfSize(64)));
    }

    private UUID currentUserId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/users/me").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private static MockMultipartFile file(String filename, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", filename, contentType, bytes);
    }

    private static byte[] pdfOfSize(long size) {
        byte[] bytes = new byte[Math.toIntExact(size)];
        Arrays.fill(bytes, (byte) ' ');
        byte[] header = AllowedFileTypeTest.PDF_BYTES;
        System.arraycopy(header, 0, bytes, 0, Math.min(header.length, bytes.length));
        return bytes;
    }
}
