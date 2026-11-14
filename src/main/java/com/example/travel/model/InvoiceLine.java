package com.example.travel.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** One included transaction, frozen at invoicing time. Everything here is a copy, not a join. */
public record InvoiceLine(
        String transactionId,
        String type,
        String externalId,
        String currency,
        Instant occurredAt,
        BigDecimal total,
        List<TaxLine> taxLines,
        List<FeeLine> feeLines,
        ObjectNode metadata) {

    public static InvoiceLine snapshotOf(Transaction t) {
        return new InvoiceLine(t.id(), t.type(), t.externalId(), t.currency(), t.occurredAt(), t.total(),
                List.copyOf(t.taxLines()), List.copyOf(t.feeLines()), t.metadata().deepCopy());
    }
}
