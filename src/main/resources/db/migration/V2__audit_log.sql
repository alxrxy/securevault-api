-- Security audit trail. actor_id/resource_id deliberately have no foreign
-- keys: audit records must outlive the users and documents they describe
-- (app_users deletes would otherwise cascade or block).
CREATE TABLE audit_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_id      UUID,
    action        VARCHAR(50) NOT NULL,
    resource_type VARCHAR(50),
    resource_id   UUID,
    outcome       VARCHAR(20) NOT NULL,
    ip            VARCHAR(45)
);

CREATE INDEX idx_audit_log_actor_id ON audit_log (actor_id);
CREATE INDEX idx_audit_log_occurred_at ON audit_log (occurred_at);
