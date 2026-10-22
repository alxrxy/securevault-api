package dev.alexreyes.securevault.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One security-relevant event. Holds identifiers only - never tokens,
 * passwords, emails typed into a failed login, or file contents.
 */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    /** Who performed the action; null when unauthenticated (e.g. a failed login). */
    @Column(name = "actor_id")
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;

    @Column(name = "resource_type")
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditOutcome outcome;

    @Column
    private String ip;

    protected AuditLog() {
        // required by JPA
    }

    public AuditLog(AuditAction action, AuditOutcome outcome, UUID actorId,
                    String resourceType, UUID resourceId, String ip) {
        this.occurredAt = Instant.now();
        this.action = action;
        this.outcome = outcome;
        this.actorId = actorId;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.ip = ip;
    }

    public UUID getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public UUID getActorId() {
        return actorId;
    }

    public AuditAction getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public AuditOutcome getOutcome() {
        return outcome;
    }

    public String getIp() {
        return ip;
    }
}
