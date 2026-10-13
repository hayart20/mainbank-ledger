package com.mainbank.notification.service;

import com.mainbank.ledger.events.TransferCompletedMessage;
import com.mainbank.notification.repository.NotificationLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationSender sender;
    @Mock NotificationLog sent;

    private NotificationService service;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new NotificationService(sender, sent);
    }

    private TransferCompletedMessage message(String type) {
        return new TransferCompletedMessage(1, id, type, Instant.parse("2026-01-02T03:04:05Z"), UUID.randomUUID(),
                "AM11", "Anna", UUID.randomUUID(), "AM22", "Bob", new BigDecimal("25.50"), "USD");
    }

    @Test
    void transferNotifiesPayerAndPayee() {
        service.onTransferCompleted(message("TRANSFER"));

        ArgumentCaptor<Notice> notices = ArgumentCaptor.forClass(Notice.class);
        verify(sender, org.mockito.Mockito.times(2)).send(notices.capture());
        assertThat(notices.getAllValues()).extracting(Notice::role, Notice::recipient, Notice::text)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("DEBIT", "Anna", "You sent 25.50 USD to Bob (AM22)"),
                        org.assertj.core.groups.Tuple.tuple("CREDIT", "Bob", "You received 25.50 USD from Anna (AM11)"));
    }

    @Test
    void openingDepositOnlyNotifiesTheCustomerNeverTheBankSettlementAccount() {
        service.onTransferCompleted(message("OPENING_DEPOSIT"));

        ArgumentCaptor<Notice> notices = ArgumentCaptor.forClass(Notice.class);
        verify(sender).send(notices.capture());
        assertThat(notices.getValue().role()).isEqualTo("CREDIT");
        assertThat(notices.getValue().recipient()).isEqualTo("Bob");
        assertThat(notices.getValue().text()).isEqualTo("Your account AM22 was funded with 25.50 USD");
    }

    @Test
    void noticeIsRecordedOnlyAfterItWasSent() {
        service.onTransferCompleted(message("TRANSFER"));

        InOrder order = inOrder(sender, sent);
        order.verify(sender).send(any());
        order.verify(sent).markSent(eq(id), any());
    }

    @Test
    void redeliveredEventDoesNotNotifyAgain() {
        when(sent.alreadySent(id, "DEBIT")).thenReturn(true);
        when(sent.alreadySent(id, "CREDIT")).thenReturn(true);

        service.onTransferCompleted(message("TRANSFER"));

        verify(sender, never()).send(any());
        verify(sent, never()).markSent(any(), any());
    }

    @Test
    void failedSendIsNotRecordedSoRedeliveryTriesAgain() {
        doThrow(new IllegalStateException("smtp down")).when(sender).send(any());

        assertThatThrownBy(() -> service.onTransferCompleted(message("TRANSFER")))
                .isInstanceOf(IllegalStateException.class);

        verify(sent, never()).markSent(any(), any());
    }
}
