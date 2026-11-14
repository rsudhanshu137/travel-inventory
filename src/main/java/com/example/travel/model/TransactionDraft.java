package com.example.travel.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A fully validated + normalised transaction that has not been stored yet. */
public record TransactionDraft(
        String type,
        String externalId,
        String currency,
        Instant occurredAt,
        BigDecimal total,
        List<TaxLine> taxLines,
        List<FeeLine> feeLines,
        ObjectNode metadata) {}
