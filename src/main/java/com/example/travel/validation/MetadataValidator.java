package com.example.travel.validation;

import static com.example.travel.model.ApiException.badRequest;
import static com.example.travel.validation.Fields.array;
import static com.example.travel.validation.Fields.copyExtras;
import static com.example.travel.validation.Fields.date;
import static com.example.travel.validation.Fields.element;
import static com.example.travel.validation.Fields.instant;
import static com.example.travel.validation.Fields.money;
import static com.example.travel.validation.Fields.object;
import static com.example.travel.validation.Fields.optText;
import static com.example.travel.validation.Fields.positiveInt;
import static com.example.travel.validation.Fields.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Per-type metadata rules. Returns a NORMALISED copy (amounts at currency scale, instants as ISO-8601 UTC,
 * unknown keys preserved) so persistence never sees raw client JSON, and a flight can never be stored as a
 * hotel blob because each type has its own required shape.
 */
@Component
public class MetadataValidator {

    public static final Set<String> FEE_KINDS = Set.of("trip_fee", "agent_call_fee");

    private static final Set<String> FLIGHT_KEYS = Set.of("origin", "destination", "fare", "coupons");
    private static final Set<String> HOTEL_KEYS =
            Set.of("name", "address", "check_in", "check_out", "nights", "room_rate_per_night", "extras");
    private static final Set<String> RAIL_KEYS = Set.of("legs");
    private static final Set<String> FEE_META_KEYS = Set.of("fee_kind", "description", "related_external_id");

    public ObjectNode validate(String type, JsonNode metadata, int scale) {
        if (metadata == null || metadata.isNull()) throw badRequest("metadata is required");
        if (!metadata.isObject()) throw badRequest("metadata must be a JSON object");
        ObjectNode in = (ObjectNode) metadata;
        switch (type) {
            case "flight": return flight(in, scale);
            case "hotel": return hotel(in, scale);
            case "rail": return rail(in);
            case "navan_fee": return navanFee(in);
            default: throw badRequest("unknown type '" + type + "'");
        }
    }

    private ObjectNode flight(ObjectNode in, int scale) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("origin", text(in, "origin", "metadata.origin", 64));
        out.put("destination", text(in, "destination", "metadata.destination", 64));
        out.put("fare", money(in, "fare", "metadata.fare", scale));
        ArrayNode coupons = array(in, "coupons", "metadata.coupons", true, 50);
        ArrayNode outCoupons = out.putArray("coupons");
        for (int i = 0; i < coupons.size(); i++) {
            String p = "metadata.coupons[" + i + "]";
            JsonNode c = element(coupons, i, p);
            ObjectNode oc = outCoupons.addObject();
            oc.put("flight_number", text(c, "flight_number", p + ".flight_number", 16));
            String carrier = optText(c, "carrier", p + ".carrier", 16);
            if (carrier != null) oc.put("carrier", carrier);
            oc.put("from", text(c, "from", p + ".from", 64));
            oc.put("to", text(c, "to", p + ".to", 64));
            Instant dep = instant(c, "departure", p + ".departure");
            Instant arr = instant(c, "arrival", p + ".arrival");
            if (arr.isBefore(dep)) throw badRequest(p + ".arrival must not be before departure");
            oc.put("departure", dep.toString());
            oc.put("arrival", arr.toString());
        }
        copyExtras(in, FLIGHT_KEYS, out);
        return out;
    }

    private ObjectNode hotel(ObjectNode in, int scale) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("name", text(in, "name", "metadata.name", 200));
        ObjectNode addr = object(in, "address", "metadata.address");
        ObjectNode outAddr = out.putObject("address");
        outAddr.put("street", text(addr, "street", "metadata.address.street", 200));
        outAddr.put("city", text(addr, "city", "metadata.address.city", 100));
        outAddr.put("country", text(addr, "country", "metadata.address.country", 100));
        copyExtras(addr, Set.of("street", "city", "country"), outAddr);

        LocalDate checkIn = date(in, "check_in", "metadata.check_in");
        LocalDate checkOut = date(in, "check_out", "metadata.check_out");
        if (!checkOut.isAfter(checkIn)) throw badRequest("metadata.check_out must be after check_in");
        out.put("check_in", checkIn.toString());
        out.put("check_out", checkOut.toString());
        out.put("nights", positiveInt(in, "nights", "metadata.nights"));
        out.put("room_rate_per_night", money(in, "room_rate_per_night", "metadata.room_rate_per_night", scale));

        JsonNode extras = in.get("extras");
        if (extras != null && !extras.isNull()) {
            ArrayNode arr = array(in, "extras", "metadata.extras", false, 50);
            ArrayNode outExtras = out.putArray("extras");
            for (int i = 0; i < arr.size(); i++) {
                String p = "metadata.extras[" + i + "]";
                JsonNode e = element(arr, i, p);
                ObjectNode oe = outExtras.addObject();
                oe.put("name", text(e, "name", p + ".name", 100));
                oe.put("amount", money(e, "amount", p + ".amount", scale));
            }
        }
        copyExtras(in, HOTEL_KEYS, out);
        return out;
    }

    private ObjectNode rail(ObjectNode in) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        ArrayNode legs = array(in, "legs", "metadata.legs", true, 50);
        ArrayNode outLegs = out.putArray("legs");
        for (int i = 0; i < legs.size(); i++) {
            String p = "metadata.legs[" + i + "]";
            JsonNode l = element(legs, i, p);
            ObjectNode ol = outLegs.addObject();
            ol.put("origin", text(l, "origin", p + ".origin", 100));
            ol.put("destination", text(l, "destination", p + ".destination", 100));
            Instant dep = instant(l, "departure", p + ".departure");
            Instant arr = instant(l, "arrival", p + ".arrival");
            if (arr.isBefore(dep)) throw badRequest(p + ".arrival must not be before departure");
            ol.put("departure", dep.toString());
            ol.put("arrival", arr.toString());
            String train = optText(l, "train_number", p + ".train_number", 32);
            if (train != null) ol.put("train_number", train);
            String cls = optText(l, "class", p + ".class", 32);
            if (cls != null) ol.put("class", cls);
        }
        copyExtras(in, RAIL_KEYS, out);
        return out;
    }

    private ObjectNode navanFee(ObjectNode in) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        String kind = text(in, "fee_kind", "metadata.fee_kind", 32);
        if (!FEE_KINDS.contains(kind)) {
            throw badRequest("unknown metadata.fee_kind '" + kind + "'; expected one of trip_fee, agent_call_fee");
        }
        out.put("fee_kind", kind);
        String description = optText(in, "description", "metadata.description", 500);
        if (description != null) out.put("description", description);
        String related = optText(in, "related_external_id", "metadata.related_external_id", 200);
        if (related != null) out.put("related_external_id", related);
        copyExtras(in, FEE_META_KEYS, out);
        return out;
    }
}
