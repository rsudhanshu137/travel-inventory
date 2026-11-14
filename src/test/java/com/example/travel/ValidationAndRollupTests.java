package com.example.travel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.travel.model.ApiException;
import com.example.travel.model.InvoiceLine;
import com.example.travel.model.RollupCalculator;
import com.example.travel.model.TaxLine;
import com.example.travel.model.Transaction;
import com.example.travel.model.TransactionDraft;
import com.example.travel.validation.MetadataValidator;
import com.example.travel.validation.TransactionValidator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Fast, DB-free unit tests for the money / validation / rollup rules. */
class ValidationAndRollupTests {

    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final TransactionValidator validator = new TransactionValidator(new MetadataValidator());

    private JsonNode seeds() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/task-b/seed-payloads.json")) {
            return json.readTree(in);
        }
    }

    private static InvoiceLine line(TransactionDraft d) {
        return InvoiceLine.snapshotOf(new Transaction("t", d.type(), d.externalId(), d.currency(), d.occurredAt(),
                d.total(), d.taxLines(), d.feeLines(), d.metadata(), Instant.now(), Instant.now()));
    }

    @Test
    void doubleLiteralsInJsonDoNotLeakBinaryFloatNoise() throws Exception {
        TransactionDraft hotel = validator.validateCreate(seeds().get("hotel"));
        assertThat(hotel.total().toPlainString()).isEqualTo("331.70");
        assertThat(hotel.taxLines().get(0).amount().toPlainString()).isEqualTo("21.70");
        assertThat(hotel.taxLines().get(0).rate().toPlainString()).isEqualTo("0.07");
    }

    @Test
    void amountsAreExactToTheCurrencyMinorUnit() throws Exception {
        ObjectNode tooPrecise = seeds().get("flight").deepCopy();
        tooPrecise.put("total", 220.005);
        assertThatThrownBy(() -> validator.validateCreate(tooPrecise)).isInstanceOf(ApiException.class);

        ObjectNode yen = seeds().get("trip_fee").deepCopy();
        yen.put("currency", "JPY");
        yen.put("total", 2500);
        assertThat(validator.validateCreate(yen).total().toPlainString()).isEqualTo("2500");
        yen.put("total", 2500.5);
        assertThatThrownBy(() -> validator.validateCreate(yen)).isInstanceOf(ApiException.class);
    }

    @Test
    void sumOfManyTenthsIsExact() throws Exception {
        List<InvoiceLine> lines = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ObjectNode fee = seeds().get("trip_fee").deepCopy();
            fee.put("total", 0.1);
            fee.put("external_id", "x" + i);
            lines.add(line(validator.validateCreate(fee)));
        }
        assertThat(RollupCalculator.grandTotal(lines, 2).toPlainString()).isEqualTo("1.00");   // 0.1 * 10 in doubles is 0.999999...
    }

    @Test
    void taxRollupGroupsByNameAndNormalisedRate() throws Exception {
        List<InvoiceLine> lines = new ArrayList<>();
        for (String rate : new String[] {"0.07", "0.070", null}) {
            ObjectNode hotel = seeds().get("hotel").deepCopy();
            hotel.put("external_id", "h" + rate);
            ObjectNode tax = (ObjectNode) hotel.get("tax_lines").get(0);
            tax.remove("rate");
            if (rate != null) tax.put("rate", new BigDecimal(rate));
            lines.add(line(validator.validateCreate(hotel)));
        }
        List<TaxLine> rollup = RollupCalculator.taxRollup(lines, 2);
        assertThat(rollup).hasSize(2);
        assertThat(rollup.get(0).rate()).isNull();
        assertThat(rollup.get(0).amount().toPlainString()).isEqualTo("21.70");
        assertThat(rollup.get(1).amount().toPlainString()).isEqualTo("43.40");
    }

    @Test
    void feeRollupIgnoresNavanFeeTransactions() throws Exception {
        List<InvoiceLine> lines = List.of(
                line(validator.validateCreate(seeds().get("flight"))),
                line(validator.validateCreate(seeds().get("trip_fee"))));
        assertThat(RollupCalculator.feeRollup(lines, 2)).hasSize(1);
        assertThat(RollupCalculator.feeRollup(lines, 2).get(0).name()).isEqualTo("YQ");
        assertThat(RollupCalculator.totals(lines, 2).keySet()).containsExactly("flight", "trip_fee", "grand_total");
    }

    @Test
    void structuralValidation() throws Exception {
        for (String field : List.of("type", "external_id", "currency", "occurred_at", "total", "tax_lines", "fee_lines", "metadata")) {
            ObjectNode body = seeds().get("flight").deepCopy();
            body.remove(field);
            assertThatThrownBy(() -> validator.validateCreate(body)).as("missing " + field).isInstanceOf(ApiException.class);
        }
        ObjectNode noCoupons = seeds().get("flight").deepCopy();
        ((ObjectNode) noCoupons.get("metadata")).remove("coupons");
        assertThatThrownBy(() -> validator.validateCreate(noCoupons)).isInstanceOf(ApiException.class);

        ObjectNode noLegs = seeds().get("rail").deepCopy();
        ((ObjectNode) noLegs.get("metadata")).remove("legs");
        assertThatThrownBy(() -> validator.validateCreate(noLegs)).isInstanceOf(ApiException.class);

        ObjectNode noAddress = seeds().get("hotel").deepCopy();
        ((ObjectNode) noAddress.get("metadata")).remove("address");
        assertThatThrownBy(() -> validator.validateCreate(noAddress)).isInstanceOf(ApiException.class);
    }
}
