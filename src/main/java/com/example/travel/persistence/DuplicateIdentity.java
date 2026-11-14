package com.example.travel.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Duplicate-identity seam. The authority is the UNIQUE constraint on transactions.external_id; this class
 * only answers "which id owns this external_id?" so a rejected POST can report the EXISTING id.
 */
@Component
public class DuplicateIdentity {
    private final JdbcTemplate jdbc;

    public DuplicateIdentity(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Index lookup on the unique external_id index. */
    public Optional<String> existingId(String externalId) {
        List<String> ids = jdbc.query("SELECT id FROM transactions WHERE external_id = ?",
                (rs, i) -> rs.getString(1), externalId);
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
    }
}
