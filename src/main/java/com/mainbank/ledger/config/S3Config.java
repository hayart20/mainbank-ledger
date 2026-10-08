package com.mainbank.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class S3Config {

    private static final S3Configuration PATH_STYLE = S3Configuration.builder().pathStyleAccessEnabled(true).build();

    private StaticCredentialsProvider credentials(MinioProperties p) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(p.accessKey(), p.secretKey()));
    }

    @Bean(destroyMethod = "close")
    S3Client s3Client(MinioProperties p) {
        return S3Client.builder()
                .endpointOverride(URI.create(p.endpoint()))
                .region(Region.of(p.region()))
                .credentialsProvider(credentials(p))
                .serviceConfiguration(PATH_STYLE)
                // bounded waits: an unreachable or hung MinIO fails the upload (and the retry job takes over)
                .overrideConfiguration(o -> o
                        .apiCallAttemptTimeout(p.attemptTimeout())
                        .apiCallTimeout(p.callTimeout()))
                .build();
    }

    /** Signs against the public endpoint: the host is part of the SigV4 signature. */
    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(MinioProperties p) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(p.publicEndpoint()))
                .region(Region.of(p.region()))
                .credentialsProvider(credentials(p))
                .serviceConfiguration(PATH_STYLE)
                .build();
    }
}
