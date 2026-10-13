package com.mainbank.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Turns "the service behind this route is not reachable" into a proper 503 (or 504 for a timeout) with the same
 * {@code application/problem+json} shape and stable {@code code} the services use, instead of a generic 500 that
 * looks like a gateway bug. Anything else is left to the default handler.
 */
@Component
@Order(-2) // before Spring Boot's default error handler (-1)
class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final ObjectMapper json;

    GatewayErrorHandler(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        HttpStatus status = statusFor(error);
        if (status == null || exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        String code = status == HttpStatus.GATEWAY_TIMEOUT ? "UPSTREAM_TIMEOUT" : "SERVICE_UNAVAILABLE";
        log.warn("{} for {} {}: {}", status.value(), exchange.getRequest().getMethod(), exchange.getRequest().getPath(),
                error.toString());

        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (status == HttpStatus.SERVICE_UNAVAILABLE) {
            response.getHeaders().set("Retry-After", "1");
        }
        byte[] body = body(status, code);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    /** @return the status to answer with, or null when this handler is not responsible for the error */
    static HttpStatus statusFor(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String type = t.getClass().getName();
            if (t instanceof TimeoutException || type.endsWith("ReadTimeoutException")
                    || type.endsWith("ConnectTimeoutException")) {
                return HttpStatus.GATEWAY_TIMEOUT;
            }
            if (t instanceof UnknownHostException || t instanceof ConnectException) {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
        }
        return null;
    }

    private byte[] body(HttpStatus status, String code) {
        Map<String, Object> problem = Map.of(
                "type", "about:blank",
                "title", status.getReasonPhrase(),
                "status", status.value(),
                "detail", status == HttpStatus.GATEWAY_TIMEOUT
                        ? "The service did not answer in time"
                        : "The service is temporarily unavailable, please retry",
                "code", code);
        try {
            return json.writeValueAsBytes(problem);
        } catch (JsonProcessingException e) {
            return ("{\"status\":" + status.value() + ",\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8);
        }
    }
}
