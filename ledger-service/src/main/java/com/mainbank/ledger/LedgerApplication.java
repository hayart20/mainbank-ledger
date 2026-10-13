package com.mainbank.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** {@code @EnableScheduling} drives the outbox relay that publishes completed transfers to Kafka. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class LedgerApplication {
    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
