package com.example.travel.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record Invoice(
        String id,
        String currency,
        BigDecimal total,
        int lineCount,
        Map<String, BigDecimal> totals,
        List<TaxLine> taxRollup,
        List<FeeLine> feeRollup,
        List<InvoiceLine> lines,
        Instant createdAt,
        Instant updatedAt) {}
