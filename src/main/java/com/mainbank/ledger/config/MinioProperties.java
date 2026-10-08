package com.mainbank.ledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param attemptTimeout max time for one HTTP attempt to MinIO, so a hung server cannot pin a receipt thread
 * @param callTimeout    max total time for one SDK call including SDK-internal retries
 */
@ConfigurationProperties("minio")
public record MinioProperties(String endpoint, String publicEndpoint, String accessKey, String secretKey,
                              String bucket, String region, Duration presignTtl,
                              Duration attemptTimeout, Duration callTimeout) {

    public MinioProperties {
        if (attemptTimeout == null) attemptTimeout = Duration.ofSeconds(10);
        if (callTimeout == null) callTimeout = Duration.ofSeconds(30);
    }
}
