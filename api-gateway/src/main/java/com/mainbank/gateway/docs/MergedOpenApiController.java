package com.mainbank.gateway.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Hidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * One OpenAPI document for the whole system at {@code /docs/all/v3/api-docs}, built on request from the specs the
 * services publish (the same URLs the routes use) plus the gateway's own. A service that is down is skipped and
 * named in the description, so the page still works for the rest.
 */
@RestController
@Hidden
class MergedOpenApiController {

    private static final Logger log = LoggerFactory.getLogger(MergedOpenApiController.class);

    private final Map<String, String> specUrls = new LinkedHashMap<>();
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper json;
    private final OpenApiMerger merger;

    MergedOpenApiController(
            @Value("${gateway.ledger-url:http://localhost:8080}") String ledger,
            @Value("${gateway.receipt-url:http://localhost:8081}") String receipt,
            @Value("${gateway.statement-url:http://localhost:8083}") String statement,
            @Value("${server.port:8080}") int ownPort,
            // springdoc builds a service's spec lazily on first request, which takes longer than a health probe
            @Value("${gateway.docs-timeout:10s}") Duration timeout,
            ObjectMapper json) {
        // the order here is the order of the groups in Swagger UI
        specUrls.put("ledger-service", ledger + "/v3/api-docs");
        specUrls.put("statement-service", statement + "/v3/api-docs");
        specUrls.put("receipt-service", receipt + "/v3/api-docs");
        specUrls.put("api-gateway", "http://localhost:" + ownPort + "/v3/api-docs");
        this.timeout = timeout;
        this.json = json;
        this.merger = new OpenApiMerger(json);
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @GetMapping(value = "/docs/all/v3/api-docs", produces = MediaType.APPLICATION_JSON_VALUE)
    JsonNode all() {
        Map<String, CompletableFuture<JsonNode>> pending = new LinkedHashMap<>();
        specUrls.forEach((service, url) -> pending.put(service, fetch(service, url)));

        Map<String, JsonNode> specs = new LinkedHashMap<>();
        List<String> unavailable = new ArrayList<>();
        pending.forEach((service, future) -> {
            JsonNode spec = future.join();
            if (spec == null) {
                unavailable.add(service);
            } else {
                specs.put(service, spec);
            }
        });
        return merger.merge(specs, unavailable);
    }

    /** Never throws: a failed fetch yields {@code null} so one dead service cannot take the whole page down. */
    private CompletableFuture<JsonNode> fetch(String service, String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        log.warn("OpenAPI of {} not merged: {} answered {}", service, url, response.statusCode());
                        return (JsonNode) null;
                    }
                    try {
                        return json.readTree(response.body());
                    } catch (Exception e) {
                        log.warn("OpenAPI of {} not merged: unreadable document ({})", service, e.toString());
                        return null;
                    }
                })
                .exceptionally(e -> {
                    log.warn("OpenAPI of {} not merged: {} unreachable ({})", service, url, e.toString());
                    return null;
                });
    }
}
