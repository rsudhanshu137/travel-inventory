package com.example.travel.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Invoice-level rollups, computed from the frozen snapshot lines (never from live transactions).
 *
 * <ul>
 *   <li>tax rollup: grouped by (name, normalised rate) -- 0.07 and 0.070 are the same group, "no rate" is its own</li>
 *   <li>fee rollup: grouped by name (case-sensitive) over {@code fee_lines} only. A navan_fee TRANSACTION is
 *       a line of the invoice, not a fee_line, so it is deliberately NOT in this list.</li>
 *   <li>rollups are a breakdown of amounts already inside each line's gross total -- they are not added on top</li>
 * </ul>
 */
public final class RollupCalculator {
    private RollupCalculator() {}

    private record TaxKey(String name, BigDecimal rate) {}

    public static List<TaxLine> taxRollup(List<InvoiceLine> lines, int scale) {
        Map<TaxKey, BigDecimal> sums = new LinkedHashMap<>();
        for (InvoiceLine line : lines) {
            for (TaxLine t : line.taxLines()) {
                sums.merge(new TaxKey(t.name(), Money.normalizeRate(t.rate())), t.amount(), BigDecimal::add);
            }
        }
        List<TaxLine> out = new ArrayList<>();
        sums.forEach((k, v) -> out.add(new TaxLine(k.name(), k.rate(), Money.scaled(v, scale))));
        out.sort(Comparator.comparing(TaxLine::name)
                .thenComparing(TaxLine::rate, Comparator.nullsFirst(Comparator.naturalOrder())));
        return out;
    }

    public static List<FeeLine> feeRollup(List<InvoiceLine> lines, int scale) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (InvoiceLine line : lines) {
            for (FeeLine f : line.feeLines()) {
                sums.merge(f.name(), f.amount(), BigDecimal::add);
            }
        }
        List<FeeLine> out = new ArrayList<>();
        sums.forEach((k, v) -> out.add(new FeeLine(k, Money.scaled(v, scale))));
        out.sort(Comparator.comparing(FeeLine::name));
        return out;
    }

    /** Sum of line totals. */
    public static BigDecimal grandTotal(List<InvoiceLine> lines, int scale) {
        BigDecimal sum = BigDecimal.ZERO;
        for (InvoiceLine l : lines) sum = sum.add(l.total());
        return Money.scaled(sum, scale);
    }

    /**
     * Contribution per kind: flight / hotel / rail, and for navan_fee the fee_kind (trip_fee, agent_call_fee),
     * followed by grand_total. Insertion order = invoice line order.
     */
    public static Map<String, BigDecimal> totals(List<InvoiceLine> lines, int scale) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (InvoiceLine l : lines) {
            String key = l.type();
            if ("navan_fee".equals(l.type()) && l.metadata().hasNonNull("fee_kind")) {
                key = l.metadata().get("fee_kind").asText();
            }
            out.merge(key, l.total(), BigDecimal::add);
        }
        out.replaceAll((k, v) -> Money.scaled(v, scale));
        out.put("grand_total", grandTotal(lines, scale));
        return out;
    }
}
