package com.mainbank.ledger.util;

import com.mainbank.ledger.domain.constant.Currency;

import java.util.UUID;

/** Ids of the per-currency bank settlement accounts seeded by migration V1. */
public final class SystemAccounts {

    private SystemAccounts() { }

    public static UUID settlementFor(Currency currency) {
        return UUID.fromString("00000000-0000-0000-0000-00000000000" + (currency.ordinal() + 1));
    }
}
