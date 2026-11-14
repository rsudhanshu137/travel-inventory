package com.example.travel.service;

import com.example.travel.model.ApiException;
import com.example.travel.model.Page;
import java.util.List;

/** Shared pagination rules: default 50, max 100, offset >= 0; limit+1 rows fetched to detect a next page. */
public final class PageSupport {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    private PageSupport() {}

    public static void check(int limit, int offset) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw ApiException.badRequest("limit must be between 1 and " + MAX_LIMIT + " (default " + DEFAULT_LIMIT + ")");
        }
        if (offset < 0) throw ApiException.badRequest("offset must be >= 0");
    }

    static <T> Page<T> page(List<T> fetchedPlusOne, int limit, int offset) {
        boolean more = fetchedPlusOne.size() > limit;
        List<T> items = more ? fetchedPlusOne.subList(0, limit) : fetchedPlusOne;
        return new Page<>(List.copyOf(items), limit, offset, more ? offset + limit : null);
    }
}
