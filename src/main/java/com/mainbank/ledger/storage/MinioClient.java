package com.mainbank.ledger.storage;

import com.mainbank.ledger.config.MinioProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal S3-compatible client for MinIO built on {@code java.net.http} and {@code javax.crypto} only
 * (path-style addressing, AWS Signature V4). It covers exactly what the ledger needs: put/get an object,
 * check/create the bucket and pre-sign a download URL.
 * <p>
 * There are no internal retries: a failure surfaces immediately and the receipt retry job takes over.
 */
@Component
public class MinioClient {

    private static final Pattern ERROR_CODE = Pattern.compile("<Code>([^<]+)</Code>");

    private final MinioProperties props;
    private final SigV4 signer;
    private final HttpClient http;
    private final Clock clock;
    private final URI endpoint;
    private final URI publicEndpoint;

    @Autowired
    public MinioClient(MinioProperties props) {
        this(props, Clock.systemUTC());
    }

    MinioClient(MinioProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.signer = new SigV4(props.accessKey(), props.secretKey(), props.region());
        this.endpoint = URI.create(props.endpoint());
        this.publicEndpoint = URI.create(props.publicEndpoint());
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.attemptTimeout())
                .build();
    }

    @PreDestroy
    void close() {
        http.close();
    }

    public void putObject(String key, byte[] body, String contentType, Map<String, String> metadata) {
        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("content-type", contentType);
        metadata.forEach((k, v) -> headers.put("x-amz-meta-" + k.toLowerCase(), v.trim()));
        execute("PUT", objectPath(key), headers, body, false);
    }

    public byte[] getObject(String key) {
        return execute("GET", objectPath(key), Map.of(), new byte[0], true);
    }

    public boolean bucketExists() {
        try {
            execute("HEAD", bucketPath(), Map.of(), new byte[0], false);
            return true;
        } catch (MinioException e) {
            // HEAD responses carry no body, so there is no error code: a bare 404 means the bucket is missing
            if (e.status() == 404) {
                return false;
            }
            throw e;
        }
    }

    /** Creates the bucket; succeeds if it already exists and is owned by these credentials. */
    public void createBucket() {
        try {
            execute("PUT", bucketPath(), Map.of(), new byte[0], false);
        } catch (MinioException e) {
            if (!"BucketAlreadyOwnedByYou".equals(e.code())) {
                throw e;
            }
        }
    }

    /**
     * Pre-signed GET URL valid for {@code ttl}. Signed against the public endpoint because the host is part of
     * the signature; {@code responseHeaders} (e.g. {@code response-content-disposition}) are signed query params.
     */
    public String presignGet(String key, Duration ttl, Map<String, String> responseHeaders) {
        String path = objectPath(key);
        String query = signer.presignedQuery(path, hostHeader(publicEndpoint), responseHeaders, ttl.toSeconds(), clock.instant());
        return base(publicEndpoint) + path + '?' + query;
    }

    private byte[] execute(String method, String path, Map<String, String> extraHeaders, byte[] body, boolean returnBody) {
        Instant now = clock.instant();
        String payloadHash = SigV4.sha256Hex(body);

        TreeMap<String, String> signed = new TreeMap<>(extraHeaders);
        signed.put("host", hostHeader(endpoint));
        signed.put("x-amz-content-sha256", payloadHash);
        signed.put("x-amz-date", SigV4.amzDate(now));
        String authorization = signer.authorization(method, path, "", signed, payloadHash, now);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base(endpoint) + path))
                .timeout(props.attemptTimeout())
                .method(method, body.length == 0 && !"PUT".equals(method)
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        // "host" is set by the HttpClient itself (restricted header) from the URI; it is only part of the signature
        signed.forEach((k, v) -> {
            if (!k.equals("host")) {
                request.header(k, v);
            }
        });
        request.header("Authorization", authorization);

        try {
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                String text = new String(response.body(), StandardCharsets.UTF_8);
                Matcher m = ERROR_CODE.matcher(text);
                String code = m.find() ? m.group(1) : null;
                throw new MinioException(method + " " + path + " failed: HTTP " + response.statusCode()
                        + (code != null ? " " + code : ""), response.statusCode(), code);
            }
            return returnBody ? response.body() : new byte[0];
        } catch (IOException e) {
            throw new MinioException(method + " " + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MinioException(method + " " + path + " interrupted", e);
        }
    }

    private String bucketPath() {
        return '/' + SigV4.uriEncode(props.bucket(), true);
    }

    private String objectPath(String key) {
        return bucketPath() + '/' + SigV4.uriEncode(key, false);
    }

    /** Value of the HTTP {@code Host} header for this URI: the port is included only when it is not the scheme default. */
    private static String hostHeader(URI uri) {
        int port = uri.getPort();
        boolean isDefault = port == -1 || ("http".equals(uri.getScheme()) && port == 80) || ("https".equals(uri.getScheme()) && port == 443);
        return isDefault ? uri.getHost() : uri.getHost() + ':' + port;
    }

    private static String base(URI uri) {
        return uri.getScheme() + "://" + hostHeader(uri);
    }
}
