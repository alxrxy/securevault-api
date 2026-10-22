package dev.alexreyes.securevault.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Writes in its own transaction (REQUIRES_NEW) so that a record of a
     * failed or denied action commits even when the caller's transaction
     * rolls back - a same-transaction insert would vanish with the failure
     * it's meant to record.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, AuditOutcome outcome, UUID actorId,
                       String resourceType, UUID resourceId, String ip) {
        auditLogRepository.save(new AuditLog(action, outcome, actorId, resourceType, resourceId, ip));
    }
}
