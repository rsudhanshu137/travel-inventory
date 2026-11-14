package com.example.travel.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record Transaction(
        String id,
        String type,
        String externalId,
        String currency,
        Instant occurredAt,
        BigDecimal total,
        List<TaxLine> taxLines,
        List<FeeLine> feeLines,
        ObjectNode metadata,
        Instant createdAt,
        Instant updatedAt) {}
