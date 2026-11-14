package com.example.travel.model;

import java.math.BigDecimal;

/** Government/VAT-style line. {@code rate} is optional (e.g. a flat airport tax has none). */
public record TaxLine(String name, BigDecimal rate, BigDecimal amount) {}
