package com.mainbank.receipt.service;

import com.mainbank.receipt.messaging.TransferCompletedMessage;
import com.mainbank.receipt.domain.Receipt;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/** The facts a receipt attests to. The hash definition is unchanged from the ledger so old and new receipts verify alike. */
public record ReceiptData(UUID transferId, Instant createdAt, String sourceIban, String sourceOwner,
                          String targetIban, String targetOwner, BigDecimal amount, String currency, String type) {

    public static ReceiptData of(Receipt r) {
        return new ReceiptData(r.getTransferId(), r.getTransferCreatedAt(), r.getSourceIban(), r.getSourceOwner(),
                r.getTargetIban(), r.getTargetOwner(), r.getAmount(), r.getCurrency(), r.getType());
    }

    public static ReceiptData of(TransferCompletedMessage m) {
        return new ReceiptData(m.transferId(), m.createdAt(), m.sourceIban(), m.sourceOwner(),
                m.targetIban(), m.targetOwner(), m.amount(), m.currency(), m.type());
    }

    /** Stable, unambiguous serialisation of the facts a receipt attests to. */
    public String canonical() {
        return String.join("|", transferId.toString(), String.valueOf(createdAt.toEpochMilli()), sourceIban, targetIban,
                amount.toPlainString(), currency, type);
    }

    /** SHA-256 over {@link #canonical()}; printed on the PDF so it can be re-verified against the ledger. */
    public String detailsHash() {
        return sha256Hex(canonical().getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
