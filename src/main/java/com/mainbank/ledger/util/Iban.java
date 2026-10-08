package com.mainbank.ledger.util;

import java.math.BigInteger;

/** ISO 13616 IBAN construction for Armenia (AM, 28 characters). */
public final class Iban {

    private static final String COUNTRY = "AM";
    private static final String BANK_CODE = "10300";
    private static final int ACCOUNT_DIGITS = 19;

    private Iban() { }

    public static String build(long accountSequence) {
        String bban = BANK_CODE + "%0" + ACCOUNT_DIGITS + "d";
        bban = bban.formatted(accountSequence);
        int check = 98 - mod97(bban + COUNTRY + "00");
        return "%s%02d%s".formatted(COUNTRY, check, bban);
    }

    public static boolean isValid(String iban) {
        if (iban == null || iban.length() < 15) return false;
        return mod97(iban.substring(4) + iban.substring(0, 4)) == 1;
    }

    private static int mod97(String s) {
        StringBuilder digits = new StringBuilder();
        for (char c : s.toCharArray()) {
            digits.append(Character.isLetter(c) ? String.valueOf(Character.toUpperCase(c) - 'A' + 10) : String.valueOf(c));
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue();
    }
}
