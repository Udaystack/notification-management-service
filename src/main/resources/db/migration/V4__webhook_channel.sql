-- Webhook channel: allow WEBHOOK in both channel checks (V1 created them unnamed; PostgreSQL named them
-- <table>_channel_check) and seed a recipient that has a webhook address.
ALTER TABLE recipient_channel DROP CONSTRAINT recipient_channel_channel_check,
    ADD CONSTRAINT recipient_channel_channel_check CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'WEBHOOK'));

ALTER TABLE delivery DROP CONSTRAINT delivery_channel_check,
    ADD CONSTRAINT delivery_channel_check CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'WEBHOOK'));

-- The webhook URL is plain http on localhost: deliverable only with nms.webhook.allow-private-hosts (local profile).
INSERT INTO recipient (id, display_name) VALUES
    ('cust-3001', 'Webhook integration');

INSERT INTO recipient_channel (recipient_id, channel, address, opted_out) VALUES
    ('cust-3001', 'WEBHOOK', 'http://localhost:9099/hooks/cust-3001', FALSE),
    ('cust-3001', 'EMAIL',   'integrations@example.com',              FALSE);
