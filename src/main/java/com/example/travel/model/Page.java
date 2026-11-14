package com.example.travel.model;

import java.util.List;

/** Offset page. {@code nextOffset} is null on the last page (fetched as limit+1, so no COUNT(*) scan). */
public record Page<T>(List<T> items, int limit, int offset, Integer nextOffset) {}
