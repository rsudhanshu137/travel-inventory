package com.example.travel.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** A failure that maps 1:1 onto an HTTP status. Kept free of Spring types so every layer can throw it. */
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final Map<String, Object> extra;

    public ApiException(int status, String code, String message, Map<String, Object> extra) {
        super(message);
        this.status = status;
        this.code = code;
        this.extra = extra == null ? Map.of() : extra;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "bad_request", message, null);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "not_found", message, null);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message, null);
    }

    public static ApiException duplicateExternalId(String externalId, String existingId) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("id", existingId);
        extra.put("existing_id", existingId);
        extra.put("external_id", externalId);
        return new ApiException(409, "duplicate_external_id",
                "A transaction with external_id '" + externalId + "' already exists", extra);
    }

    public int status() { return status; }
    public String code() { return code; }
    public Map<String, Object> extra() { return extra; }
}
