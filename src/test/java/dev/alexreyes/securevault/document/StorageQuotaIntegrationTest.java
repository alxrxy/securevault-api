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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs with a 1 KB per-user quota so the boundaries are cheap to hit. */
@TestPropertySource(properties = "app.storage.user-quota=1KB")
class StorageQuotaIntegrationTest extends IntegrationTestBase {

    private static final int QUOTA = 1024;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void uploadsMayFillQuotaExactlyThenAreRejectedWith409() throws Exception {
        String token = newUserToken();

        upload(token, 600).andExpect(status().isCreated());
        upload(token, QUOTA - 600).andExpect(status().isCreated()); // exactly at quota
        upload(token, 10)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message", containsString("storage quota")))
                .andExpect(jsonPath("$.message", containsString("1024 of 1024 bytes used")));

        mockMvc.perform(get("/api/documents").header("Authorization", bearer(token)))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void singleFileLargerThanQuotaIsRejected() throws Exception {
        upload(newUserToken(), QUOTA + 1).andExpect(status().isConflict());
    }

    @Test
    void deletingADocumentFreesItsQuota() throws Exception {
        String token = newUserToken();
        UUID big = uploadedId(upload(token, 1000).andExpect(status().isCreated()));
        upload(token, 100).andExpect(status().isConflict());

        mockMvc.perform(delete("/api/documents/" + big).header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        upload(token, 100).andExpect(status().isCreated());
    }

    @Test
    void quotaIsPerUser() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        upload(alice, QUOTA).andExpect(status().isCreated());

        upload(bob, QUOTA).andExpect(status().isCreated());
        upload(alice, 10).andExpect(status().isConflict());
    }

    @Test
    void rejectedUploadLeavesNoFileAndIsAudited() throws Exception {
        String token = newUserToken();
        UUID ownerId = currentUserId(token);
        upload(token, QUOTA).andExpect(status().isCreated());
        String ip = randomIp();

        mockMvc.perform(multipart("/api/documents").file(pdf(10))
                        .header("Authorization", bearer(token)).with(fromIp(ip)))
                .andExpect(status().isConflict());

        try (var files = Files.list(STORAGE_ROOT.resolve(ownerId.toString()))) {
            assertThat(files.count()).isEqualTo(1);
        }
        assertThat(auditLogRepository.findByIp(ip))
                .extracting(AuditLog::getAction, AuditLog::getOutcome)
                .containsExactly(tuple(AuditAction.DOCUMENT_UPLOAD, AuditOutcome.FAILURE));
    }

    /**
     * Without the per-user row lock, concurrent uploads can all pass the
     * quota check before any of them inserts, overshooting the quota.
     */
    @Test
    void concurrentUploadsCannotOvershootQuota() throws Exception {
        String token = newUserToken();
        UUID ownerId = currentUserId(token);
        int threads = 8;
        int size = 300; // 3 fit in 1024 bytes, 4 don't
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Integer> call = () -> {
                    start.await();
                    return upload(token, size).andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }

            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(3);
        } finally {
            pool.shutdownNow();
        }

        Path ownerDir = STORAGE_ROOT.resolve(ownerId.toString());
        try (var files = Files.list(ownerDir)) {
            assertThat(files.count()).isEqualTo(3);
        }
    }

    private ResultActions upload(String token, int size) throws Exception {
        return mockMvc.perform(multipart("/api/documents").file(pdf(size)).header("Authorization", bearer(token)));
    }

    private static UUID uploadedId(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id"));
    }

    private UUID currentUserId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/users/me").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private static MockMultipartFile pdf(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) ' ');
        byte[] header = AllowedFileTypeTest.PDF_BYTES;
        System.arraycopy(header, 0, bytes, 0, Math.min(header.length, size));
        return new MockMultipartFile("file", "q.pdf", "application/pdf", bytes);
    }
}
