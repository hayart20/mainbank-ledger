package com.mainbank.ledger.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
class KafkaConfig {

    /** Created at startup by Spring's KafkaAdmin if missing (single replica: development broker). */
    @Bean
    NewTopic transferCompletedTopic(@Value("${ledger.kafka.transfer-completed-topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }
}
