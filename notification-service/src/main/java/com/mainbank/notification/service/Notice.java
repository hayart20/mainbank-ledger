package com.mainbank.notification.service;

/**
 * One message to one account holder.
 *
 * @param role      {@code DEBIT} for the payer, {@code CREDIT} for the payee; with the transfer id it is the
 *                  identity of a notification, which makes sending idempotent
 * @param recipient account holder name
 * @param iban      account the notice is about
 * @param text      what the holder is told
 */
public record Notice(String role, String recipient, String iban, String text) {
}
