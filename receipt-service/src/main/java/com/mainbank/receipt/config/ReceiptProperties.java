package com.mainbank.receipt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param maxAttempts   generation attempts per receipt before the retry job gives up
 * @param retryInterval how often the retry job looks for receipts that are not READY
 * @param retryMinAge   a receipt is only retried when it was last touched longer ago than this, so the retry job
 *                      does not race the Kafka consumer that is still working on it
 */
@ConfigurationProperties("receipt")
public record ReceiptProperties(int maxAttempts, Duration retryInterval, Duration retryMinAge) {
}
