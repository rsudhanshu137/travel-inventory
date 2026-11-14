package com.example.travel.persistence;

import static com.example.travel.persistence.TransactionRepository.instant;
import static com.example.travel.persistence.TransactionRepository.utc;

import com.example.travel.model.FeeLine;
import com.example.travel.model.InvoiceHeader;
import com.example.travel.model.InvoiceLine;
import com.example.travel.model.InvoiceSummary;
import com.example.travel.model.Money;
import com.example.travel.model.TaxLine;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** All SQL for invoices + their frozen snapshot tables. Callers provide the transaction. */
@Repository
public class InvoiceRepository {
    private final JdbcTemplate jdbc;

    public InvoiceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertHeader(String id, String currency, java.math.BigDecimal total, Instant now) {
        jdbc.update("INSERT INTO invoices (id, currency, total, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                id, currency, total, utc(now), utc(now));
    }

    public void updateHeader(String id, String currency, java.math.BigDecimal total, Instant now) {
        jdbc.update("UPDATE invoices SET currency = ?, total = ?, updated_at = ? WHERE id = ?",
                currency, total, utc(now), id);
    }

    /** Row lock so two overlapping PATCH/DELETE on one invoice serialise instead of interleaving. */
    public boolean lock(String id) {
        return !jdbc.query("SELECT id FROM invoices WHERE id = ? FOR UPDATE", (rs, i) -> rs.getString(1), id).isEmpty();
    }

    /** Removes snapshot lines (and, via ON DELETE CASCADE, their tax/fee lines). Never touches transactions. */
    public void deleteLines(String invoiceId) {
        jdbc.update("DELETE FROM invoice_lines WHERE invoice_id = ?", invoiceId);
    }

    public int delete(String id) {
        return jdbc.update("DELETE FROM invoices WHERE id = ?", id);
    }

    public void insertLine(String invoiceId, int position, InvoiceLine l) {
        String lineId = "ln_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO invoice_lines (line_id, invoice_id, transaction_id, position, type, external_id, "
                        + "currency, occurred_at, total, metadata_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                lineId, invoiceId, l.transactionId(), position, l.type(), l.externalId(), l.currency(),
                utc(l.occurredAt()), l.total(), l.metadata().toString());

        List<Object[]> tax = new ArrayList<>();
        for (int i = 0; i < l.taxLines().size(); i++) {
            TaxLine t = l.taxLines().get(i);
            tax.add(new Object[] {lineId, i, t.name(), t.rate(), t.amount()});
        }
        if (!tax.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO invoice_line_tax_lines (line_id, position, name, rate, amount) VALUES (?, ?, ?, ?, ?)", tax);
        }
        List<Object[]> fees = new ArrayList<>();
        for (int i = 0; i < l.feeLines().size(); i++) {
            FeeLine f = l.feeLines().get(i);
            fees.add(new Object[] {lineId, i, f.name(), f.amount()});
        }
        if (!fees.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO invoice_line_fee_lines (line_id, position, name, amount) VALUES (?, ?, ?, ?)", fees);
        }
    }

    public Optional<InvoiceHeader> findHeader(String id) {
        List<InvoiceHeader> rows = jdbc.query(
                "SELECT id, currency, total, created_at, updated_at FROM invoices WHERE id = ?",
                (rs, i) -> new InvoiceHeader(rs.getString("id"), rs.getString("currency"),
                        Money.scaled(rs.getBigDecimal("total"), Money.scaleFor(rs.getString("currency"))),
                        instant(rs, "created_at"), instant(rs, "updated_at")), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Reads ONLY invoice_* tables -- the snapshot is never a join onto live transactions. */
    public List<InvoiceLine> findLines(String invoiceId, int scale) {
        Map<String, List<TaxLine>> taxByLine = new HashMap<>();
        jdbc.query("SELECT t.line_id, t.name, t.rate, t.amount FROM invoice_line_tax_lines t "
                + "JOIN invoice_lines l ON l.line_id = t.line_id WHERE l.invoice_id = ? ORDER BY t.line_id, t.position", rs -> {
            taxByLine.computeIfAbsent(rs.getString("line_id"), k -> new ArrayList<>()).add(new TaxLine(
                    rs.getString("name"), Money.normalizeRate(rs.getBigDecimal("rate")),
                    Money.scaled(rs.getBigDecimal("amount"), scale)));
        }, invoiceId);
        Map<String, List<FeeLine>> feeByLine = new HashMap<>();
        jdbc.query("SELECT f.line_id, f.name, f.amount FROM invoice_line_fee_lines f "
                + "JOIN invoice_lines l ON l.line_id = f.line_id WHERE l.invoice_id = ? ORDER BY f.line_id, f.position", rs -> {
            feeByLine.computeIfAbsent(rs.getString("line_id"), k -> new ArrayList<>()).add(new FeeLine(
                    rs.getString("name"), Money.scaled(rs.getBigDecimal("amount"), scale)));
        }, invoiceId);

        return jdbc.query("SELECT line_id, transaction_id, type, external_id, currency, occurred_at, total, metadata_json "
                + "FROM invoice_lines WHERE invoice_id = ? ORDER BY position", (rs, i) -> {
            String lineId = rs.getString("line_id");
            return new InvoiceLine(rs.getString("transaction_id"), rs.getString("type"), rs.getString("external_id"),
                    rs.getString("currency"), instant(rs, "occurred_at"),
                    Money.scaled(rs.getBigDecimal("total"), scale),
                    List.copyOf(taxByLine.getOrDefault(lineId, List.of())),
                    List.copyOf(feeByLine.getOrDefault(lineId, List.of())),
                    (ObjectNode) MetadataMapper.readJson(rs.getString("metadata_json")));
        }, invoiceId);
    }

    public List<InvoiceSummary> list(int fetch, int offset) {
        return jdbc.query("SELECT i.id, i.currency, i.total, i.created_at, "
                + "(SELECT COUNT(*) FROM invoice_lines l WHERE l.invoice_id = i.id) AS line_count "
                + "FROM invoices i ORDER BY i.seq DESC LIMIT ? OFFSET ?", (rs, n) -> new InvoiceSummary(
                rs.getString("id"), rs.getString("currency"),
                Money.scaled(rs.getBigDecimal("total"), Money.scaleFor(rs.getString("currency"))),
                rs.getInt("line_count"), instant(rs, "created_at")), fetch, offset);
    }
}
