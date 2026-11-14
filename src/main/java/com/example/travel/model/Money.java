package com.example.travel.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Money rules in one place. All amounts are {@link BigDecimal}; the scale is the currency's minor-unit
 * digits (EUR/USD 2, JPY 0, KWD 3). The database stores NUMERIC(19,4), so up to 4 digits fit.
 */
public final class Money {
    private Money() {}

    /** @throws IllegalArgumentException for an unknown ISO-4217 code or one without a minor unit. */
    public static int scaleFor(String currencyCode) {
        int digits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
        if (digits < 0 || digits > 4) {
            throw new IllegalArgumentException("currency has no usable minor unit: " + currencyCode);
        }
        return digits;
    }

    /** Re-scale a value read back from NUMERIC(19,4) to the currency scale (value is already exact). */
    public static BigDecimal scaled(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    /** Canonical form for rates: 0.070000 -> 0.07, 1.000 -> 1. */
    public static BigDecimal normalizeRate(BigDecimal rate) {
        if (rate == null) return null;
        BigDecimal r = rate.stripTrailingZeros();
        return r.scale() < 0 ? r.setScale(0) : r;
    }
}
