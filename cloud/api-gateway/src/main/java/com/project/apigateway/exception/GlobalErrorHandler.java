package com.project.apigateway.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.handler.WebFluxResponseStatusExceptionHandler;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * Global error handler for the Gateway layer.
 * Handles Gateway-level errors (502, 503, 404, unexpected exceptions).
 * Business errors from downstream services pass through as-is.
 */
@Slf4j
@Order(-2)
@Component
public class GlobalErrorHandler extends WebFluxResponseStatusExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();

        HttpStatus status;
        String message;

        if (ex instanceof ResponseStatusException rse) {
            var code = HttpStatus.resolve(rse.getStatusCode().value());
            status  = (code != null) ? code : HttpStatus.INTERNAL_SERVER_ERROR;
            message = rse.getReason() != null ? rse.getReason() : rse.getMessage();
        } else if (ex instanceof NotFoundException) {
            status  = HttpStatus.SERVICE_UNAVAILABLE;
            message = "Downstream service is unavailable";
        } else if (ex instanceof java.net.ConnectException) {
            status  = HttpStatus.SERVICE_UNAVAILABLE;
            message = "Downstream service is unavailable";
        } else {
            status  = HttpStatus.INTERNAL_SERVER_ERROR;
            message = "An unexpected gateway error occurred";
        }

        log.error("Gateway error [{}]: {} — {}", status, ex.getClass().getSimpleName(), ex.getMessage());

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {
                  "success": false,
                  "status": %d,
                  "error": "%s",
                  "message": "%s",
                  "timestamp": "%s"
                }
                """.formatted(
                status.value(),
                status.getReasonPhrase(),
                message,
                LocalDateTime.now()
        );

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
