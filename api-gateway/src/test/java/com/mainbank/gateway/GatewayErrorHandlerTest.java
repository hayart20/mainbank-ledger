package com.mainbank.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayErrorHandlerTest {

    private final GatewayErrorHandler handler = new GatewayErrorHandler(new ObjectMapper());

    @Test
    void unreachableServiceIs503() {
        assertThat(GatewayErrorHandler.statusFor(new UnknownHostException("receipt-service"))).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(GatewayErrorHandler.statusFor(new ConnectException("refused"))).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void aFailedConnectIsUnreachableNotSlowEvenThoughTheJdkCallsItATimeout() {
        // HttpConnectTimeoutException extends HttpTimeoutException: it must still be a 503, not a 504
        assertThat(GatewayErrorHandler.statusFor(new HttpConnectTimeoutException("HTTP connect timed out")))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void aServiceThatAnswersTooSlowlyIs504() {
        assertThat(GatewayErrorHandler.statusFor(new HttpTimeoutException("request timed out"))).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(GatewayErrorHandler.statusFor(new SocketTimeoutException("Read timed out"))).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(GatewayErrorHandler.statusFor(new TimeoutException("slow"))).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void rootCauseIsFoundThroughWrappers() {
        var wrapped = new ResourceAccessException("I/O error", new ConnectException("refused"));

        assertThat(GatewayErrorHandler.statusFor(wrapped)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void otherErrorsAreNotOurs() {
        assertThat(GatewayErrorHandler.statusFor(new IllegalStateException("bug"))).isNull();
        assertThat(GatewayErrorHandler.statusFor(new ResponseStatusException(HttpStatus.NOT_FOUND))).isNull();
    }

    @Test
    void writesAProblemJsonResponseForUnreachableServices() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/receipts/1");
        var response = new MockHttpServletResponse();

        var result = handler.resolveException(request, response, null,
                new ResourceAccessException("I/O error", new UnknownHostException("receipt-service")));

        assertThat(result).isNotNull();
        assertThat(response.getStatus()).isEqualTo(503);
        // exactly the media type: no legacy "charset=ISO-8859-1" appended (JSON is UTF-8 by definition)
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentAsString()).contains("\"code\":\"SERVICE_UNAVAILABLE\"").contains("\"status\":503");
    }

    @Test
    void leavesUnrelatedErrorsToSpring() {
        var response = new MockHttpServletResponse();

        var result = handler.resolveException(new MockHttpServletRequest("GET", "/x"), response, null,
                new IllegalStateException("bug"));

        assertThat(result).isNull();
        assertThat(response.getStatus()).isEqualTo(200); // untouched
    }
}
