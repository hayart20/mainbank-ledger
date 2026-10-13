package com.mainbank.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Turns "the service behind this route cannot be reached" into a proper 503, and "it did not answer in time" into a
 * 504, with the same {@code application/problem+json} shape and stable {@code code} the services use, instead of a
 * generic 500 that looks like a gateway bug.
 * <p>
 * It is a {@link HandlerExceptionResolver} rather than a {@code @ExceptionHandler}: for any other error it returns
 * {@code null}, so Spring's own handling (a 404 for an unknown path, validation errors, real bugs) applies untouched.
 */
@Component
class GatewayErrorHandler implements HandlerExceptionResolver, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final ObjectMapper json;

    GatewayErrorHandler(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
                                         Object handler, Exception ex) {
        HttpStatus status = statusFor(ex);
        if (status == null || response.isCommitted()) {
            return null;
        }
        boolean timeout = status == HttpStatus.GATEWAY_TIMEOUT;
        String code = timeout ? "UPSTREAM_TIMEOUT" : "SERVICE_UNAVAILABLE";
        log.warn("{} for {} {}: {}", status.value(), request.getMethod(), request.getRequestURI(), ex.toString());

        Map<String, Object> problem = Map.of(
                "type", "about:blank",
                "title", status.getReasonPhrase(),
                "status", status.value(),
                "detail", timeout ? "The service did not answer in time" : "The service is temporarily unavailable, please retry",
                "code", code);
        try {
            // bytes, not getWriter(): the writer would stamp a legacy default charset on the JSON content type
            byte[] body = json.writeValueAsBytes(problem);
            response.setStatus(status.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setContentLength(body.length);
            if (!timeout) {
                response.setHeader("Retry-After", "1");
            }
            response.getOutputStream().write(body);
        } catch (IOException writeFailure) {
            log.warn("Could not write the {} response: {}", status.value(), writeFailure.toString());
        }
        return new ModelAndView(); // handled: an empty view tells the dispatcher not to render anything
    }

    /**
     * @return 503 when the service could not be reached, 504 when it was reached but did not answer in time,
     *         or null when this handler is not responsible for the error
     */
    static HttpStatus statusFor(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            // Order matters: the JDK reports a failed connect (nothing listening, name not resolvable) as a
            // *connect timeout*, which is "unreachable" (503), not "slow" (504); and HttpConnectTimeoutException
            // is a subclass of HttpTimeoutException.
            if (t instanceof HttpConnectTimeoutException || t instanceof UnknownHostException
                    || t instanceof ConnectException || t instanceof NoRouteToHostException
                    || t instanceof UnresolvedAddressException) {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException || t instanceof TimeoutException) {
                return HttpStatus.GATEWAY_TIMEOUT;
            }
        }
        return null;
    }
}
