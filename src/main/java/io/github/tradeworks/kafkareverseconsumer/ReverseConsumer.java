package io.github.tradeworks.kafkareverseconsumer;

import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;

import java.io.Closeable;
import java.time.Duration;
import java.util.Collection;
import java.util.Set;

@SuppressWarnings("unused")
public interface ReverseConsumer<K, V> extends Closeable {

    /**
     * @see org.apache.kafka.clients.consumer.KafkaConsumer#assign(Collection)
     */
    void assign(Collection<TopicPartition> partitions);

    /**
     * @see org.apache.kafka.clients.consumer.KafkaConsumer#poll(Duration)
     */
    ConsumerRecords<K, V> poll(Duration timeout);

    /**
     * @see org.apache.kafka.clients.consumer.KafkaConsumer#paused()
     */
    Set<TopicPartition> paused();

    /**
     * @see org.apache.kafka.clients.consumer.KafkaConsumer#pause(Collection)
     */
    void pause(Collection<TopicPartition> partitions);

    /**
     * @see org.apache.kafka.clients.consumer.KafkaConsumer#resume(Collection)
     */
    void resume(Collection<TopicPartition> partitions);

    /**
     * @see org.apache.kafka.clients.consumer.KafkaConsumer#close()
     */
    void close();

    /**
     * @see org.apache.kafka.clients.consumer.KafkaConsumer#close(Duration)
     */
    void close(Duration timeout);

    /**
     * @see org.apache.kafka.clients.consumer.KafkaConsumer#wakeup()
     */
    void wakeup();
}
