package com.example.travel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Black-box tests over real HTTP against a real file-backed H2 database (never jdbc:h2:mem:).
 * Test numbers 1..10 map to the numbered list in the assignment brief.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TravelApiTests {

    private static final Path DB_DIR = newTempDir();
    // Keep "685.70" as 685.70 when parsing responses (Jackson would otherwise strip trailing zeros -> 685.7),
    // so assertions can check that money is rendered at the currency's scale.
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();
    private static final HttpClient HTTP = HttpClient.newHttpClient(); // java.net.http supports PATCH

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:file:" + DB_DIR.resolve("travel-test").toAbsolutePath() + ";LOCK_TIMEOUT=20000");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    private JsonNode seeds;
    private JsonNode expected;

    private static Path newTempDir() {
        try {
            return Files.createTempDirectory("travel-inventory-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void cleanDatabase() throws IOException {
        jdbc.update("DELETE FROM invoices");      // snapshot lines + their tax/fee lines cascade
        jdbc.update("DELETE FROM transactions");  // child tables cascade (RESTRICT is satisfied: invoices are gone)
        seeds = fixture("seed-payloads.json");
        expected = fixture("expected-invoice.json");
    }

    // ============================================================ 1. create all types, GET round-trips structure

    @Test
    void t01_createsFlightHotelRailAndBothNavanFees_andGetReturnsStructure() throws Exception {
        Resp flight = post("/transactions", seeds.get("flight"));
        assertThat(flight.status).isEqualTo(201);
        String flightId = flight.json.get("id").asText();
        assertThat(flightId).startsWith("txn_");

        JsonNode f = get("/transactions/" + flightId).json;
        assertThat(f.get("type").asText()).isEqualTo("flight");
        assertThat(f.get("metadata").get("origin").asText()).isEqualTo("TXL");
        assertThat(f.get("metadata").get("destination").asText()).isEqualTo("LHR");
        assertThat(dec(f.get("metadata").get("fare"))).isEqualByComparingTo("180");
        assertThat(f.get("metadata").get("coupons")).hasSize(1);
        assertThat(f.get("metadata").get("coupons").get(0).get("flight_number").asText()).isEqualTo("BA982");
        assertThat(f.get("tax_lines").get(0).get("name").asText()).isEqualTo("Airport tax");
        assertThat(f.get("fee_lines").get(0).get("name").asText()).isEqualTo("YQ");
        assertThat(f.get("tax_lines").get(0).has("rate")).isFalse();

        JsonNode h = get("/transactions/" + id("hotel")).json;
        assertThat(h.get("metadata").get("name").asText()).isEqualTo("Hotel Mitte London");
        assertThat(h.get("metadata").get("address").get("street").asText()).isEqualTo("12 Kingsway");
        assertThat(h.get("metadata").get("address").get("city").asText()).isEqualTo("London");
        assertThat(h.get("metadata").get("address").get("country").asText()).isEqualTo("GB");
        assertThat(h.get("metadata").get("nights").asInt()).isEqualTo(2);
        assertThat(h.get("metadata").get("extras").get(0).get("name").asText()).isEqualTo("Breakfast");
        assertThat(dec(h.get("tax_lines").get(0).get("rate"))).isEqualByComparingTo("0.07");
        assertThat(h.get("fee_lines")).isEmpty();

        JsonNode r = get("/transactions/" + id("rail")).json;
        assertThat(r.get("metadata").get("legs")).hasSize(2);
        assertThat(r.get("metadata").get("legs").get(0).get("train_number").asText()).isEqualTo("EST9108");
        assertThat(r.get("metadata").get("legs").get(1).get("destination").asText()).isEqualTo("Paris Nord");
        assertThat(r.get("metadata").get("legs").get(1).get("class").asText()).isEqualTo("standard");

        JsonNode trip = get("/transactions/" + id("trip_fee")).json;
        assertThat(trip.get("type").asText()).isEqualTo("navan_fee");
        assertThat(trip.get("metadata").get("fee_kind").asText()).isEqualTo("trip_fee");
        assertThat(trip.get("metadata").get("related_external_id").asText()).isEqualTo("flight-TXL-LHR-2026-03-11");

        JsonNode call = get("/transactions/" + id("agent_call_fee")).json;
        assertThat(call.get("metadata").get("fee_kind").asText()).isEqualTo("agent_call_fee");
        assertThat(dec(call.get("total"))).isEqualByComparingTo("15");

        assertThat(get("/transactions/txn_nope").status).isEqualTo(404);
    }

    @Test
    void t01b_invalidInputIsRejectedWith400_andNothingIsStored() throws Exception {
        ObjectNode unknownType = seeds.get("flight").deepCopy();
        unknownType.put("type", "boat");
        assertThat(post("/transactions", unknownType).status).isEqualTo(400);

        ObjectNode unknownFeeKind = seeds.get("trip_fee").deepCopy();
        ((ObjectNode) unknownFeeKind.get("metadata")).put("fee_kind", "coffee_fee");
        assertThat(post("/transactions", unknownFeeKind).status).isEqualTo(400);

        ObjectNode emptyMetadata = seeds.get("flight").deepCopy();
        emptyMetadata.set("metadata", JSON.createObjectNode());
        assertThat(post("/transactions", emptyMetadata).status).isEqualTo(400);

        ObjectNode flightAsHotel = seeds.get("flight").deepCopy();
        flightAsHotel.set("metadata", seeds.get("hotel").get("metadata").deepCopy());
        assertThat(post("/transactions", flightAsHotel).status).isEqualTo(400);

        ObjectNode tooPrecise = seeds.get("flight").deepCopy();
        tooPrecise.put("total", 220.005);
        assertThat(post("/transactions", tooPrecise).status).isEqualTo(400);

        assertThat(call("POST", "/transactions", "{not json").status).isEqualTo(400);
        assertThat(count("transactions")).isZero();
    }

    // ============================================================ 2. duplicate external_id

    @Test
    void t02_duplicateExternalId_isRejectedWith409AndExistingId_andOnlyOneRowExists() throws Exception {
        Resp first = post("/transactions", seeds.get("flight"));
        assertThat(first.status).isEqualTo(201);
        String existingId = first.json.get("id").asText();

        // identical body
        Resp same = post("/transactions", seeds.get("flight"));
        assertThat(same.status).isEqualTo(409);
        assertThat(same.json.get("existing_id").asText()).isEqualTo(existingId);
        assertThat(same.json.get("id").asText()).isEqualTo(existingId);

        // the brief's duplicate fixture: same external_id, different (thinner) body -- still a duplicate, not a 400
        Resp dup = post("/transactions", seeds.get("duplicate_flight"));
        assertThat(dup.status).isEqualTo(409);
        assertThat(dup.json.get("existing_id").asText()).isEqualTo(existingId);

        assertThat(countWhere("transactions", "external_id = 'flight-TXL-LHR-2026-03-11'")).isEqualTo(1);
        assertThat(get("/transactions?external_id=flight-TXL-LHR-2026-03-11").json.get("items")).hasSize(1);
        assertThat(count("flight_coupons")).isEqualTo(1);       // no orphan child rows from the rejected attempts
        assertThat(count("transaction_tax_lines")).isEqualTo(1);
    }

    // ============================================================ 3. trip invoice matches expected-invoice.json

    @Test
    void t03_tripInvoice_totalsAndRollupsMatchExpectedInvoiceJson() throws Exception {
        Resp resp = createTripInvoice();
        assertThat(resp.status).isEqualTo(201);
        JsonNode inv = get("/invoices/" + resp.json.get("id").asText()).json;

        assertThat(inv.get("currency").asText()).isEqualTo(expected.get("currency").asText());
        assertThat(inv.get("line_count").asInt()).isGreaterThanOrEqualTo(expected.get("line_count_minimum").asInt());
        assertThat(dec(inv.get("total"))).isEqualByComparingTo("685.70");
        assertThat(inv.get("total").asText()).isEqualTo("685.70");   // money is rendered at currency scale

        JsonNode want = expected.get("totals");
        JsonNode totals = inv.get("totals");
        for (String key : List.of("flight", "hotel", "rail", "trip_fee", "agent_call_fee", "grand_total")) {
            assertThat(dec(totals.get(key))).as("totals." + key).isEqualByComparingTo(dec(want.get(key)));
        }

        List<String> types = new ArrayList<>();
        inv.get("lines").forEach(l -> types.add(l.get("type").asText()));
        assertThat(types).containsExactly("flight", "hotel", "rail", "navan_fee", "navan_fee");

        // tax rollup: Airport tax 15.00 (no rate) + VAT 0.07 -> 21.70
        JsonNode tax = inv.get("tax_rollup");
        assertThat(tax).hasSize(2);
        assertThat(find(tax, "Airport tax").has("rate")).isFalse();
        assertThat(dec(find(tax, "Airport tax").get("amount"))).isEqualByComparingTo("15");
        assertThat(dec(find(tax, "VAT").get("rate"))).isEqualByComparingTo("0.07");
        assertThat(dec(find(tax, "VAT").get("amount"))).isEqualByComparingTo("21.7");

        // fee rollup: carrier/merchant fee_lines only. The 25.00 Navan trip fee is a TRANSACTION, not a fee line.
        JsonNode fee = inv.get("fee_rollup");
        assertThat(fee).hasSize(2);
        assertThat(dec(find(fee, "YQ").get("amount"))).isEqualByComparingTo("25");
        assertThat(dec(find(fee, "Booking fee").get("amount"))).isEqualByComparingTo("4");

        // snapshot lines carry the type-specific structure
        JsonNode flightLine = inv.get("lines").get(0);
        assertThat(flightLine.get("metadata").get("coupons").get(0).get("flight_number").asText()).isEqualTo("BA982");
        assertThat(inv.get("lines").get(1).get("metadata").get("address").get("city").asText()).isEqualTo("London");
        assertThat(inv.get("lines").get(2).get("metadata").get("legs")).hasSize(2);
        assertThat(inv.get("lines").get(3).get("metadata").get("fee_kind").asText()).isEqualTo("trip_fee");

        // single-id shape also works
        Resp single = post("/invoices", JSON.createObjectNode().put("transaction_id", id("hotel")));
        assertThat(single.status).isEqualTo(201);
        assertThat(dec(single.json.get("total"))).isEqualByComparingTo("331.7");
    }

    // ============================================================ 4. snapshot is frozen

    @Test
    void t04_snapshot_invoiceStillShowsFlight220_afterTheStoredRowIsChanged() throws Exception {
        Resp invoice = createTripInvoice();
        String invoiceId = invoice.json.get("id").asText();
        String flightId = id("flight");

        // The API refuses to PATCH a billed transaction (see t07), so to prove the snapshot is not a live join we
        // change the stored row underneath it, as a back-office script or a bug would.
        jdbc.update("UPDATE transactions SET total = 999.00 WHERE id = ?", flightId);
        jdbc.update("UPDATE transaction_tax_lines SET amount = 500.00 WHERE transaction_id = ?", flightId);
        jdbc.update("UPDATE flight_coupons SET flight_number = 'XX000' WHERE transaction_id = ?", flightId);

        assertThat(dec(get("/transactions/" + flightId).json.get("total"))).isEqualByComparingTo("999");   // live row changed
        JsonNode inv = get("/invoices/" + invoiceId).json;
        JsonNode flightLine = inv.get("lines").get(0);
        assertThat(flightLine.get("total").asText()).isEqualTo("220.00");
        assertThat(dec(flightLine.get("tax_lines").get(0).get("amount"))).isEqualByComparingTo("15");
        assertThat(flightLine.get("metadata").get("coupons").get(0).get("flight_number").asText()).isEqualTo("BA982");
        assertThat(dec(inv.get("totals").get("flight"))).isEqualByComparingTo("220");
        assertThat(dec(inv.get("total"))).isEqualByComparingTo("685.70");
    }

    // ============================================================ 5. mixed currency

    @Test
    void t05_mixedCurrencyInvoice_returns409_andCreatesNothing() throws Exception {
        String eurId = post("/transactions", seeds.get("flight")).json.get("id").asText();
        ObjectNode usd = seeds.get("trip_fee").deepCopy();
        usd.put("currency", "USD");
        String usdId = post("/transactions", usd).json.get("id").asText();

        Resp resp = post("/invoices", JSON.createObjectNode().set("transaction_ids", ids(eurId, usdId)));
        assertThat(resp.status).isEqualTo(409);
        assertThat(resp.json.get("error").asText()).isEqualTo("currency_mismatch");
        assertThat(count("invoices")).isZero();
        assertThat(count("invoice_lines")).isZero();
    }

    // ============================================================ 6. concurrent duplicate create

    @Test
    void t06_concurrentDuplicateCreate_leavesExactlyOneRow() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        String body = JSON.writeValueAsString(seeds.get("hotel"));
        List<Future<Resp>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return call("POST", "/transactions", body);
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();

        int created = 0;
        int conflicts = 0;
        List<String> existingIds = new ArrayList<>();
        for (Future<Resp> f : futures) {
            Resp r = f.get(60, TimeUnit.SECONDS);
            if (r.status == 201) created++;
            else if (r.status == 409) {
                conflicts++;
                existingIds.add(r.json.get("existing_id").asText());
            }
        }
        pool.shutdownNow();

        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        assertThat(countWhere("transactions", "external_id = 'hotel-LON-2026-03-11'")).isEqualTo(1);
        assertThat(count("transaction_tax_lines")).isEqualTo(1);   // no half-written twins
        String winner = jdbc.queryForObject("SELECT id FROM transactions WHERE external_id = 'hotel-LON-2026-03-11'", String.class);
        assertThat(existingIds).allMatch(winner::equals);          // every loser was told the SAME existing id
    }

    @Test
    void t06b_concurrentPatchesOfOneTransaction_neverTearTheTaxList() throws Exception {
        String id = post("/transactions", seeds.get("flight")).json.get("id").asText();
        ObjectNode patchA = JSON.createObjectNode();
        ArrayNode a = patchA.putArray("tax_lines");
        for (int i = 0; i < 5; i++) a.addObject().put("name", "A" + i).put("amount", 1);
        ObjectNode patchB = JSON.createObjectNode();
        patchB.putArray("tax_lines").addObject().put("name", "B0").put("amount", 2);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Resp>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String body = JSON.writeValueAsString(i % 2 == 0 ? patchA : patchB);
            futures.add(pool.submit(() -> {
                go.await();
                return call("PATCH", "/transactions/" + id, body);
            }));
        }
        go.countDown();
        for (Future<Resp> f : futures) assertThat(f.get(60, TimeUnit.SECONDS).status).isEqualTo(200);
        pool.shutdownNow();

        List<String> names = jdbc.queryForList(
                "SELECT name FROM transaction_tax_lines WHERE transaction_id = ? ORDER BY position", String.class, id);
        boolean wholeA = names.equals(List.of("A0", "A1", "A2", "A3", "A4"));
        boolean wholeB = names.equals(List.of("B0"));
        assertThat(wholeA || wholeB).as("tax lines were %s", names).isTrue();
        assertThat(get("/transactions/" + id).json.get("tax_lines")).hasSize(names.size());
    }

    // ============================================================ 7. PATCH after invoiced

    @Test
    void t07_patchTransactionOnAnInvoice_returns409_andInvoiceIsUnchanged() throws Exception {
        Resp invoice = createTripInvoice();
        String invoiceId = invoice.json.get("id").asText();
        String before = JSON.writeValueAsString(get("/invoices/" + invoiceId).json);
        String flightId = id("flight");

        ObjectNode patch = JSON.createObjectNode();
        patch.put("total", 999.0);
        Resp resp = call("PATCH", "/transactions/" + flightId, JSON.writeValueAsString(patch));
        assertThat(resp.status).isEqualTo(409);
        assertThat(resp.json.get("error").asText()).isEqualTo("transaction_invoiced");

        assertThat(dec(get("/transactions/" + flightId).json.get("total"))).isEqualByComparingTo("220");  // persisted nothing
        assertThat(JSON.writeValueAsString(get("/invoices/" + invoiceId).json)).isEqualTo(before);
        assertThat(dec(get("/invoices/" + invoiceId).json.get("total"))).isEqualByComparingTo("685.70");
    }

    // ============================================================ 8. DELETE after invoiced

    @Test
    void t08_deleteTransactionOnAnInvoice_returns409_andRowStillExists() throws Exception {
        createTripInvoice();
        String railId = id("rail");
        Resp resp = call("DELETE", "/transactions/" + railId, null);
        assertThat(resp.status).isEqualTo(409);
        assertThat(get("/transactions/" + railId).status).isEqualTo(200);
        assertThat(countWhere("rail_legs", "transaction_id = '" + railId + "'")).isEqualTo(2);
    }

    // ============================================================ 9. DELETE invoice

    @Test
    void t09_deleteInvoice_removesItAndItsSnapshot_butKeepsTheTransactions() throws Exception {
        Resp invoice = createTripInvoice();
        String invoiceId = invoice.json.get("id").asText();
        assertThat(count("invoice_lines")).isEqualTo(5);

        assertThat(call("DELETE", "/invoices/" + invoiceId, null).status).isEqualTo(204);
        assertThat(get("/invoices/" + invoiceId).status).isEqualTo(404);
        assertThat(count("invoice_lines")).isZero();
        assertThat(count("invoice_line_tax_lines")).isZero();
        assertThat(count("invoice_line_fee_lines")).isZero();
        for (String key : List.of("flight", "hotel", "rail", "trip_fee", "agent_call_fee")) {
            assertThat(get("/transactions/" + id(key)).status).as(key).isEqualTo(200);
        }
        assertThat(call("DELETE", "/invoices/" + invoiceId, null).status).isEqualTo(404);

        // and now that nothing bills them, the transactions are editable / deletable again
        assertThat(call("DELETE", "/transactions/" + id("flight"), null).status).isEqualTo(204);
    }

    // ============================================================ 10. pagination

    @Test
    void t10_listTransactions_isPaginated_defaultsTo50_andRejectsLimitOver100() throws Exception {
        for (int i = 0; i < 55; i++) {
            ObjectNode fee = seeds.get("trip_fee").deepCopy();
            fee.put("external_id", "bulk-" + i);
            assertThat(post("/transactions", fee).status).isEqualTo(201);
        }
        JsonNode page = get("/transactions").json;
        assertThat(page.get("limit").asInt()).isEqualTo(50);
        assertThat(page.get("items")).hasSize(50);
        assertThat(page.get("next_offset").asInt()).isEqualTo(50);

        JsonNode second = get("/transactions?offset=50").json;
        assertThat(second.get("items")).hasSize(5);
        assertThat(second.has("next_offset")).isFalse();                 // last page

        assertThat(get("/transactions?limit=1000").status).isEqualTo(400);
        assertThat(get("/transactions?limit=101").status).isEqualTo(400);
        assertThat(get("/transactions?limit=0").status).isEqualTo(400);
        assertThat(get("/transactions?offset=-1").status).isEqualTo(400);
        assertThat(get("/transactions?limit=abc").status).isEqualTo(400);
        assertThat(get("/transactions?limit=100").json.get("items")).hasSize(55);

        assertThat(get("/invoices?limit=1000").status).isEqualTo(400);
        assertThat(get("/invoices").json.get("limit").asInt()).isEqualTo(50);
    }

    // ============================================================ extras beyond the required ten

    @Test
    void patchTransaction_whenNotInvoiced_replacesFieldsAtomically_andRevalidates() throws Exception {
        String id = post("/transactions", seeds.get("flight")).json.get("id").asText();

        ObjectNode patch = JSON.createObjectNode();
        patch.put("total", 230.5);
        ObjectNode fee = patch.putArray("fee_lines").addObject();
        fee.put("name", "Fuel").put("amount", 10);
        Resp ok = call("PATCH", "/transactions/" + id, JSON.writeValueAsString(patch));
        assertThat(ok.status).isEqualTo(200);
        assertThat(ok.json.get("total").asText()).isEqualTo("230.50");
        assertThat(ok.json.get("fee_lines")).hasSize(1);
        assertThat(ok.json.get("fee_lines").get(0).get("name").asText()).isEqualTo("Fuel");
        assertThat(ok.json.get("tax_lines")).hasSize(1);                                      // untouched list kept
        assertThat(ok.json.get("metadata").get("coupons")).hasSize(1);

        // metadata is replaced as a whole and validated for the transaction's own type
        ObjectNode newMeta = JSON.createObjectNode();
        newMeta.set("metadata", seeds.get("flight").get("metadata").deepCopy());
        ArrayNode coupons = (ArrayNode) newMeta.get("metadata").get("coupons");
        coupons.addObject().put("flight_number", "BA983").put("from", "LHR").put("to", "TXL")
                .put("departure", "2026-03-13T10:00:00Z").put("arrival", "2026-03-13T12:50:00Z");
        Resp two = call("PATCH", "/transactions/" + id, JSON.writeValueAsString(newMeta));
        assertThat(two.status).isEqualTo(200);
        assertThat(two.json.get("metadata").get("coupons")).hasSize(2);
        assertThat(two.json.get("metadata").get("coupons").get(1).get("flight_number").asText()).isEqualTo("BA983");

        ObjectNode hotelMeta = JSON.createObjectNode();
        hotelMeta.set("metadata", seeds.get("hotel").get("metadata").deepCopy());
        assertThat(call("PATCH", "/transactions/" + id, JSON.writeValueAsString(hotelMeta)).status).isEqualTo(400);
        assertThat(call("PATCH", "/transactions/" + id, "{\"currency\":\"USD\"}").status).isEqualTo(400);
        assertThat(call("PATCH", "/transactions/" + id, "{}").status).isEqualTo(400);
        assertThat(call("PATCH", "/transactions/txn_missing", "{\"total\":1}").status).isEqualTo(404);
        assertThat(get("/transactions/" + id).json.get("metadata").get("coupons")).hasSize(2);  // 400s changed nothing
    }

    @Test
    void deleteTransaction_whenNotInvoiced_removesRowAndChildren() throws Exception {
        String railId = post("/transactions", seeds.get("rail")).json.get("id").asText();
        String flightId = post("/transactions", seeds.get("flight")).json.get("id").asText();
        assertThat(call("DELETE", "/transactions/" + railId, null).status).isEqualTo(204);
        assertThat(get("/transactions/" + railId).status).isEqualTo(404);
        assertThat(count("rail_legs")).isZero();
        assertThat(count("transaction_fee_lines")).isEqualTo(1);   // the flight's YQ line survives
        assertThat(get("/transactions/" + flightId).status).isEqualTo(200);
        assertThat(call("DELETE", "/transactions/" + railId, null).status).isEqualTo(404);
    }

    @Test
    void invoiceValidation_missingIds404_duplicateIds409_badBodies400() throws Exception {
        String flightId = post("/transactions", seeds.get("flight")).json.get("id").asText();

        assertThat(post("/invoices", JSON.createObjectNode().put("transaction_id", "txn_nope")).status).isEqualTo(404);
        assertThat(post("/invoices", JSON.createObjectNode().set("transaction_ids", ids(flightId, "txn_nope"))).status).isEqualTo(404);
        Resp dup = post("/invoices", JSON.createObjectNode().set("transaction_ids", ids(flightId, flightId)));
        assertThat(dup.status).isEqualTo(409);
        assertThat(dup.json.get("error").asText()).isEqualTo("duplicate_transaction_id");
        assertThat(post("/invoices", JSON.createObjectNode().set("transaction_ids", JSON.createArrayNode())).status).isEqualTo(400);
        assertThat(post("/invoices", JSON.createObjectNode()).status).isEqualTo(400);
        assertThat(count("invoices")).isZero();
        assertThat(get("/invoices/inv_nope").status).isEqualTo(404);
    }

    @Test
    void patchInvoice_replacesTheSetAndRebuildsTheSnapshot_atomically() throws Exception {
        String flightId = post("/transactions", seeds.get("flight")).json.get("id").asText();
        String hotelId = post("/transactions", seeds.get("hotel")).json.get("id").asText();
        ObjectNode usd = seeds.get("trip_fee").deepCopy();
        usd.put("currency", "USD");
        String usdId = post("/transactions", usd).json.get("id").asText();

        String invoiceId = post("/invoices", JSON.createObjectNode().put("transaction_id", flightId)).json.get("id").asText();
        assertThat(dec(get("/invoices/" + invoiceId).json.get("total"))).isEqualByComparingTo("220");

        Resp patched = call("PATCH", "/invoices/" + invoiceId,
                JSON.writeValueAsString(JSON.createObjectNode().set("transaction_ids", ids(flightId, hotelId))));
        assertThat(patched.status).isEqualTo(200);
        assertThat(patched.json.get("id").asText()).isEqualTo(invoiceId);
        assertThat(dec(patched.json.get("total"))).isEqualByComparingTo("551.70");
        assertThat(patched.json.get("lines")).hasSize(2);
        assertThat(patched.json.get("tax_rollup")).hasSize(2);
        assertThat(count("invoice_lines")).isEqualTo(2);

        // rebuild picks up CURRENT stored values (a stored change made out-of-band, as in t04)
        jdbc.update("UPDATE transactions SET total = 200.00 WHERE id = ?", flightId);
        Resp rebuilt = call("PATCH", "/invoices/" + invoiceId,
                JSON.writeValueAsString(JSON.createObjectNode().set("transaction_ids", ids(hotelId, flightId))));
        assertThat(dec(rebuilt.json.get("total"))).isEqualByComparingTo("531.70");
        assertThat(rebuilt.json.get("lines").get(0).get("type").asText()).isEqualTo("hotel");

        // mixed currency / missing ids: 409 / 404 and the invoice is left exactly as it was
        String before = JSON.writeValueAsString(get("/invoices/" + invoiceId).json);
        assertThat(call("PATCH", "/invoices/" + invoiceId,
                JSON.writeValueAsString(JSON.createObjectNode().set("transaction_ids", ids(flightId, usdId)))).status).isEqualTo(409);
        assertThat(call("PATCH", "/invoices/" + invoiceId,
                JSON.writeValueAsString(JSON.createObjectNode().set("transaction_ids", ids(flightId, "txn_nope")))).status).isEqualTo(404);
        assertThat(call("PATCH", "/invoices/inv_nope",
                JSON.writeValueAsString(JSON.createObjectNode().put("transaction_id", flightId))).status).isEqualTo(404);
        assertThat(JSON.writeValueAsString(get("/invoices/" + invoiceId).json)).isEqualTo(before);
        assertThat(count("invoice_lines")).isEqualTo(2);
    }

    @Test
    void aTransactionMayBeOnSeveralInvoices_andStaysLockedUntilAllAreGone() throws Exception {
        String flightId = post("/transactions", seeds.get("flight")).json.get("id").asText();
        String inv1 = post("/invoices", JSON.createObjectNode().put("transaction_id", flightId)).json.get("id").asText();
        String inv2 = post("/invoices", JSON.createObjectNode().put("transaction_id", flightId)).json.get("id").asText();

        assertThat(call("DELETE", "/invoices/" + inv1, null).status).isEqualTo(204);
        assertThat(call("DELETE", "/transactions/" + flightId, null).status).isEqualTo(409);   // still on inv2
        assertThat(call("DELETE", "/invoices/" + inv2, null).status).isEqualTo(204);
        assertThat(call("DELETE", "/transactions/" + flightId, null).status).isEqualTo(204);
    }

    @Test
    void listInvoices_returnsBoundedSummaries() throws Exception {
        createTripInvoice();
        JsonNode page = get("/invoices").json;
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("line_count").asInt()).isEqualTo(5);
        assertThat(page.get("items").get(0).get("total").asText()).isEqualTo("685.70");
    }

    @Test
    void health_isUp() throws Exception {
        Resp r = get("/health");
        assertThat(r.status).isEqualTo(200);
        assertThat(r.json.get("status").asText()).isEqualTo("UP");
    }

    // ============================================================ helpers

    private Resp createTripInvoice() throws Exception {
        List<String> order = List.of("flight", "hotel", "rail", "trip_fee", "agent_call_fee");
        ArrayNode idList = JSON.createArrayNode();
        for (String key : order) idList.add(id(key));
        return post("/invoices", JSON.createObjectNode().set("transaction_ids", idList));
    }

    /** Creates the seed transaction on first use (idempotent within one test) and returns its server id. */
    private String id(String seedKey) throws Exception {
        String externalId = seeds.get(seedKey).get("external_id").asText();
        List<String> existing = jdbc.queryForList("SELECT id FROM transactions WHERE external_id = ?", String.class, externalId);
        if (!existing.isEmpty()) return existing.get(0);
        Resp r = post("/transactions", seeds.get(seedKey));
        assertThat(r.status).as("seed " + seedKey).isEqualTo(201);
        return r.json.get("id").asText();
    }

    private static ArrayNode ids(String... ids) {
        ArrayNode a = JSON.createArrayNode();
        for (String i : ids) a.add(i);
        return a;
    }

    private static JsonNode find(JsonNode array, String name) {
        for (JsonNode n : array) if (name.equals(n.get("name").asText())) return n;
        throw new AssertionError("no rollup entry named " + name + " in " + array);
    }

    private static BigDecimal dec(JsonNode n) {
        return new BigDecimal(n.asText());
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private int countWhere(String table, String where) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
        return n == null ? 0 : n;
    }

    private static JsonNode fixture(String name) throws IOException {
        try (InputStream in = TravelApiTests.class.getResourceAsStream("/fixtures/task-b/" + name)) {
            return JSON.readTree(in);
        }
    }

    private record Resp(int status, JsonNode json) {}

    private Resp post(String path, JsonNode body) throws Exception {
        return call("POST", path, JSON.writeValueAsString(body));
    }

    private Resp get(String path) throws Exception {
        return call("GET", path, null);
    }

    private Resp call(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode json = r.body() == null || r.body().isBlank() ? null : JSON.readTree(r.body());
        return new Resp(r.statusCode(), json);
    }
}
