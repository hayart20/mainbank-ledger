package com.mainbank.ledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

@ConfigurationProperties("ledger")
public record LedgerProperties(BigDecimal maxTransferAmount, Duration lockTimeout, Receipt receipt) {

    public record Receipt(int maxAttempts, Duration retryInterval, Duration retryMinAge) { }
}
