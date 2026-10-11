package com.mainbank.ledger.storage;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks the signer against the worked examples published in the AWS S3 Signature V4 documentation. */
class SigV4Test {

    private static final SigV4 SIGNER = new SigV4("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1");
    private static final Instant AT = Instant.parse("2013-05-24T00:00:00Z");
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void authorizationHeaderMatchesAwsGetObjectExample() {
        Map<String, String> headers = new TreeMap<>();
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("range", "bytes=0-9");
        headers.put("x-amz-content-sha256", EMPTY_SHA256);
        headers.put("x-amz-date", "20130524T000000Z");

        String auth = SIGNER.authorization("GET", "/test.txt", "", headers, EMPTY_SHA256, AT);

        assertThat(auth).isEqualTo("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                + " SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                + " Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
    }

    @Test
    void presignedQueryMatchesAwsPresignedUrlExample() {
        String query = SIGNER.presignedQuery("/test.txt", "examplebucket.s3.amazonaws.com", Map.of(), 86400, AT);

        assertThat(query).isEqualTo("X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"
                + "&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host"
                + "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404");
    }

    @Test
    void uriEncodeKeepsUnreservedAndEncodesTheRest() {
        assertThat(SigV4.uriEncode("a-b_c.d~E9", true)).isEqualTo("a-b_c.d~E9");
        assertThat(SigV4.uriEncode("receipts/x y+z.pdf", false)).isEqualTo("receipts/x%20y%2Bz.pdf");
        assertThat(SigV4.uriEncode("a/b", true)).isEqualTo("a%2Fb");
        assertThat(SigV4.uriEncode("é", true)).isEqualTo("%C3%A9");
    }

    @Test
    void canonicalQuerySortsByEncodedName() {
        assertThat(SigV4.canonicalQuery(Map.of("b", "2", "a", "1 x"))).isEqualTo("a=1%20x&b=2");
    }
}
