package com.mainbank.ledger.service.receipt;

import com.mainbank.ledger.domain.constant.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ReceiptPdfAndDataTest {

    private static final Instant WHEN = Instant.parse("2026-01-15T10:15:30.123Z");

    private static ReceiptData data(UUID id, String amount, String owner) {
        return new ReceiptData(id, WHEN, "AM11", owner, "AM22", "Bob", new BigDecimal(amount), Currency.AMD, "TRANSFER");
    }

    @Test
    void canonicalFormIsStableAndPipeSeparated() {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");

        assertThat(data(id, "12.30", "Anna").canonical())
                .isEqualTo("11111111-1111-1111-1111-111111111111|" + WHEN.toEpochMilli() + "|AM11|AM22|12.30|AMD|TRANSFER");
    }

    @Test
    void detailsHashIsDeterministicAndChangesWhenAnyAttestedFactChanges() {
        UUID id = UUID.randomUUID();
        String base = data(id, "12.30", "Anna").detailsHash();

        assertThat(base).hasSize(64).isEqualTo(data(id, "12.30", "Anna").detailsHash());
        assertThat(data(id, "12.31", "Anna").detailsHash()).isNotEqualTo(base);
        assertThat(data(UUID.randomUUID(), "12.30", "Anna").detailsHash()).isNotEqualTo(base);
    }

    @Test
    void generatedPdfIsAValidPdfDocumentContainingTheHashInput() {
        ReceiptData d = data(UUID.randomUUID(), "12.30", "Anna");

        byte[] pdf = new ReceiptPdfGenerator().generate(d);

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(new String(pdf, pdf.length - 8, 8, StandardCharsets.ISO_8859_1)).contains("%%EOF");
    }

    @Test
    void namesOutsideLatin1DoNotBreakPdfGeneration() {
        ReceiptData d = data(UUID.randomUUID(), "1.00", "Աննա Հակոբյան");

        assertThatCode(() -> new ReceiptPdfGenerator().generate(d)).doesNotThrowAnyException();
    }
}
