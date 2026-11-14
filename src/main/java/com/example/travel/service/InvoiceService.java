package com.example.travel.service;

import com.example.travel.model.ApiException;
import com.example.travel.model.Invoice;
import com.example.travel.model.InvoiceHeader;
import com.example.travel.model.InvoiceLine;
import com.example.travel.model.InvoiceSummary;
import com.example.travel.model.Money;
import com.example.travel.model.Page;
import com.example.travel.model.RollupCalculator;
import com.example.travel.model.Transaction;
import com.example.travel.persistence.InvoiceRepository;
import com.example.travel.persistence.TransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Invoice snapshot / rollup seam. An invoice is built ONCE from stored transactions and then only read back. */
@Service
public class InvoiceService {

    private static final int MAX_IDS = 200;

    private final InvoiceRepository invoices;
    private final TransactionRepository transactions;
    private final TransactionTemplate tx;

    public InvoiceService(InvoiceRepository invoices, TransactionRepository transactions, TransactionTemplate tx) {
        this.invoices = invoices;
        this.transactions = transactions;
        this.tx = tx;
    }

    public Invoice create(JsonNode body) {
        List<String> ids = parseIds(body);
        String invoiceId = "inv_" + UUID.randomUUID().toString().replace("-", "");
        tx.executeWithoutResult(status -> writeSnapshot(invoiceId, ids, true));
        return get(invoiceId);
    }

    /** Replace the set of transactions: rebuild the snapshot from CURRENT stored rows in one atomic write. */
    public Invoice patch(String invoiceId, JsonNode body) {
        List<String> ids = parseIds(body);
        tx.executeWithoutResult(status -> {
            if (!invoices.lock(invoiceId)) throw ApiException.notFound("invoice '" + invoiceId + "' not found");
            writeSnapshot(invoiceId, ids, false);
        });
        return get(invoiceId);
    }

    public void delete(String invoiceId) {
        tx.executeWithoutResult(status -> {
            if (!invoices.lock(invoiceId)) throw ApiException.notFound("invoice '" + invoiceId + "' not found");
            invoices.delete(invoiceId); // snapshot rows cascade; transactions are untouched
        });
    }

    public Invoice get(String invoiceId) {
        InvoiceHeader h = invoices.findHeader(invoiceId)
                .orElseThrow(() -> ApiException.notFound("invoice '" + invoiceId + "' not found"));
        int scale = Money.scaleFor(h.currency());
        List<InvoiceLine> lines = invoices.findLines(invoiceId, scale);
        return new Invoice(h.id(), h.currency(), h.total(), lines.size(),
                RollupCalculator.totals(lines, scale),
                RollupCalculator.taxRollup(lines, scale),
                RollupCalculator.feeRollup(lines, scale),
                lines, h.createdAt(), h.updatedAt());
    }

    public Page<InvoiceSummary> list(int limit, int offset) {
        return PageSupport.page(invoices.list(limit + 1, offset), limit, offset);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Must run inside a DB transaction. Locks every referenced transaction row (so a concurrent PATCH/DELETE of one
     * of them waits and then sees "invoiced"), validates, then copies the rows into invoice_* tables.
     * Check order: 404 (missing id) before 409 (mixed currency).
     */
    private void writeSnapshot(String invoiceId, List<String> ids, boolean create) {
        Set<String> found = transactions.lockAll(ids);
        List<String> missing = new ArrayList<>();
        for (String id : ids) if (!found.contains(id)) missing.add(id);
        if (!missing.isEmpty()) throw ApiException.notFound("transaction(s) not found: " + missing);

        List<InvoiceLine> lines = new ArrayList<>();
        for (String id : ids) {
            Transaction t = transactions.findById(id).orElseThrow();
            lines.add(InvoiceLine.snapshotOf(t));
        }
        String currency = lines.get(0).currency();
        for (InvoiceLine l : lines) {
            if (!l.currency().equals(currency)) {
                throw ApiException.conflict("currency_mismatch",
                        "an invoice cannot mix currencies (" + currency + " and " + l.currency() + ")");
            }
        }
        BigDecimal total = RollupCalculator.grandTotal(lines, Money.scaleFor(currency));
        Instant now = Instant.now();
        if (create) {
            invoices.insertHeader(invoiceId, currency, total, now);
        } else {
            invoices.deleteLines(invoiceId);
            invoices.updateHeader(invoiceId, currency, total, now);
        }
        int position = 0;
        for (InvoiceLine l : lines) invoices.insertLine(invoiceId, position++, l);
    }

    /**
     * Accepts {"transaction_id": "..."} or {"transaction_ids": [...]} (exactly one). An empty list is 400; the same id
     * twice is 409 (documented choice: reject rather than silently dedupe).
     */
    static List<String> parseIds(JsonNode body) {
        if (body == null || !body.isObject()) throw ApiException.badRequest("request body must be a JSON object");
        boolean single = body.has("transaction_id");
        boolean many = body.has("transaction_ids");
        if (single == many) {
            throw ApiException.badRequest("provide exactly one of transaction_id or transaction_ids");
        }
        List<String> ids = new ArrayList<>();
        if (single) {
            ids.add(idText(body.get("transaction_id"), "transaction_id"));
        } else {
            JsonNode arr = body.get("transaction_ids");
            if (!arr.isArray() || arr.isEmpty()) throw ApiException.badRequest("transaction_ids must be a non-empty array");
            if (arr.size() > MAX_IDS) throw ApiException.badRequest("transaction_ids may contain at most " + MAX_IDS + " ids");
            for (JsonNode n : arr) ids.add(idText(n, "transaction_ids[]"));
        }
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (!seen.add(id)) {
                throw ApiException.conflict("duplicate_transaction_id",
                        "transaction '" + id + "' is listed more than once on this invoice");
            }
        }
        return List.copyOf(ids);
    }

    private static String idText(JsonNode n, String path) {
        if (n == null || !n.isTextual() || n.asText().isBlank()) {
            throw ApiException.badRequest(path + " must be a non-blank string");
        }
        return n.asText().trim();
    }
}
