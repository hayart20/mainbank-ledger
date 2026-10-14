package com.mainbank.gateway.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Combines the OpenAPI documents of the services into one, so a single Swagger UI page lists every endpoint.
 * <p>
 * The services own disjoint paths, so paths are simply added. Component entries (schemas, ...) are keyed by name:
 * the first one wins, which is right for the shared {@code ProblemDetail} and visible in the log if two services
 * ever define different types under the same name.
 */
public class OpenApiMerger {

    private static final Logger log = LoggerFactory.getLogger(OpenApiMerger.class);

    private final ObjectMapper json;

    public OpenApiMerger(ObjectMapper json) {
        this.json = json;
    }

    /**
     * @param specs       OpenAPI documents by service name, in the order they should appear
     * @param unavailable names of services whose document could not be fetched; listed in the description
     */
    public ObjectNode merge(Map<String, JsonNode> specs, List<String> unavailable) {
        ObjectNode merged = json.createObjectNode();
        merged.put("openapi", "3.1.0");

        ObjectNode info = merged.putObject("info");
        info.put("title", "MainBank API");
        info.put("version", "1.0.0");
        info.put("description", description(specs.keySet(), unavailable));

        // relative: Swagger UI then calls the host the page was loaded from, i.e. the gateway
        merged.putArray("servers").addObject().put("url", "/");

        ArrayNode tags = merged.putArray("tags");
        ObjectNode paths = merged.putObject("paths");
        ObjectNode components = merged.putObject("components");
        Set<String> tagNames = new HashSet<>();

        specs.forEach((service, spec) -> {
            for (JsonNode tag : spec.path("tags")) {
                if (tagNames.add(tag.path("name").asText())) {
                    tags.add(tag);
                }
            }
            addAll(paths, spec.path("paths"), service, "path");
            JsonNode comps = spec.path("components");
            for (Iterator<String> kinds = comps.fieldNames(); kinds.hasNext(); ) {
                String kind = kinds.next();
                addAll(components.withObjectProperty(kind), comps.get(kind), service, kind);
            }
        });
        if (components.isEmpty()) {
            merged.remove("components");
        }
        return merged;
    }

    private static void addAll(ObjectNode target, JsonNode source, String service, String what) {
        if (!source.isObject()) {
            return;
        }
        source.fields().forEachRemaining(entry -> {
            JsonNode existing = target.get(entry.getKey());
            if (existing == null) {
                target.set(entry.getKey(), entry.getValue());
            } else if (!existing.equals(entry.getValue())) {
                log.warn("OpenAPI {} '{}' of {} differs from the one already merged; keeping the first",
                        what, entry.getKey(), service);
            }
        });
    }

    private static String description(Set<String> services, List<String> unavailable) {
        StringBuilder text = new StringBuilder("All endpoints of the platform behind one gateway, merged from: ")
                .append(String.join(", ", services)).append('.');
        if (!unavailable.isEmpty()) {
            text.append("\n\n**Currently unavailable (not listed):** ").append(String.join(", ", unavailable)).append('.');
        }
        return text.toString();
    }
}
