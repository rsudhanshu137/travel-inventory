package com.example.travel.persistence;

import com.example.travel.model.Money;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Maps the validated type-specific metadata JSON to/from the typed columns of {@code transactions}
 * (single-table inheritance). Coupons and legs are child tables handled by the repository.
 */
final class MetadataMapper {
    private MetadataMapper() {}

    /** Every type-specific column, in a fixed order. Columns that do not apply to a type are written NULL. */
    static final String[] DETAIL_COLUMNS = {
            "flight_origin", "flight_destination", "flight_fare",
            "hotel_name", "hotel_street", "hotel_city", "hotel_country", "hotel_address_extra_json",
            "hotel_check_in", "hotel_check_out", "hotel_nights", "hotel_room_rate", "hotel_extras_json",
            "fee_kind", "fee_description", "fee_related_external_id",
            "extra_json"
    };

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    static Map<String, Object> toColumns(String type, ObjectNode md) {
        Map<String, Object> c = new LinkedHashMap<>();
        for (String col : DETAIL_COLUMNS) c.put(col, null);
        Set<String> known = new HashSet<>();
        switch (type) {
            case "flight":
                c.put("flight_origin", md.get("origin").asText());
                c.put("flight_destination", md.get("destination").asText());
                c.put("flight_fare", md.get("fare").decimalValue());
                known.addAll(Set.of("origin", "destination", "fare", "coupons"));
                break;
            case "hotel":
                ObjectNode addr = (ObjectNode) md.get("address");
                c.put("hotel_name", md.get("name").asText());
                c.put("hotel_street", addr.get("street").asText());
                c.put("hotel_city", addr.get("city").asText());
                c.put("hotel_country", addr.get("country").asText());
                ObjectNode addrExtra = JsonNodeFactory.instance.objectNode();
                for (Iterator<Map.Entry<String, JsonNode>> it = addr.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> e = it.next();
                    if (!Set.of("street", "city", "country").contains(e.getKey())) addrExtra.set(e.getKey(), e.getValue());
                }
                if (!addrExtra.isEmpty()) c.put("hotel_address_extra_json", addrExtra.toString());
                c.put("hotel_check_in", LocalDate.parse(md.get("check_in").asText()));
                c.put("hotel_check_out", LocalDate.parse(md.get("check_out").asText()));
                c.put("hotel_nights", md.get("nights").intValue());
                c.put("hotel_room_rate", md.get("room_rate_per_night").decimalValue());
                if (md.hasNonNull("extras")) c.put("hotel_extras_json", md.get("extras").toString());
                known.addAll(Set.of("name", "address", "check_in", "check_out", "nights", "room_rate_per_night", "extras"));
                break;
            case "rail":
                known.add("legs");
                break;
            case "navan_fee":
                c.put("fee_kind", md.get("fee_kind").asText());
                if (md.hasNonNull("description")) c.put("fee_description", md.get("description").asText());
                if (md.hasNonNull("related_external_id")) c.put("fee_related_external_id", md.get("related_external_id").asText());
                known.addAll(Set.of("fee_kind", "description", "related_external_id"));
                break;
            default:
                throw new IllegalArgumentException("unknown type " + type);
        }
        ObjectNode extra = JsonNodeFactory.instance.objectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = md.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!known.contains(e.getKey())) extra.set(e.getKey(), e.getValue());
        }
        if (!extra.isEmpty()) c.put("extra_json", extra.toString());
        return c;
    }

    /** Rebuild the metadata object from the current row (coupons/legs are attached by the caller). */
    static ObjectNode fromRow(ResultSet rs, String type, int scale) throws SQLException {
        ObjectNode md = JsonNodeFactory.instance.objectNode();
        switch (type) {
            case "flight":
                md.put("origin", rs.getString("flight_origin"));
                md.put("destination", rs.getString("flight_destination"));
                md.put("fare", Money.scaled(rs.getBigDecimal("flight_fare"), scale));
                break;
            case "hotel":
                md.put("name", rs.getString("hotel_name"));
                ObjectNode addr = md.putObject("address");
                addr.put("street", rs.getString("hotel_street"));
                addr.put("city", rs.getString("hotel_city"));
                addr.put("country", rs.getString("hotel_country"));
                mergeInto(addr, rs.getString("hotel_address_extra_json"));
                md.put("check_in", rs.getObject("hotel_check_in", LocalDate.class).toString());
                md.put("check_out", rs.getObject("hotel_check_out", LocalDate.class).toString());
                md.put("nights", rs.getInt("hotel_nights"));
                md.put("room_rate_per_night", Money.scaled(rs.getBigDecimal("hotel_room_rate"), scale));
                String extras = rs.getString("hotel_extras_json");
                if (extras != null) md.set("extras", readJson(extras));
                break;
            case "navan_fee":
                md.put("fee_kind", rs.getString("fee_kind"));
                String desc = rs.getString("fee_description");
                if (desc != null) md.put("description", desc);
                String related = rs.getString("fee_related_external_id");
                if (related != null) md.put("related_external_id", related);
                break;
            default:
                break; // rail: legs only (child table)
        }
        mergeInto(md, rs.getString("extra_json"));
        return md;
    }

    static JsonNode readJson(String s) {
        try {
            return JSON.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt JSON in database", e);
        }
    }

    private static void mergeInto(ObjectNode target, String json) {
        if (json == null) return;
        JsonNode extra = readJson(json);
        for (Iterator<Map.Entry<String, JsonNode>> it = extra.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!target.has(e.getKey())) target.set(e.getKey(), e.getValue());
        }
    }
}
