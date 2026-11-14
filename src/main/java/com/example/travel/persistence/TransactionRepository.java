package com.example.travel.persistence;

import com.example.travel.model.FeeLine;
import com.example.travel.model.Money;
import com.example.travel.model.TaxLine;
import com.example.travel.model.Transaction;
import com.example.travel.model.TransactionDraft;
import com.example.travel.model.TransactionSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** All SQL for transactions and their child tables. Every method expects the caller's transaction. */
@Repository
public class TransactionRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public TransactionRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    private record Header(String id, String type, String externalId, String currency, Instant occurredAt,
                          BigDecimal total, Instant createdAt, Instant updatedAt, ObjectNode metadata) {}

    // ------------------------------------------------------------------ writes

    public void insert(String id, TransactionDraft d, Instant now) {
        Map<String, Object> detail = MetadataMapper.toColumns(d.type(), d.metadata());
        List<Object> args = new ArrayList<>();
        args.add(id);
        args.add(d.type());
        args.add(d.externalId());
        args.add(d.currency());
        args.add(utc(d.occurredAt()));
        args.add(d.total());
        args.add(utc(now));
        args.add(utc(now));
        args.addAll(detail.values());
        String sql = "INSERT INTO transactions (id, type, external_id, currency, occurred_at, total, created_at, updated_at, "
                + String.join(", ", MetadataMapper.DETAIL_COLUMNS) + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, "
                + "?, ".repeat(MetadataMapper.DETAIL_COLUMNS.length - 1) + "?)";
        jdbc.update(sql, args.toArray());
        insertChildren(id, d);
    }

    /** Replace header values + all child rows. Caller holds the row lock and is inside a transaction. */
    public void replace(String id, TransactionDraft d, Instant now) {
        Map<String, Object> detail = MetadataMapper.toColumns(d.type(), d.metadata());
        List<Object> args = new ArrayList<>();
        args.add(utc(d.occurredAt()));
        args.add(d.total());
        args.add(utc(now));
        args.addAll(detail.values());
        args.add(id);
        StringBuilder set = new StringBuilder("occurred_at = ?, total = ?, updated_at = ?");
        for (String col : MetadataMapper.DETAIL_COLUMNS) set.append(", ").append(col).append(" = ?");
        jdbc.update("UPDATE transactions SET " + set + " WHERE id = ?", args.toArray());
        for (String table : List.of("transaction_tax_lines", "transaction_fee_lines", "flight_coupons", "rail_legs")) {
            jdbc.update("DELETE FROM " + table + " WHERE transaction_id = ?", id);
        }
        insertChildren(id, d);
    }

    private void insertChildren(String id, TransactionDraft d) {
        List<Object[]> tax = new ArrayList<>();
        for (int i = 0; i < d.taxLines().size(); i++) {
            TaxLine t = d.taxLines().get(i);
            tax.add(new Object[] {id, i, t.name(), t.rate(), t.amount()});
        }
        if (!tax.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO transaction_tax_lines (transaction_id, position, name, rate, amount) VALUES (?, ?, ?, ?, ?)", tax);
        }
        List<Object[]> fees = new ArrayList<>();
        for (int i = 0; i < d.feeLines().size(); i++) {
            FeeLine f = d.feeLines().get(i);
            fees.add(new Object[] {id, i, f.name(), f.amount()});
        }
        if (!fees.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO transaction_fee_lines (transaction_id, position, name, amount) VALUES (?, ?, ?, ?)", fees);
        }
        if ("flight".equals(d.type())) {
            List<Object[]> rows = new ArrayList<>();
            int i = 0;
            for (JsonNode c : d.metadata().get("coupons")) {
                rows.add(new Object[] {id, i++, textOrNull(c, "carrier"), c.get("flight_number").asText(),
                        c.get("from").asText(), c.get("to").asText(),
                        utc(Instant.parse(c.get("departure").asText())), utc(Instant.parse(c.get("arrival").asText()))});
            }
            jdbc.batchUpdate("INSERT INTO flight_coupons (transaction_id, position, carrier, flight_number, from_airport, to_airport, departure, arrival) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", rows);
        } else if ("rail".equals(d.type())) {
            List<Object[]> rows = new ArrayList<>();
            int i = 0;
            for (JsonNode l : d.metadata().get("legs")) {
                rows.add(new Object[] {id, i++, l.get("origin").asText(), l.get("destination").asText(),
                        utc(Instant.parse(l.get("departure").asText())), utc(Instant.parse(l.get("arrival").asText())),
                        textOrNull(l, "train_number"), textOrNull(l, "class")});
            }
            jdbc.batchUpdate("INSERT INTO rail_legs (transaction_id, position, origin, destination, departure, arrival, train_number, travel_class) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", rows);
        }
    }

    public int delete(String id) {
        return jdbc.update("DELETE FROM transactions WHERE id = ?", id); // child rows go via ON DELETE CASCADE
    }

    // ------------------------------------------------------------------ locks

    /** Row lock (SELECT ... FOR UPDATE). Serialises PATCH / DELETE / invoicing of the same transaction. */
    public boolean lock(String id) {
        return !jdbc.query("SELECT id FROM transactions WHERE id = ? FOR UPDATE", (rs, i) -> rs.getString(1), id).isEmpty();
    }

    /** Locks every existing row among {@code ids} in id order (consistent order => no lock-order deadlocks). */
    public Set<String> lockAll(Collection<String> ids) {
        List<String> found = named.query(
                "SELECT id FROM transactions WHERE id IN (:ids) ORDER BY id FOR UPDATE",
                new MapSqlParameterSource("ids", ids), (rs, i) -> rs.getString(1));
        return new TreeSet<>(found);
    }

    // ------------------------------------------------------------------ reads

    /** Backed by ix on the invoice_lines.transaction_id index. */
    public boolean isInvoiced(String id) {
        Boolean b = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM invoice_lines WHERE transaction_id = ?)", Boolean.class, id);
        return Boolean.TRUE.equals(b);
    }

    public Optional<Transaction> findById(String id) {
        List<Header> rows = jdbc.query("SELECT * FROM transactions WHERE id = ?", (rs, i) -> header(rs), id);
        if (rows.isEmpty()) return Optional.empty();
        Header h = rows.get(0);
        int scale = Money.scaleFor(h.currency());

        List<TaxLine> tax = jdbc.query(
                "SELECT name, rate, amount FROM transaction_tax_lines WHERE transaction_id = ? ORDER BY position",
                (rs, i) -> new TaxLine(rs.getString("name"), Money.normalizeRate(rs.getBigDecimal("rate")),
                        Money.scaled(rs.getBigDecimal("amount"), scale)), id);
        List<FeeLine> fees = jdbc.query(
                "SELECT name, amount FROM transaction_fee_lines WHERE transaction_id = ? ORDER BY position",
                (rs, i) -> new FeeLine(rs.getString("name"), Money.scaled(rs.getBigDecimal("amount"), scale)), id);

        ObjectNode md = h.metadata();
        if ("flight".equals(h.type())) {
            ArrayNode arr = md.putArray("coupons");
            jdbc.query("SELECT carrier, flight_number, from_airport, to_airport, departure, arrival "
                    + "FROM flight_coupons WHERE transaction_id = ? ORDER BY position", rs -> {
                ObjectNode c = arr.addObject();
                String carrier = rs.getString("carrier");
                if (carrier != null) c.put("carrier", carrier);
                c.put("flight_number", rs.getString("flight_number"));
                c.put("from", rs.getString("from_airport"));
                c.put("to", rs.getString("to_airport"));
                c.put("departure", instant(rs, "departure").toString());
                c.put("arrival", instant(rs, "arrival").toString());
            }, id);
        } else if ("rail".equals(h.type())) {
            ArrayNode arr = md.putArray("legs");
            jdbc.query("SELECT origin, destination, departure, arrival, train_number, travel_class "
                    + "FROM rail_legs WHERE transaction_id = ? ORDER BY position", rs -> {
                ObjectNode l = arr.addObject();
                l.put("origin", rs.getString("origin"));
                l.put("destination", rs.getString("destination"));
                l.put("departure", instant(rs, "departure").toString());
                l.put("arrival", instant(rs, "arrival").toString());
                String train = rs.getString("train_number");
                if (train != null) l.put("train_number", train);
                String cls = rs.getString("travel_class");
                if (cls != null) l.put("class", cls);
            }, id);
        }
        return Optional.of(new Transaction(h.id(), h.type(), h.externalId(), h.currency(), h.occurredAt(),
                h.total(), List.copyOf(tax), List.copyOf(fees), md, h.createdAt(), h.updatedAt()));
    }

    /** Bounded page: always LIMIT/OFFSET, ordered by the unique identity column. Never loads the table. */
    public List<TransactionSummary> list(String externalId, int fetch, int offset) {
        String base = "SELECT id, type, external_id, currency, occurred_at, total, created_at FROM transactions ";
        if (externalId != null) {
            return jdbc.query(base + "WHERE external_id = ? ORDER BY seq DESC LIMIT ? OFFSET ?",
                    (rs, i) -> summary(rs), externalId, fetch, offset);
        }
        return jdbc.query(base + "ORDER BY seq DESC LIMIT ? OFFSET ?", (rs, i) -> summary(rs), fetch, offset);
    }

    // ------------------------------------------------------------------ mapping helpers

    private static Header header(ResultSet rs) throws SQLException {
        String currency = rs.getString("currency");
        String type = rs.getString("type");
        int scale = Money.scaleFor(currency);
        return new Header(rs.getString("id"), type, rs.getString("external_id"), currency,
                instant(rs, "occurred_at"), Money.scaled(rs.getBigDecimal("total"), scale),
                instant(rs, "created_at"), instant(rs, "updated_at"), MetadataMapper.fromRow(rs, type, scale));
    }

    private static TransactionSummary summary(ResultSet rs) throws SQLException {
        String currency = rs.getString("currency");
        return new TransactionSummary(rs.getString("id"), rs.getString("type"), rs.getString("external_id"),
                currency, instant(rs, "occurred_at"),
                Money.scaled(rs.getBigDecimal("total"), Money.scaleFor(currency)), instant(rs, "created_at"));
    }

    static Instant instant(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, OffsetDateTime.class).toInstant();
    }

    static OffsetDateTime utc(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static String textOrNull(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }
}
