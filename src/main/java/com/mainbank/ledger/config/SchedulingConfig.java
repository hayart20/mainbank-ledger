package com.mainbank.ledger.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables the receipt retry job ({@code ReceiptService#retryPending()}). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
