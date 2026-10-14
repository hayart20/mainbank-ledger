package com.mainbank.gateway.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiMergerTest {

    private final ObjectMapper json = new ObjectMapper();
    private final OpenApiMerger merger = new OpenApiMerger(json);

    private JsonNode spec(String text) throws Exception {
        return json.readTree(text);
    }

    @Test
    void combinesPathsTagsAndSchemasOfAllServices() throws Exception {
        Map<String, JsonNode> specs = new LinkedHashMap<>();
        specs.put("ledger", spec("""
                {"tags":[{"name":"Accounts"}],
                 "paths":{"/api/v1/accounts":{"post":{}}},
                 "components":{"schemas":{"AccountResponse":{"type":"object"},"ProblemDetail":{"type":"object"}}}}"""));
        specs.put("receipt", spec("""
                {"tags":[{"name":"Receipts"}],
                 "paths":{"/api/v1/receipts/{id}":{"get":{}}},
                 "components":{"schemas":{"ReceiptLinkResponse":{"type":"object"},"ProblemDetail":{"type":"object"}}}}"""));

        JsonNode merged = merger.merge(specs, List.of());

        assertThat(merged.path("paths").fieldNames()).toIterable()
                .containsExactly("/api/v1/accounts", "/api/v1/receipts/{id}");
        assertThat(merged.path("tags")).extracting(t -> t.path("name").asText()).containsExactly("Accounts", "Receipts");
        assertThat(merged.path("components").path("schemas").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("AccountResponse", "ReceiptLinkResponse", "ProblemDetail");
        assertThat(merged.path("servers").get(0).path("url").asText()).isEqualTo("/");
    }

    @Test
    void sameNamedSchemaKeepsTheFirstAndTagIsNotDuplicated() throws Exception {
        Map<String, JsonNode> specs = new LinkedHashMap<>();
        specs.put("a", spec("""
                {"tags":[{"name":"T"}],"paths":{},"components":{"schemas":{"X":{"description":"first"}}}}"""));
        specs.put("b", spec("""
                {"tags":[{"name":"T"}],"paths":{},"components":{"schemas":{"X":{"description":"second"}}}}"""));

        JsonNode merged = merger.merge(specs, List.of());

        assertThat(merged.path("tags")).hasSize(1);
        assertThat(merged.path("components").path("schemas").path("X").path("description").asText()).isEqualTo("first");
    }

    @Test
    void namesUnavailableServicesInTheDescription() throws Exception {
        Map<String, JsonNode> specs = Map.of("ledger", spec("{\"paths\":{}}"));

        JsonNode merged = merger.merge(specs, List.of("receipt-service"));

        assertThat(merged.path("info").path("description").asText())
                .contains("Currently unavailable").contains("receipt-service");
        assertThat(merged.has("components")).isFalse();
    }
}
