package com.mainbank.ledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param attemptTimeout max time for one HTTP request to MinIO (connect and response), so a hung server cannot
 *                       pin a receipt thread; there are no internal retries, the receipt retry job takes over
 */
@ConfigurationProperties("minio")
public record MinioProperties(String endpoint, String publicEndpoint, String accessKey, String secretKey,
                              String bucket, String region, Duration presignTtl,
                              Duration attemptTimeout) {

    public MinioProperties {
        if (attemptTimeout == null) attemptTimeout = Duration.ofSeconds(10);
    }
}
