package com.mainbank.receipt.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptPdfAndDataTest {

    private static final Instant WHEN = Instant.parse("2026-01-02T03:04:05.123Z");

    private static ReceiptData data(String owner) {
        return new ReceiptData(UUID.fromString("11111111-1111-1111-1111-111111111111"), WHEN, "AM11", owner,
                "AM22", "Bob", new BigDecimal("12.30"), "AMD", "TRANSFER");
    }

    @Test
    void canonicalFormIsStableSoOldReceiptsKeepVerifying() {
        assertThat(data("Hayk").canonical()).isEqualTo(
                "11111111-1111-1111-1111-111111111111|1767323045123|AM11|AM22|12.30|AMD|TRANSFER");
    }

    @Test
    void detailsHashIgnoresOwnerNamesButCoversTheAmount() {
        assertThat(data("Hayk").detailsHash()).isEqualTo(data("Someone else").detailsHash());
        assertThat(data("Hayk").detailsHash()).isNotEqualTo(new ReceiptData(data("Hayk").transferId(), WHEN, "AM11", "Hayk",
                "AM22", "Bob", new BigDecimal("12.31"), "AMD", "TRANSFER").detailsHash());
    }

    @Test
    void generatesAPdfDocument() {
        byte[] pdf = new ReceiptPdfGenerator().generate(data("Hayk ա")); // non-Latin-1 must not break generation

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(500);
    }
}
