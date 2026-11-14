package com.example.travel.model;

import java.math.BigDecimal;
import java.time.Instant;

/** Light row for list endpoints (no child-table loads => no N+1 under volume). */
public record TransactionSummary(
        String id, String type, String externalId, String currency,
        Instant occurredAt, BigDecimal total, Instant createdAt) {}
