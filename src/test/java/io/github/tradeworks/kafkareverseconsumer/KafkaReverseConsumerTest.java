package io.github.tradeworks.kafkareverseconsumer;


import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaReverseConsumerTest {

    @Test
    void reachedBeginning() {
        MockConsumer<String, String> consumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        final String topic = "t1";
        final int partition = 0;
        consumer.updatePartitions(topic, List.of(new PartitionInfo(topic, partition, null, null, null)));
        final TopicPartition topicPartition = new TopicPartition(topic, partition);
        consumer.updateBeginningOffsets(Map.of(topicPartition, 0L));
        consumer.updateEndOffsets(Map.of(topicPartition, 0L));
        final KafkaReverseConsumer<String, String> reverseConsumer = new KafkaReverseConsumer<>(consumer, 5);

        reverseConsumer.assign(Set.of(topicPartition));
        assertTrue(reverseConsumer.reachedBeginning(topicPartition));

        final ConsumerRecords<String, String> records = reverseConsumer.poll(Duration.ofMillis(1));
        assertEquals(0, records.count());
    }
}