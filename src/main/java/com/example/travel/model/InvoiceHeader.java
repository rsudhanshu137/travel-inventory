package com.example.travel.model;

import java.math.BigDecimal;
import java.time.Instant;

public record InvoiceHeader(String id, String currency, BigDecimal total, Instant createdAt, Instant updatedAt) {}
