-- Source systems allowed to call the API. Only SHA-256 hashes of API keys are stored.
CREATE TABLE api_client (
    id            BIGSERIAL PRIMARY KEY,
    source_system VARCHAR(64) NOT NULL UNIQUE,
    key_hash      CHAR(64)    NOT NULL UNIQUE,
    active        BOOLEAN     NOT NULL DEFAULT TRUE
);

CREATE TABLE recipient (
    id           VARCHAR(64)  PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL
);

-- Per-recipient channel address and opt-out flag.
CREATE TABLE recipient_channel (
    recipient_id VARCHAR(64)  NOT NULL REFERENCES recipient (id),
    channel      VARCHAR(16)  NOT NULL CHECK (channel IN ('EMAIL', 'SMS', 'PUSH')),
    address      VARCHAR(320) NOT NULL,
    opted_out    BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (recipient_id, channel)
);

CREATE TABLE notification (
    id              UUID         PRIMARY KEY,
    source_system   VARCHAR(64)  NOT NULL,
    -- Cleared (set to NULL) by the retention job; NULLs do not collide in the unique constraint.
    idempotency_key TEXT,
    request_hash    CHAR(64),
    event_id        TEXT         NOT NULL,
    type            VARCHAR(16)  NOT NULL CHECK (type IN ('TRANSACTIONAL', 'ALERT', 'SECURITY', 'MARKETING')),
    severity        VARCHAR(16)  NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    priority        VARCHAR(16)  NOT NULL CHECK (priority IN ('LOW', 'NORMAL', 'HIGH')),
    priority_rank   SMALLINT     NOT NULL,
    subject         VARCHAR(200) NOT NULL,
    body            VARCHAR(10000) NOT NULL,
    body_hash       CHAR(64)     NOT NULL,
    status          VARCHAR(32)  NOT NULL CHECK (status IN
                        ('ACCEPTED', 'IN_PROGRESS', 'COMPLETED', 'PARTIALLY_DELIVERED', 'FAILED', 'EXPIRED')),
    scheduled_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_notification_idempotency UNIQUE (source_system, idempotency_key)
);

CREATE TABLE delivery (
    id                 UUID        PRIMARY KEY,
    notification_id    UUID        NOT NULL REFERENCES notification (id),
    recipient_id       VARCHAR(64) NOT NULL REFERENCES recipient (id),
    channel            VARCHAR(16) NOT NULL CHECK (channel IN ('EMAIL', 'SMS', 'PUSH')),
    address_masked     VARCHAR(320) NOT NULL,
    status             VARCHAR(16) NOT NULL CHECK (status IN
                           ('PENDING', 'IN_FLIGHT', 'SENT', 'RETRY_SCHEDULED', 'FAILED', 'EXPIRED')),
    attempt_count      INT         NOT NULL DEFAULT 0,
    next_attempt_at    TIMESTAMPTZ,
    locked_until       TIMESTAMPTZ,
    last_failure_class VARCHAR(32) CHECK (last_failure_class IN
                           ('TRANSIENT', 'TIMEOUT', 'RATE_LIMITED', 'PERMANENT_REJECTION', 'INVALID_RECIPIENT', 'AUTH_ERROR')),
    last_attempt_at    TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL,
    version            BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_delivery_notification_recipient_channel UNIQUE (notification_id, recipient_id, channel)
);

-- Worker claim: due PENDING/RETRY_SCHEDULED rows and expired IN_FLIGHT leases.
CREATE INDEX ix_delivery_status_next_attempt ON delivery (status, next_attempt_at);

-- Append-only. notification_id is NULL only for rejected requests that never produced a notification.
CREATE TABLE audit_event (
    id              BIGSERIAL   PRIMARY KEY,
    notification_id UUID        REFERENCES notification (id),
    delivery_id     UUID        REFERENCES delivery (id),
    source_system   VARCHAR(64),
    event_type      VARCHAR(32) NOT NULL,
    reason_code     VARCHAR(64),
    details         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    occurred_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_audit_event_notification ON audit_event (notification_id, id);
