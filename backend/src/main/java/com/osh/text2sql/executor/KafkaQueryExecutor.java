package com.osh.text2sql.executor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.DatasourceType;
import com.osh.text2sql.dto.KafkaQuerySpec;
import com.osh.text2sql.dto.QueryExecutionResult;
import com.osh.text2sql.exception.BadRequestException;
import com.osh.text2sql.introspect.KafkaIntrospector;
import com.osh.text2sql.util.JsonUtils;
import com.osh.text2sql.util.KafkaQueryValidator;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class KafkaQueryExecutor implements QueryExecutor {

    private final KafkaIntrospector kafkaIntrospector;

    public KafkaQueryExecutor(KafkaIntrospector kafkaIntrospector) {
        this.kafkaIntrospector = kafkaIntrospector;
    }

    @Override
    public QueryExecutionResult execute(ConnectionProfile profile, String query) {
        KafkaQuerySpec spec = KafkaQueryValidator.validate(query);
        long start = System.currentTimeMillis();
        return switch (spec.getOperation()) {
            case "LIST_TOPICS" -> executeListTopics(profile, spec, start);
            case "DESCRIBE_TOPIC" -> executeDescribeTopic(profile, spec, start);
            case "READ_MESSAGES" -> executeReadMessages(profile, spec, start);
            case "COUNT_UNCONSUMED_MESSAGES" -> executeCountUnconsumedMessages(profile, spec, start);
            default -> throw new BadRequestException("不支持的 Kafka 操作");
        };
    }

    private QueryExecutionResult executeListTopics(ConnectionProfile profile, KafkaQuerySpec spec, long start) {
        try (AdminClient adminClient = AdminClient.create(kafkaIntrospector.buildAdminProperties(profile))) {
            Set<String> topicNames = kafkaIntrospector.listTopics(adminClient, Boolean.TRUE.equals(spec.getIncludeInternal()));
            List<Map<String, Object>> rows = topicNames.stream()
                .sorted()
                .limit(spec.getLimit())
                .map(name -> Map.<String, Object>of("topic", name))
                .toList();
            return QueryExecutionResult.builder()
                .type(DatasourceType.KAFKA)
                .executedQuery(compactQuery(spec))
                .queryLanguage("Kafka Query DSL")
                .summary("共发现 %d 个 topic，当前展示 %d 个".formatted(topicNames.size(), rows.size()))
                .columns(List.of("topic"))
                .rows(rows)
                .total(topicNames.size())
                .elapsedMs(System.currentTimeMillis() - start)
                .rawResponse(Map.of("topics", rows))
                .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Kafka topic 列表读取失败: " + exception.getMessage(), exception);
        }
    }

    private QueryExecutionResult executeDescribeTopic(ConnectionProfile profile, KafkaQuerySpec spec, long start) {
        try (AdminClient adminClient = AdminClient.create(kafkaIntrospector.buildAdminProperties(profile))) {
            DescribeTopicsResult result = adminClient.describeTopics(List.of(spec.getTopic()));
            TopicDescription description = result.topicNameValues()
                .get(spec.getTopic())
                .get(8, TimeUnit.SECONDS);
            List<Map<String, Object>> rows = description.partitions().stream()
                .sorted(Comparator.comparingInt(partition -> partition.partition()))
                .map(partition -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("topic", description.name());
                    row.put("partition", partition.partition());
                    row.put("leader", partition.leader() == null ? "unknown" : partition.leader().idString());
                    row.put("replicas", partition.replicas().stream().map(node -> node.idString()).toList());
                    row.put("inSyncReplicas", partition.isr().stream().map(node -> node.idString()).toList());
                    return row;
                })
                .toList();
            return QueryExecutionResult.builder()
                .type(DatasourceType.KAFKA)
                .executedQuery(compactQuery(spec))
                .queryLanguage("Kafka Query DSL")
                .summary("topic %s 共 %d 个分区".formatted(description.name(), rows.size()))
                .columns(List.of("topic", "partition", "leader", "replicas", "inSyncReplicas"))
                .rows(rows)
                .total(rows.size())
                .elapsedMs(System.currentTimeMillis() - start)
                .rawResponse(Map.of(
                    "name", description.name(),
                    "internal", description.isInternal(),
                    "partitions", rows
                ))
                .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Kafka topic 详情读取失败: " + exception.getMessage(), exception);
        }
    }

    private QueryExecutionResult executeReadMessages(ConnectionProfile profile, KafkaQuerySpec spec, long start) {
        Properties consumerProperties = buildConsumerProperties(profile);
        try (AdminClient adminClient = AdminClient.create(kafkaIntrospector.buildAdminProperties(profile));
             Consumer<String, String> consumer = new KafkaConsumer<>(consumerProperties)) {
            List<TopicPartition> partitions = resolveTopicPartitions(consumer, spec);
            if (partitions.isEmpty()) {
                throw new BadRequestException("未找到可读取的 Kafka 分区");
            }

            Map<TopicPartition, Long> beginOffsets = adminClient.listOffsets(buildOffsetRequest(partitions, OffsetSpec.earliest()))
                .all()
                .get(8, TimeUnit.SECONDS)
                .entrySet()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().offset()));
            Map<TopicPartition, Long> endOffsets = adminClient.listOffsets(buildOffsetRequest(partitions, OffsetSpec.latest()))
                .all()
                .get(8, TimeUnit.SECONDS)
                .entrySet()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().offset()));

            consumer.assign(partitions);
            for (TopicPartition partition : partitions) {
                seekPartition(consumer, partition, spec, beginOffsets.getOrDefault(partition, 0L), endOffsets.getOrDefault(partition, 0L));
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            int emptyPolls = 0;
            while (rows.size() < spec.getLimit() && emptyPolls < 4) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(800));
                if (records.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                for (ConsumerRecord<String, String> record : records) {
                    if (!matches(record, spec)) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("topic", record.topic());
                    row.put("partition", record.partition());
                    row.put("offset", record.offset());
                    row.put("timestamp", record.timestamp());
                    row.put("key", record.key());
                    row.put("value", record.value());
                    rows.add(row);
                    if (rows.size() >= spec.getLimit()) {
                        break;
                    }
                }
            }

            return QueryExecutionResult.builder()
                .type(DatasourceType.KAFKA)
                .executedQuery(compactQuery(spec))
                .queryLanguage("Kafka Query DSL")
                .summary("topic %s 读取到 %d 条消息".formatted(spec.getTopic(), rows.size()))
                .columns(List.of("topic", "partition", "offset", "timestamp", "key", "value"))
                .rows(rows)
                .total(rows.size())
                .elapsedMs(System.currentTimeMillis() - start)
                .rawResponse(Map.of(
                    "spec", JsonUtils.fromJson(JsonUtils.toJson(spec), new TypeReference<Map<String, Object>>() {
                    }),
                    "rows", rows,
                    "beginOffsets", stringifyOffsets(beginOffsets),
                    "endOffsets", stringifyOffsets(endOffsets)
                ))
                .build();
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Kafka 消息读取失败: " + exception.getMessage(), exception);
        }
    }

    private QueryExecutionResult executeCountUnconsumedMessages(ConnectionProfile profile, KafkaQuerySpec spec, long start) {
        Properties consumerProperties = buildConsumerProperties(profile);
        try (AdminClient adminClient = AdminClient.create(kafkaIntrospector.buildAdminProperties(profile));
             Consumer<String, String> consumer = new KafkaConsumer<>(consumerProperties)) {
            List<TopicPartition> partitions = resolveTopicPartitions(consumer, spec);
            if (partitions.isEmpty()) {
                throw new BadRequestException("未找到可统计的 Kafka 分区");
            }

            Map<TopicPartition, Long> committedOffsets = adminClient.listConsumerGroupOffsets(spec.getConsumerGroup())
                .partitionsToOffsetAndMetadata()
                .get(8, TimeUnit.SECONDS)
                .entrySet()
                .stream()
                .filter(entry -> partitions.contains(entry.getKey()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> offsetOf(entry.getValue())));
            Map<TopicPartition, Long> endOffsets = adminClient.listOffsets(buildOffsetRequest(partitions, OffsetSpec.latest()))
                .all()
                .get(8, TimeUnit.SECONDS)
                .entrySet()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().offset()));

            consumer.assign(partitions);
            for (TopicPartition partition : partitions) {
                long committed = committedOffsets.getOrDefault(partition, 0L);
                long end = endOffsets.getOrDefault(partition, committed);
                consumer.seek(partition, Math.max(0L, Math.min(committed, end)));
            }

            long matchedUnconsumedCount = 0L;
            int emptyPolls = 0;
            Set<String> seenMessages = new HashSet<>();
            List<Map<String, Object>> rows = new ArrayList<>();

            while (emptyPolls < 4) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(800));
                if (records.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                for (ConsumerRecord<String, String> record : records) {
                    String recordId = record.topic() + "-" + record.partition() + "-" + record.offset();
                    if (!seenMessages.add(recordId)) {
                        continue;
                    }
                    if (!matches(record, spec)) {
                        continue;
                    }
                    matchedUnconsumedCount++;
                    if (rows.size() < Math.min(spec.getLimit(), 20)) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("topic", record.topic());
                        row.put("consumerGroup", spec.getConsumerGroup());
                        row.put("partition", record.partition());
                        row.put("offset", record.offset());
                        row.put("key", record.key());
                        row.put("value", record.value());
                        rows.add(row);
                    }
                }
            }

            List<Map<String, Object>> resultRows = rows.isEmpty()
                ? List.of(Map.of("matchedUnconsumedCount", matchedUnconsumedCount))
                : rows;
            List<String> columns = rows.isEmpty()
                ? List.of("matchedUnconsumedCount")
                : List.of("topic", "consumerGroup", "partition", "offset", "key", "value");

            return QueryExecutionResult.builder()
                .type(DatasourceType.KAFKA)
                .executedQuery(compactQuery(spec))
                .queryLanguage("Kafka Query DSL")
                .summary("topic %s 下 consumer group %s 匹配 key 过滤后的未消费消息数为 %d".formatted(
                    spec.getTopic(), spec.getConsumerGroup(), matchedUnconsumedCount))
                .columns(columns)
                .rows(resultRows)
                .total(matchedUnconsumedCount)
                .elapsedMs(System.currentTimeMillis() - start)
                .rawResponse(Map.of(
                    "spec", JsonUtils.fromJson(JsonUtils.toJson(spec), new TypeReference<Map<String, Object>>() {
                    }),
                    "matchedUnconsumedCount", matchedUnconsumedCount,
                    "committedOffsets", stringifyOffsets(committedOffsets),
                    "endOffsets", stringifyOffsets(endOffsets),
                    "sampleRows", rows
                ))
                .build();
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Kafka 未消费消息统计失败: " + exception.getMessage(), exception);
        }
    }

    private Properties buildConsumerProperties(ConnectionProfile profile) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, profile.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "osh-text2sql-" + UUID.randomUUID());
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, "osh-text2sql-client");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        properties.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 8000);
        properties.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 8000);
        kafkaIntrospector.applySecurityProperties(properties, profile);
        return properties;
    }

    private List<TopicPartition> resolveTopicPartitions(Consumer<String, String> consumer, KafkaQuerySpec spec) {
        List<PartitionInfo> partitionInfos = consumer.partitionsFor(spec.getTopic(), Duration.ofSeconds(8));
        if (partitionInfos == null || partitionInfos.isEmpty()) {
            throw new BadRequestException("Kafka topic 不存在或没有可用分区: " + spec.getTopic());
        }
        return partitionInfos.stream()
            .filter(info -> spec.getPartition() == null || info.partition() == spec.getPartition())
            .sorted(Comparator.comparingInt(PartitionInfo::partition))
            .map(info -> new TopicPartition(info.topic(), info.partition()))
            .toList();
    }

    private Map<TopicPartition, OffsetSpec> buildOffsetRequest(List<TopicPartition> partitions, OffsetSpec spec) {
        Map<TopicPartition, OffsetSpec> request = new LinkedHashMap<>();
        for (TopicPartition partition : partitions) {
            request.put(partition, spec);
        }
        return request;
    }

    private void seekPartition(Consumer<String, String> consumer,
                               TopicPartition partition,
                               KafkaQuerySpec spec,
                               long beginOffset,
                               long endOffset) {
        if ("EARLIEST".equals(spec.getFrom())) {
            consumer.seek(partition, beginOffset);
            return;
        }
        if ("OFFSET".equals(spec.getFrom())) {
            long offset = Math.max(beginOffset, Math.min(spec.getOffset(), Math.max(beginOffset, endOffset)));
            consumer.seek(partition, offset);
            return;
        }
        long limit = Math.max(1, spec.getLimit());
        long offset = Math.max(beginOffset, endOffset - limit);
        consumer.seek(partition, offset);
    }

    private boolean matches(ConsumerRecord<String, String> record, KafkaQuerySpec spec) {
        if (spec.getKeyContains() != null) {
            String key = record.key() == null ? "" : record.key();
            if (!key.contains(spec.getKeyContains())) {
                return false;
            }
        }
        if (spec.getValueContains() != null) {
            String value = record.value() == null ? "" : record.value();
            if (!value.contains(spec.getValueContains())) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Long> stringifyOffsets(Map<TopicPartition, Long> offsets) {
        Map<String, Long> result = new LinkedHashMap<>();
        offsets.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator
                .comparing(TopicPartition::topic)
                .thenComparingInt(TopicPartition::partition)))
            .forEach(entry -> result.put(entry.getKey().topic() + "-" + entry.getKey().partition(), entry.getValue()));
        return result;
    }

    private long offsetOf(OffsetAndMetadata offsetAndMetadata) {
        return offsetAndMetadata == null ? 0L : offsetAndMetadata.offset();
    }

    private String compactQuery(KafkaQuerySpec spec) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", spec.getOperation());
        if (spec.getTopic() != null) payload.put("topic", spec.getTopic());
        if (spec.getConsumerGroup() != null) payload.put("consumerGroup", spec.getConsumerGroup());
        if (spec.getPartition() != null) payload.put("partition", spec.getPartition());
        if (spec.getLimit() != null) payload.put("limit", spec.getLimit());
        if (spec.getFrom() != null) payload.put("from", spec.getFrom());
        if (spec.getOffset() != null) payload.put("offset", spec.getOffset());
        if (spec.getIncludeInternal() != null) payload.put("includeInternal", spec.getIncludeInternal());
        if (spec.getKeyContains() != null) payload.put("keyContains", spec.getKeyContains());
        if (spec.getValueContains() != null) payload.put("valueContains", spec.getValueContains());
        return JsonUtils.toJson(payload);
    }
}
