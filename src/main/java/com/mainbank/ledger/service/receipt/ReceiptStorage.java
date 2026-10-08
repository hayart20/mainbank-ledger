package com.mainbank.ledger.service.receipt;

import com.mainbank.ledger.config.MinioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Thin wrapper over the S3 SDK so the rest of the code never touches AWS types. */
@Component
public class ReceiptStorage {

    private static final Logger log = LoggerFactory.getLogger(ReceiptStorage.class);

    public record Link(String url, Instant expiresAt) { }

    private final S3Client s3;
    private final S3Presigner presigner;
    private final MinioProperties props;

    public ReceiptStorage(S3Client s3, S3Presigner presigner, MinioProperties props) {
        this.s3 = s3;
        this.presigner = presigner;
        this.props = props;
    }

    /** Best effort at startup: docker-compose pre-creates the bucket, but local/test runs may not. */
    @EventListener(ApplicationReadyEvent.class)
    void ensureBucketOnStartup() {
        try {
            ensureBucket();
        } catch (RuntimeException e) {
            log.warn("Could not verify bucket '{}' at startup, will retry on first upload: {}", props.bucket(), e.getMessage());
        }
    }

    public void put(String key, byte[] pdf, String detailsSha256) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(props.bucket())
                .key(key)
                .contentType("application/pdf")
                .metadata(Map.of("sha256-details", detailsSha256))
                .build();
        try {
            s3.putObject(request, RequestBody.fromBytes(pdf));
        } catch (NoSuchBucketException e) {
            ensureBucket();
            s3.putObject(request, RequestBody.fromBytes(pdf));
        }
    }

    public byte[] get(String key) {
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(props.bucket()).key(key).build()).asByteArray();
    }

    public Link presignDownload(String key, String filename) {
        Duration ttl = props.presignTtl();
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(props.bucket())
                .key(key)
                .responseContentType("application/pdf")
                .responseContentDisposition("attachment; filename=\"" + filename + "\"")
                .build();
        var presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(get)
                .build());
        return new Link(presigned.url().toString(), Instant.now().plus(ttl));
    }

    private void ensureBucket() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(props.bucket()).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(props.bucket()).build());
            log.info("Created bucket '{}'", props.bucket());
        }
    }
}
