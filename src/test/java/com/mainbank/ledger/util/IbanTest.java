package com.mainbank.ledger.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IbanTest {

    @Test
    void generatedIbansAreValidAndHaveArmenianLength() {
        for (long seq : new long[]{1, 1_000_000_000L, 9_999_999_999_999L}) {
            String iban = Iban.build(seq);
            assertThat(iban).startsWith("AM").hasSize(28);
            assertThat(Iban.isValid(iban)).as(iban).isTrue();
        }
    }

    @Test
    void corruptedIbanFailsChecksum() {
        String iban = Iban.build(42);
        String corrupted = iban.substring(0, 10) + (iban.charAt(10) == '1' ? '2' : '1') + iban.substring(11);
        assertThat(Iban.isValid(corrupted)).isFalse();
    }

    @Test
    void receiptHashIsDeterministic() {
        assertThat(Hashing.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
