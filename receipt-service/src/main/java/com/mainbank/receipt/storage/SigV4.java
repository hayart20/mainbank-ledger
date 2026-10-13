package com.mainbank.receipt.storage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * AWS Signature Version 4 for the S3 API, using only {@code javax.crypto} and {@code MessageDigest}.
 * Supports the two forms MinIO needs: an {@code Authorization} header and a query-string pre-signed URL.
 */
final class SigV4 {

    static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    static final String ALGORITHM = "AWS4-HMAC-SHA256";

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final HexFormat HEX = HexFormat.of();

    private final String accessKey;
    private final byte[] secretKey;
    private final String region;

    SigV4(String accessKey, String secretKey, String region) {
        this.accessKey = accessKey;
        this.secretKey = secretKey.getBytes(StandardCharsets.UTF_8);
        this.region = region;
    }

    static String amzDate(Instant at) {
        return AMZ_DATE.format(at);
    }

    static String sha256Hex(byte[] data) {
        return HEX.formatHex(sha256().digest(data));
    }

    /**
     * Value of the {@code Authorization} header.
     *
     * @param headers every header that takes part in the signature, names lower-cased, values trimmed; must include
     *                {@code host}, {@code x-amz-date} and every other {@code x-amz-*} header that is sent
     */
    String authorization(String method, String canonicalPath, String canonicalQuery,
                         Map<String, String> headers, String payloadHash, Instant at) {
        TreeMap<String, String> sorted = new TreeMap<>(headers);
        String signedHeaders = String.join(";", sorted.keySet());
        StringBuilder canonicalHeaders = new StringBuilder();
        sorted.forEach((k, v) -> canonicalHeaders.append(k).append(':').append(v).append('\n'));
        String canonicalRequest = method + '\n' + canonicalPath + '\n' + canonicalQuery + '\n'
                + canonicalHeaders + '\n' + signedHeaders + '\n' + payloadHash;
        String signature = signature(canonicalRequest, at);
        return ALGORITHM + " Credential=" + accessKey + '/' + scope(at)
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    /**
     * Query string (already URI-encoded) for a pre-signed GET: the given {@code extraParams} plus the
     * {@code X-Amz-*} parameters and the signature. Only the {@code host} header is signed, and the payload is unsigned.
     */
    String presignedQuery(String canonicalPath, String host, Map<String, String> extraParams, long expiresSeconds, Instant at) {
        TreeMap<String, String> params = new TreeMap<>(extraParams);
        params.put("X-Amz-Algorithm", ALGORITHM);
        params.put("X-Amz-Credential", accessKey + '/' + scope(at));
        params.put("X-Amz-Date", amzDate(at));
        params.put("X-Amz-Expires", Long.toString(expiresSeconds));
        params.put("X-Amz-SignedHeaders", "host");
        String canonicalQuery = canonicalQuery(params);
        String canonicalRequest = "GET\n" + canonicalPath + '\n' + canonicalQuery + "\nhost:" + host + "\n\nhost\n" + UNSIGNED_PAYLOAD;
        return canonicalQuery + "&X-Amz-Signature=" + signature(canonicalRequest, at);
    }

    /** Parameters sorted by encoded name, as SigV4 requires. */
    static String canonicalQuery(Map<String, String> params) {
        TreeMap<String, String> encoded = new TreeMap<>();
        params.forEach((k, v) -> encoded.put(uriEncode(k, true), uriEncode(v, true)));
        StringJoiner joiner = new StringJoiner("&");
        encoded.forEach((k, v) -> joiner.add(k + '=' + v));
        return joiner.toString();
    }

    /** RFC 3986 encoding as defined by SigV4: only unreserved characters stay literal; '/' optionally. */
    static String uriEncode(String value, boolean encodeSlash) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved || (c == '/' && !encodeSlash)) {
                out.append(c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    private String scope(Instant at) {
        return amzDate(at).substring(0, 8) + '/' + region + "/s3/aws4_request";
    }

    private String signature(String canonicalRequest, Instant at) {
        String stringToSign = ALGORITHM + '\n' + amzDate(at) + '\n' + scope(at) + '\n'
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] key = hmac(("AWS4" + new String(secretKey, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8),
                amzDate(at).substring(0, 8));
        key = hmac(key, region);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        return HEX.formatHex(hmac(key, stringToSign));
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
