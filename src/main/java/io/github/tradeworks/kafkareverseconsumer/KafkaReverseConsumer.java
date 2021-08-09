package io.github.tradeworks.kafkareverseconsumer;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;

@SuppressWarnings("unused")
public class KafkaReverseConsumer<K, V> implements ReverseConsumer<K, V> {

    private static final Logger log = LoggerFactory.getLogger(KafkaReverseConsumer.class);

    private final int seekDelta;
    private final Consumer<K, V> underlyingConsumer;
    private final Comparator<ConsumerRecord<K, V>> reverseOffsetComparator;
    private final Map<TopicPartition, SortedSet<ConsumerRecord<K, V>>> buffer = new HashMap<>();

    /**
     * Contains the offset of the next record to be returned by poll; the actual offset of the next record may be
     * smaller, due to log compaction (TODO: or when using a consumer in read_committed mode).
     */
    private final Map<TopicPartition, Long> positions = new HashMap<>();

    public KafkaReverseConsumer(Consumer<K, V> underlyingConsumer, int maxPollRecords) {
        this.underlyingConsumer = underlyingConsumer;
        Comparator<ConsumerRecord<K, V>> offsetComparator = Comparator.comparing(ConsumerRecord::offset);
        reverseOffsetComparator = offsetComparator.reversed();
        seekDelta = maxPollRecords;
    }

    @Override
    public ConsumerRecords<K, V> poll(Duration timeout) {
        long endTime = System.nanoTime() + timeout.toNanos();
        Map<TopicPartition, List<ConsumerRecord<K, V>>> reverseRecordsMap = new HashMap<>();
        do {
            final Duration remainingTimeout = Duration.ofNanos(endTime - System.nanoTime());
            if (remainingTimeout.isNegative())
                break;
            final long beforePoll = System.nanoTime();
            final ConsumerRecords<K, V> consumerRecords = underlyingConsumer.poll(remainingTimeout);
            final long pollTime = (System.nanoTime() - beforePoll) / 1_000_000;
            log.debug("polled {} records in {} msec", consumerRecords.count(), pollTime);
            final Set<TopicPartition> partitions = consumerRecords.partitions();
            for (TopicPartition partition : partitions) {
                final List<ConsumerRecord<K, V>> records = consumerRecords.records(partition);
                long position = positions.get(partition);
                Collection<ConsumerRecord<K, V>> reverseRecords = buffer.get(partition);
                if (reverseRecords == null)
                    reverseRecords = new ArrayList<>();
                final ListIterator<ConsumerRecord<K, V>> listIterator = records.listIterator(records.size());
                boolean firstRecord = true;
                boolean hasGap = false;
                while (listIterator.hasPrevious()) {
                    final ConsumerRecord<K, V> record = listIterator.previous();
                    final long offset = record.offset();
                    if (firstRecord) {
                        hasGap = offset < position;
                        firstRecord = false;
                        if (hasGap) {
                            final long seekOffset = offset + 1;
                            log.debug("partition {}: Seeking to offset {} because of gap", partition, seekOffset);
                            underlyingConsumer.seek(partition, seekOffset);
                        }
                    }
                    if (offset <= position) {
                        reverseRecords.add(record);
                        if (!hasGap)
                            position = offset - 1;
                    }
                }
                if (!hasGap) {
                    final long seekOffset = position - seekDelta + 1;
                    log.debug("partition {}: Seeking to offset {}", partition, seekOffset);
                    underlyingConsumer.seek(partition, seekOffset);
                }
                if (reverseRecords.size() > 0) {
                    boolean wasBuffered = reverseRecords instanceof TreeSet;
                    if (hasGap) {
                        if (!wasBuffered) {
                            final TreeSet<ConsumerRecord<K, V>> bufRecords = new TreeSet<>(reverseOffsetComparator);
                            bufRecords.addAll(reverseRecords);
                            buffer.put(partition, bufRecords);
                            log.debug("partition {}: Buffering {} records", partition, bufRecords.size());
                        }
                    } else {
                        if (wasBuffered) {
                            buffer.remove(partition);
                            reverseRecordsMap.put(partition, new ArrayList<>(reverseRecords));
                        } else {
                            reverseRecordsMap.put(partition, (List<ConsumerRecord<K, V>>) reverseRecords);
                        }
                        positions.put(partition, position);
                    }
                }
            }

        } while (reverseRecordsMap.isEmpty());
        return new ConsumerRecords<>(reverseRecordsMap);
    }

    /**
     * Will reset all positions.
     */
    @Override
    public void assign(Collection<TopicPartition> partitions) {
        underlyingConsumer.assign(partitions);
        determinePositions(partitions);
        seekToPositions();
    }

    private void determinePositions(Collection<TopicPartition> partitions) {
        positions.clear();
        final Map<TopicPartition, Long> beginningOffsets = underlyingConsumer.beginningOffsets(partitions);
        if (partitions.size() != beginningOffsets.size()) // should never happen
            throw new RuntimeException("requested beginning offsets for " + partitions + " partitions, but received " + beginningOffsets);
        final Map<TopicPartition, Long> endOffsets = underlyingConsumer.endOffsets(partitions);
        if (partitions.size() != endOffsets.size()) // should never happen
            throw new RuntimeException("requested end offsets for " + partitions + " partitions, but received " + endOffsets);
        for (Map.Entry<TopicPartition, Long> entry : endOffsets.entrySet()) {
            final TopicPartition topicPartition = entry.getKey();
            final Long endOffset = entry.getValue();
            boolean partitionIsEmpty = endOffset.longValue() == beginningOffsets.get(topicPartition).longValue();
            positions.put(topicPartition, partitionIsEmpty ? -1L : endOffset - 1);
        }
    }

    private void seekToPositions() {
        List<TopicPartition> pauseThese = new ArrayList<>();
        List<TopicPartition> resumeThese = new ArrayList<>();
        for (Map.Entry<TopicPartition, Long> entry : positions.entrySet()) {
            final TopicPartition partition = entry.getKey();
            final Long position = entry.getValue();
            if (position == -1) {
                pauseThese.add(partition);
                continue;
            }
            resumeThese.add(partition); // todo: is this necessary, when we seek?
            long seekOffset = Math.max(0, position - seekDelta + 1);
            underlyingConsumer.seek(partition, seekOffset);
            log.debug("partition {}: Seeking to offset {} in assign", partition, seekOffset);
        }
        if (pauseThese.size() > 0)
            underlyingConsumer.pause(pauseThese);
        if (resumeThese.size() > 0)
            underlyingConsumer.resume(resumeThese);
    }

    // purely delegating methods
    @Override public Set<TopicPartition> paused() { return underlyingConsumer.paused(); }
    @Override public void pause(Collection<TopicPartition> partitions) { underlyingConsumer.pause(partitions); }
    @Override public void resume(Collection<TopicPartition> partitions) { underlyingConsumer.resume(partitions); }
    @Override public void close() { underlyingConsumer.close(); }
    @Override public void close(Duration timeout) { underlyingConsumer.close(timeout); }
    @Override public void wakeup() { underlyingConsumer.wakeup(); }

}
