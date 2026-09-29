package com.nms.intake;

import com.nms.common.config.NmsProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Clears idempotency records older than the retention period so their keys can be reused. The notification itself
 * is kept. Safe to run on every instance: the update is idempotent.
 */
@Component
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final JdbcClient jdbc;
    private final NmsProperties properties;
    private final Clock clock;

    IdempotencyCleanupJob(JdbcClient jdbc, NmsProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${nms.idempotency.cleanup-interval}",
            initialDelayString = "${nms.idempotency.cleanup-interval}")
    public int clearExpiredKeys() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(properties.idempotency().retention());
        int cleared = jdbc.sql("""
                UPDATE notification SET idempotency_key = NULL, request_hash = NULL, updated_at = :now
                WHERE idempotency_key IS NOT NULL AND created_at < :cutoff
                """)
                .param("now", Timestamp.from(now))
                .param("cutoff", Timestamp.from(cutoff))
                .update();
        if (cleared > 0) {
            log.info("Cleared {} idempotency records older than {}", cleared, cutoff);
        }
        return cleared;
    }
}
