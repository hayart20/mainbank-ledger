package com.mainbank.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayErrorHandlerTest {

    @Test
    void unreachableServiceIs503() {
        assertThat(GatewayErrorHandler.statusFor(new UnknownHostException("receipt-service"))).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(GatewayErrorHandler.statusFor(new ConnectException("refused"))).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void rootCauseIsFoundThroughWrappers() {
        var wrapped = new RuntimeException("request failed", new IllegalStateException(new UnknownHostException("x")));

        assertThat(GatewayErrorHandler.statusFor(wrapped)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void timeoutsAre504EvenWhenTheyAreAlsoConnectErrors() {
        assertThat(GatewayErrorHandler.statusFor(new TimeoutException("slow"))).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        // netty's ConnectTimeoutException extends ConnectException: it must still be a timeout
        assertThat(GatewayErrorHandler.statusFor(new io.netty.channel.ConnectTimeoutException("connect timed out")))
                .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void otherErrorsAreLeftToTheDefaultHandler() {
        assertThat(GatewayErrorHandler.statusFor(new IllegalStateException("bug"))).isNull();
        assertThat(GatewayErrorHandler.statusFor(new ResponseStatusException(HttpStatus.NOT_FOUND))).isNull();
    }
}
