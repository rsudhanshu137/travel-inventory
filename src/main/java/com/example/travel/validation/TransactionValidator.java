package com.example.travel.validation;

import static com.example.travel.model.ApiException.badRequest;
import static com.example.travel.validation.Fields.array;
import static com.example.travel.validation.Fields.element;
import static com.example.travel.validation.Fields.instant;
import static com.example.travel.validation.Fields.money;
import static com.example.travel.validation.Fields.optRate;
import static com.example.travel.validation.Fields.text;

import com.example.travel.model.FeeLine;
import com.example.travel.model.Money;
import com.example.travel.model.TaxLine;
import com.example.travel.model.Transaction;
import com.example.travel.model.TransactionDraft;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class TransactionValidator {

    public static final Set<String> TYPES = Set.of("flight", "hotel", "rail", "navan_fee");
    private static final Set<String> PATCHABLE = Set.of("total", "occurred_at", "tax_lines", "fee_lines", "metadata");
    private static final Set<String> IMMUTABLE = Set.of("type", "external_id", "currency");
    private static final int MAX_LINES = 100;

    private final MetadataValidator metadataValidator;

    public TransactionValidator(MetadataValidator metadataValidator) {
        this.metadataValidator = metadataValidator;
    }

    // ------------------------------------------------------------------ cheap pre-checks (used before duplicate lookup)

    public String requireType(JsonNode body) {
        requireObject(body);
        String type = text(body, "type", "type", 32);
        if (!TYPES.contains(type)) {
            throw badRequest("unknown type '" + type + "'; expected one of flight, hotel, rail, navan_fee");
        }
        return type;
    }

    public String requireExternalId(JsonNode body) {
        requireObject(body);
        return text(body, "external_id", "external_id", 200);
    }

    // ------------------------------------------------------------------ create

    public TransactionDraft validateCreate(JsonNode body) {
        String type = requireType(body);
        String externalId = requireExternalId(body);
        String currency = currency(text(body, "currency", "currency", 3));
        int scale = scaleFor(currency);
        Instant occurredAt = instant(body, "occurred_at", "occurred_at");
        BigDecimal total = money(body, "total", "total", scale);
        List<TaxLine> tax = taxLines(body, scale);
        List<FeeLine> fees = feeLines(body, scale);
        var metadata = metadataValidator.validate(type, body.get("metadata"), scale);
        return new TransactionDraft(type, externalId, currency, occurredAt, total, tax, fees, metadata);
    }

    // ------------------------------------------------------------------ patch

    /** Partial update: omitted fields keep their stored value; supplied lists/metadata REPLACE the stored ones. */
    public TransactionDraft validatePatch(Transaction existing, JsonNode patch) {
        requireObject(patch);
        boolean any = false;
        for (Iterator<String> it = patch.fieldNames(); it.hasNext(); ) {
            String f = it.next();
            if (IMMUTABLE.contains(f)) {
                String current = switch (f) {
                    case "type" -> existing.type();
                    case "external_id" -> existing.externalId();
                    default -> existing.currency();
                };
                JsonNode v = patch.get(f);
                if (!v.isTextual() || !v.asText().equals(current)) throw badRequest(f + " is immutable");
            } else if (PATCHABLE.contains(f)) {
                any = true;
            } else {
                throw badRequest("unknown field '" + f + "'");
            }
        }
        if (!any) throw badRequest("no updatable field supplied (total, occurred_at, tax_lines, fee_lines, metadata)");

        int scale = scaleFor(existing.currency());
        Instant occurredAt = patch.has("occurred_at") ? instant(patch, "occurred_at", "occurred_at") : existing.occurredAt();
        BigDecimal total = patch.has("total") ? money(patch, "total", "total", scale) : existing.total();
        List<TaxLine> tax = patch.has("tax_lines") ? taxLines(patch, scale) : existing.taxLines();
        List<FeeLine> fees = patch.has("fee_lines") ? feeLines(patch, scale) : existing.feeLines();
        var metadata = patch.has("metadata")
                ? metadataValidator.validate(existing.type(), patch.get("metadata"), scale)
                : existing.metadata();
        return new TransactionDraft(existing.type(), existing.externalId(), existing.currency(),
                occurredAt, total, tax, fees, metadata);
    }

    // ------------------------------------------------------------------ pieces

    private static void requireObject(JsonNode body) {
        if (body == null || !body.isObject()) throw badRequest("request body must be a JSON object");
    }

    private static String currency(String code) {
        if (!code.matches("[A-Z]{3}")) throw badRequest("currency must be a 3-letter upper-case ISO-4217 code");
        scaleFor(code);
        return code;
    }

    private static int scaleFor(String currency) {
        try {
            return Money.scaleFor(currency);
        } catch (IllegalArgumentException e) {
            throw badRequest("unsupported currency '" + currency + "'");
        }
    }

    private static List<TaxLine> taxLines(JsonNode body, int scale) {
        ArrayNode arr = array(body, "tax_lines", "tax_lines", false, MAX_LINES);
        List<TaxLine> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            String p = "tax_lines[" + i + "]";
            JsonNode n = element(arr, i, p);
            out.add(new TaxLine(text(n, "name", p + ".name", 100),
                    optRate(n, "rate", p + ".rate"),
                    money(n, "amount", p + ".amount", scale)));
        }
        return List.copyOf(out);
    }

    private static List<FeeLine> feeLines(JsonNode body, int scale) {
        ArrayNode arr = array(body, "fee_lines", "fee_lines", false, MAX_LINES);
        List<FeeLine> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            String p = "fee_lines[" + i + "]";
            JsonNode n = element(arr, i, p);
            out.add(new FeeLine(text(n, "name", p + ".name", 100), money(n, "amount", p + ".amount", scale)));
        }
        return List.copyOf(out);
    }
}
