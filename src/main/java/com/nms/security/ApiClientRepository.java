package com.nms.security;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ApiClientRepository {

    private final JdbcClient jdbc;

    ApiClientRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The source system owning an active API key with this hash, if any. */
    Optional<String> findActiveSourceSystem(String keyHash) {
        return jdbc.sql("SELECT source_system FROM api_client WHERE key_hash = :hash AND active")
                .param("hash", keyHash)
                .query(String.class)
                .optional();
    }
}
