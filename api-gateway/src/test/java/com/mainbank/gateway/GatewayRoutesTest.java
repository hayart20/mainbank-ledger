package com.mainbank.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "gateway.ledger-url=http://ledger:8080",
        "gateway.receipt-url=http://receipt:8081",
        "gateway.statement-url=http://statement:8083",
        "server.port=0"})
class GatewayRoutesTest {

    @Autowired RouteLocator routes;
    @Autowired org.springframework.cloud.gateway.config.HttpClientProperties httpClient;

    /** The service a request path is routed to, i.e. the first route whose predicate matches. */
    private String target(String path) {
        List<Route> all = routes.getRoutes().collectList().block();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
        return all.stream()
                .filter(r -> Boolean.TRUE.equals(Mono.from(r.getPredicate().apply(exchange)).block()))
                .map(r -> r.getUri().toString())
                .findFirst().orElse("none");
    }

    @Test
    void accountsAndTransfersGoToTheLedger() {
        assertThat(target("/api/v1/accounts")).isEqualTo("http://ledger:8080");
        assertThat(target("/api/v1/accounts/7b9c0d9e-0000-0000-0000-000000000001")).isEqualTo("http://ledger:8080");
        assertThat(target("/api/v1/transfers")).isEqualTo("http://ledger:8080");
        assertThat(target("/api/v1/transfers/7b9c0d9e-0000-0000-0000-000000000001")).isEqualTo("http://ledger:8080");
    }

    @Test
    void statementIsRoutedBeforeTheGenericAccountsRoute() {
        assertThat(target("/api/v1/accounts/7b9c0d9e-0000-0000-0000-000000000001/statement"))
                .isEqualTo("http://statement:8083");
    }

    @Test
    void receiptsGoToTheReceiptService() {
        assertThat(target("/api/v1/receipts/7b9c0d9e-0000-0000-0000-000000000001")).isEqualTo("http://receipt:8081");
    }

    @Test
    void downstreamCallsHaveBoundedTimeouts() {
        assertThat(httpClient.getConnectTimeout()).isEqualTo(2000);
        assertThat(httpClient.getResponseTimeout()).isEqualTo(java.time.Duration.ofSeconds(30));
    }

    @Test
    void unknownPathsAreNotRouted() {
        assertThat(target("/api/v1/unknown")).isEqualTo("none");
        assertThat(target("/internal/anything")).isEqualTo("none");
    }
}
