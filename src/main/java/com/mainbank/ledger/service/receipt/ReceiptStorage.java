package com.mainbank.ledger.service.receipt;

import com.mainbank.ledger.config.MinioProperties;
import com.mainbank.ledger.storage.MinioClient;
import com.mainbank.ledger.storage.MinioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Receipt-specific facade over {@link MinioClient}: content type, metadata and download links. */
@Component
public class ReceiptStorage {

    private static final Logger log = LoggerFactory.getLogger(ReceiptStorage.class);

    public record Link(String url, Instant expiresAt) { }

    private final MinioClient minio;
    private final MinioProperties props;

    public ReceiptStorage(MinioClient minio, MinioProperties props) {
        this.minio = minio;
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
        Map<String, String> metadata = Map.of("sha256-details", detailsSha256);
        try {
            minio.putObject(key, pdf, "application/pdf", metadata);
        } catch (MinioException e) {
            if (!e.isNoSuchBucket()) {
                throw e;
            }
            ensureBucket();
            minio.putObject(key, pdf, "application/pdf", metadata);
        }
    }

    public byte[] get(String key) {
        return minio.getObject(key);
    }

    public Link presignDownload(String key, String filename) {
        Duration ttl = props.presignTtl();
        String url = minio.presignGet(key, ttl, Map.of(
                "response-content-type", "application/pdf",
                "response-content-disposition", "attachment; filename=\"" + filename + "\""));
        return new Link(url, Instant.now().plus(ttl));
    }

    private void ensureBucket() {
        if (!minio.bucketExists()) {
            minio.createBucket();
            log.info("Created bucket '{}'", props.bucket());
        }
    }
}
