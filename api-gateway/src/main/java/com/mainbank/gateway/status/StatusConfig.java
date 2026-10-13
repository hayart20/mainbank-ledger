package com.mainbank.gateway.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wires the system-status check from the same service URLs the routes use ({@code gateway.*-url}), plus the
 * notification service, which has no route (it exposes no API) but is part of the system.
 */
@Configuration
class StatusConfig {

    @Bean
    SystemStatusService systemStatusService(
            @Value("${gateway.ledger-url:http://localhost:8080}") String ledger,
            @Value("${gateway.receipt-url:http://localhost:8081}") String receipt,
            @Value("${gateway.statement-url:http://localhost:8083}") String statement,
            @Value("${gateway.notification-url:http://localhost:8082}") String notification,
            @Value("${gateway.status-timeout:2s}") Duration timeout,
            ObjectMapper json) {
        Map<String, String> services = new LinkedHashMap<>();
        services.put("ledger", ledger);
        services.put("receipt", receipt);
        services.put("statement", statement);
        services.put("notification", notification);
        return new SystemStatusService(services, timeout, json);
    }
}
