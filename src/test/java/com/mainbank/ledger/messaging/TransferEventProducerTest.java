package com.mainbank.ledger.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferEventProducerTest {

    private static final String TOPIC = "ledger.transfer.completed";

    @Mock KafkaTemplate<String, String> kafka;

    private TransferEventProducer producer;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        producer = new TransferEventProducer(kafka, new ObjectMapper(), TOPIC);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void withoutATransactionTheMessageIsSentImmediatelyKeyedByTransferId() {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        producer.publishAfterCommit(id);

        verify(kafka).send(TOPIC, id.toString(), "{\"transferId\":\"" + id + "\"}");
    }

    @Test
    void insideATransactionTheMessageIsSentOnlyAfterCommit() {
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        TransactionSynchronizationManager.initSynchronization();

        producer.publishAfterCommit(id);

        verify(kafka, never()).send(anyString(), anyString(), anyString());
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(kafka).send(TOPIC, id.toString(), "{\"transferId\":\"" + id + "\"}");
    }

    @Test
    void rolledBackTransactionNeverSendsAMessage() {
        TransactionSynchronizationManager.initSynchronization();

        producer.publishAfterCommit(id);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void brokerFailureNeverReachesTheCaller() {
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("broker down"));

        assertThatCode(() -> producer.publishAfterCommit(id)).doesNotThrowAnyException();
    }

    @Test
    void asyncSendFailureNeverReachesTheCaller() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("timeout")));

        assertThatCode(() -> producer.publishAfterCommit(id)).doesNotThrowAnyException();
    }
}
