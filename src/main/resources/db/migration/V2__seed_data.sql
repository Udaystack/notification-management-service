-- Demo API clients. Plain-text keys are documented in the README for local use only.
INSERT INTO api_client (source_system, key_hash, active) VALUES
    ('billing', '70479cd3fb2561c49212504f11bd8ec20e65408b333841e7e45df44ae820c461', TRUE),
    ('trading', 'f623898d9e52c74426e0f04275e32fe5fd2340185a34cd5b74e2c0fa2fc3e71d', TRUE),
    ('legacy',  '9fdc7a3cebe55da1493ab40d2473617f7a5125d050e5b9a36b2f88999cc88dc9', FALSE);

INSERT INTO recipient (id, display_name) VALUES
    ('cust-1001', 'Jane Doe'),
    ('cust-1002', 'John Smith'),
    ('cust-1003', 'Push-only opted out'),
    ('cust-1004', 'Operations'),
    ('cust-2001', 'Marker transient'),
    ('cust-2002', 'Marker timeout'),
    ('cust-2003', 'Marker ratelimit'),
    ('cust-2004', 'Marker reject'),
    ('cust-2005', 'Marker invalid'),
    ('cust-2006', 'Marker auth'),
    ('cust-2007', 'Marker flaky');

INSERT INTO recipient_channel (recipient_id, channel, address, opted_out) VALUES
    -- Happy path, critical escalation, masking.
    ('cust-1001', 'EMAIL', 'jane.doe@example.com', FALSE),
    ('cust-1001', 'SMS',   '+1-555-867-1234',      FALSE),
    ('cust-1001', 'PUSH',  'device-token-1001',    FALSE),
    -- Opted out of SMS: EMAIL fallback.
    ('cust-1002', 'EMAIL', 'john.smith@example.com', FALSE),
    ('cust-1002', 'SMS',   '+1-555-222-3344',        TRUE),
    -- Only channel is opted out: no eligible channel.
    ('cust-1003', 'PUSH',  'device-token-1003', TRUE),
    -- Escalation adds SMS, opt-out removes PUSH.
    ('cust-1004', 'EMAIL', 'ops@example.com',   FALSE),
    ('cust-1004', 'SMS',   '+1-555-000-1111',   FALSE),
    ('cust-1004', 'PUSH',  'device-token-1004', TRUE),
    -- Failure injection (simulated providers).
    ('cust-2001', 'EMAIL', 'user+transient@example.com', FALSE),
    ('cust-2002', 'EMAIL', 'user+timeout@example.com',   FALSE),
    ('cust-2003', 'EMAIL', 'user+ratelimit@example.com', FALSE),
    ('cust-2004', 'EMAIL', 'user+reject@example.com',    FALSE),
    ('cust-2005', 'EMAIL', 'user+invalid@example.com',   FALSE),
    ('cust-2006', 'EMAIL', 'user+auth@example.com',      FALSE),
    ('cust-2007', 'EMAIL', 'user+flaky@example.com',     FALSE);
