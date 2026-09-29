-- Event-level deduplication. Additive only: nullable column, widened checks, new indexes; no backfill.

-- A SUPPRESSED delivery references the deliverable original it duplicates.
ALTER TABLE delivery ADD COLUMN suppressed_by UUID NULL REFERENCES delivery (id);

ALTER TABLE delivery
    DROP CONSTRAINT delivery_status_check,
    ADD CONSTRAINT delivery_status_check CHECK (status IN
        ('PENDING', 'IN_FLIGHT', 'SENT', 'RETRY_SCHEDULED', 'FAILED', 'EXPIRED', 'SUPPRESSED'));

ALTER TABLE notification
    DROP CONSTRAINT notification_status_check,
    ADD CONSTRAINT notification_status_check CHECK (status IN
        ('ACCEPTED', 'IN_PROGRESS', 'COMPLETED', 'PARTIALLY_DELIVERED', 'FAILED', 'EXPIRED', 'SUPPRESSED'));

-- Dedup lookup: notifications of one event, then their deliveries per recipient and channel.
CREATE INDEX ix_notification_src_event ON notification (source_system, event_id);
CREATE INDEX ix_delivery_dedup ON delivery (notification_id, recipient_id, channel, created_at);
