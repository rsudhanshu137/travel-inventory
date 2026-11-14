package com.example.travel.validation;

import static com.example.travel.model.ApiException.badRequest;

import com.example.travel.model.Money;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Small typed accessors over JsonNode. Every failure is a 400 that names the offending path. */
final class Fields {
    private Fields() {}

    static ObjectNode object(JsonNode parent, String field, String path) {
        JsonNode n = parent.get(field);
        if (n == null || n.isNull()) throw badRequest(path + " is required");
        if (!n.isObject()) throw badRequest(path + " must be a JSON object");
        return (ObjectNode) n;
    }

    static ArrayNode array(JsonNode parent, String field, String path, boolean nonEmpty, int max) {
        JsonNode n = parent.get(field);
        if (n == null || n.isNull()) throw badRequest(path + " is required");
        if (!n.isArray()) throw badRequest(path + " must be a JSON array");
        if (nonEmpty && n.isEmpty()) throw badRequest(path + " must contain at least one element");
        if (n.size() > max) throw badRequest(path + " may contain at most " + max + " elements");
        return (ArrayNode) n;
    }

    static JsonNode element(JsonNode array, int i, String path) {
        JsonNode e = array.get(i);
        if (e == null || !e.isObject()) throw badRequest(path + " must be a JSON object");
        return e;
    }

    static String text(JsonNode parent, String field, String path, int maxLen) {
        JsonNode n = parent.get(field);
        if (n == null || n.isNull()) throw badRequest(path + " is required");
        if (!n.isTextual() || n.asText().isBlank()) throw badRequest(path + " must be a non-blank string");
        String s = n.asText().trim();
        if (s.length() > maxLen) throw badRequest(path + " must be at most " + maxLen + " characters");
        return s;
    }

    static String optText(JsonNode parent, String field, String path, int maxLen) {
        JsonNode n = parent.get(field);
        if (n == null || n.isNull()) return null;
        return text(parent, field, path, maxLen);
    }

    /** Non-negative JSON number with no more decimals than the currency allows; returned at exactly that scale. */
    static BigDecimal money(JsonNode parent, String field, String path, int scale) {
        BigDecimal v = number(parent, field, path);
        if (v.signum() < 0) throw badRequest(path + " must not be negative");
        if (v.stripTrailingZeros().scale() > scale) {
            throw badRequest(path + " has more than " + scale + " decimal places for this currency");
        }
        BigDecimal scaled = v.setScale(scale);
        if (scaled.precision() > 15) throw badRequest(path + " is too large");
        return scaled;
    }

    /** Optional tax rate in [0,1] with at most 6 decimals (0.07 = 7%). Null when absent. */
    static BigDecimal optRate(JsonNode parent, String field, String path) {
        JsonNode n = parent.get(field);
        if (n == null || n.isNull()) return null;
        BigDecimal v = number(parent, field, path);
        if (v.signum() < 0 || v.compareTo(BigDecimal.ONE) > 0) throw badRequest(path + " must be between 0 and 1");
        if (v.stripTrailingZeros().scale() > 6) throw badRequest(path + " has more than 6 decimal places");
        return Money.normalizeRate(v);
    }

    private static BigDecimal number(JsonNode parent, String field, String path) {
        JsonNode n = parent.get(field);
        if (n == null || n.isNull()) throw badRequest(path + " is required");
        if (!n.isNumber()) throw badRequest(path + " must be a JSON number");
        // asText() of a double is its shortest decimal form ("331.7"), so no binary-float noise leaks in.
        return new BigDecimal(n.asText());
    }

    static Instant instant(JsonNode parent, String field, String path) {
        String s = text(parent, field, path, 40);
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            throw badRequest(path + " must be ISO-8601 with an offset, e.g. 2026-03-11T06:40:00Z");
        }
    }

    static LocalDate date(JsonNode parent, String field, String path) {
        String s = text(parent, field, path, 10);
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw badRequest(path + " must be an ISO date, e.g. 2026-03-11");
        }
    }

    static int positiveInt(JsonNode parent, String field, String path) {
        JsonNode n = parent.get(field);
        if (n == null || n.isNull()) throw badRequest(path + " is required");
        if (!n.isIntegralNumber() || !n.canConvertToInt() || n.intValue() < 1) {
            throw badRequest(path + " must be a positive integer");
        }
        return n.intValue();
    }

    /** Keep client-sent keys we do not model ("extra fields are fine"). */
    static void copyExtras(ObjectNode in, Set<String> known, ObjectNode out) {
        Iterator<Map.Entry<String, JsonNode>> it = in.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!known.contains(e.getKey()) && !out.has(e.getKey())) {
                out.set(e.getKey(), e.getValue().deepCopy());
            }
        }
    }
}
