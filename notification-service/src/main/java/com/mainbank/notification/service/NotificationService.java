package com.mainbank.notification.service;

import com.mainbank.ledger.events.TransferCompletedMessage;
import com.mainbank.notification.repository.NotificationLog;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Turns a completed transfer into one notice per customer involved and sends the ones not sent yet.
 * <p>
 * The order is send, then record: a crash in between sends the notice again on redelivery (a duplicate is
 * preferable to a notification that was never delivered). Once recorded, a notice is never sent twice.
 */
@Service
public class NotificationService {

    private final NotificationSender sender;
    private final NotificationLog sent;

    public NotificationService(NotificationSender sender, NotificationLog sent) {
        this.sender = sender;
        this.sent = sent;
    }

    public void onTransferCompleted(TransferCompletedMessage m) {
        for (Notice notice : noticesFor(m)) {
            if (sent.alreadySent(m.transferId(), notice.role())) {
                continue;
            }
            sender.send(notice);
            sent.markSent(m.transferId(), notice);
        }
    }

    static List<Notice> noticesFor(TransferCompletedMessage m) {
        String money = m.amount().toPlainString() + " " + m.currency();
        Notice credit = "OPENING_DEPOSIT".equals(m.type())
                // the source is the bank's own settlement account, not a customer
                ? new Notice("CREDIT", m.targetOwner(), m.targetIban(), "Your account " + m.targetIban() + " was funded with " + money)
                : new Notice("CREDIT", m.targetOwner(), m.targetIban(),
                        "You received " + money + " from " + m.sourceOwner() + " (" + m.sourceIban() + ")");
        if ("OPENING_DEPOSIT".equals(m.type())) {
            return List.of(credit);
        }
        Notice debit = new Notice("DEBIT", m.sourceOwner(), m.sourceIban(),
                "You sent " + money + " to " + m.targetOwner() + " (" + m.targetIban() + ")");
        return List.of(debit, credit);
    }
}
