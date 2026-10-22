package dev.alexreyes.securevault.audit;

import dev.alexreyes.securevault.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuditLogIntegrationTest extends IntegrationTestBase {

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void successfulRegisterAndLoginAreRecordedWithActor() throws Exception {
        String ip = randomIp();
        String email = uniqueEmail();

        mockMvc.perform(postJson("/api/auth/register", credentialsJson(email, PASSWORD)).with(fromIp(ip)))
                .andExpect(status().isCreated());
        mockMvc.perform(postJson("/api/auth/login", credentialsJson(email, PASSWORD)).with(fromIp(ip)))
                .andExpect(status().isOk());

        List<AuditLog> entries = auditLogRepository.findByIp(ip);
        assertThat(entries).extracting(AuditLog::getAction, AuditLog::getOutcome)
                .containsExactlyInAnyOrder(
                        tuple(AuditAction.REGISTER, AuditOutcome.SUCCESS),
                        tuple(AuditAction.LOGIN, AuditOutcome.SUCCESS));
        UUID actorId = entries.getFirst().getActorId();
        assertThat(actorId).isNotNull();
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.getActorId()).isEqualTo(actorId);
            assertThat(entry.getResourceId()).isEqualTo(actorId);
            assertThat(entry.getOccurredAt()).isNotNull();
        });
    }

    @Test
    void duplicateRegistrationFailureSurvivesTransactionRollback() throws Exception {
        String email = uniqueEmail();
        register(email);
        String ip = randomIp();

        mockMvc.perform(postJson("/api/auth/register", credentialsJson(email, PASSWORD)).with(fromIp(ip)))
                .andExpect(status().isConflict());

        // register() is @Transactional and rolled back; the audit row was
        // written in its own REQUIRES_NEW transaction and must still exist.
        List<AuditLog> entries = auditLogRepository.findByIp(ip);
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.getAction()).isEqualTo(AuditAction.REGISTER);
            assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.FAILURE);
            assertThat(entry.getActorId()).isNull();
        });
    }

    @Test
    void failedLoginRecordsTargetedAccountButNoActor() throws Exception {
        String email = uniqueEmail();
        register(email);
        String ip = randomIp();

        mockMvc.perform(postJson("/api/auth/login", credentialsJson(email, "wrong-password-123")).with(fromIp(ip)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(postJson("/api/auth/login", credentialsJson(uniqueEmail(), PASSWORD)).with(fromIp(ip)))
                .andExpect(status().isUnauthorized());

        List<AuditLog> entries = auditLogRepository.findByIp(ip);
        assertThat(entries).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.getAction()).isEqualTo(AuditAction.LOGIN);
            assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.FAILURE);
            assertThat(entry.getActorId()).isNull();
        });
        assertThat(entries).filteredOn(entry -> entry.getResourceId() != null)
                .singleElement()
                .satisfies(entry -> assertThat(entry.getResourceType()).isEqualTo("USER"));
    }
}
