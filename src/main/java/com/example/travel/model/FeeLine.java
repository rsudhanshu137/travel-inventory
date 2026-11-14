package com.example.travel.model;

import java.math.BigDecimal;

/** Carrier/merchant fee on a transaction (YQ-like surcharge, booking fee, ...). Never a tax. */
public record FeeLine(String name, BigDecimal amount) {}
