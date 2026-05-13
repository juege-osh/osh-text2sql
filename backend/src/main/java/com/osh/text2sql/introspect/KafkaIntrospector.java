package com.osh.text2sql.introspect;

import cn.hutool.core.util.StrUtil;
import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.ConnectionTestResponse;
import com.osh.text2sql.dto.DatasourceSchemaResponse;
import com.osh.text2sql.dto.DatasourceType;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class KafkaIntrospector implements DatasourceIntrospector {

    private static final int DEFAULT_TIMEOUT_SECONDS = 8;

    @Override
    public DatasourceSchemaResponse introspect(ConnectionProfile profile) {
        try (AdminClient adminClient = AdminClient.create(buildAdminProperties(profile))) {
            Set<String> topicNames = listTopics(adminClient, false);
            List<String> selectedTopics = topicNames.stream()
                .sorted()
                .limit(12)
                .toList();
            DescribeTopicsResult describeTopicsResult = adminClient.describeTopics(selectedTopics);
            Map<String, KafkaFuture<TopicDescription>> values = describeTopicsResult.topicNameValues();
            Map<String, Object> schema = new LinkedHashMap<>();
            for (String topic : selectedTopics) {
                TopicDescription description = values.get(topic).get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                schema.put(topic, Map.of(
                    "partitions", description.partitions().size(),
                    "internal", description.isInternal(),
                    "replicationFactor", description.partitions().isEmpty() ? 0 : description.partitions().get(0).replicas().size(),
                    "partitionLeaders", description.partitions().stream()
                        .map(partitionInfo -> Map.of(
                            "partition", partitionInfo.partition(),
                            "leader", partitionInfo.leader() == null ? "unknown" : partitionInfo.leader().idString()
                        ))
                        .toList()
                ));
            }
            return DatasourceSchemaResponse.builder()
                .type(DatasourceType.KAFKA)
                .name(profile.getBootstrapServers())
                .summary("Kafka 集群主题摘要，展示前 %d 个 topic 的分区与副本信息".formatted(schema.size()))
                .schema(schema)
                .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Kafka 结构读取失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public ConnectionTestResponse test(ConnectionProfile profile) {
        long start = System.currentTimeMillis();
        try (AdminClient adminClient = AdminClient.create(buildAdminProperties(profile))) {
            Set<String> topicNames = listTopics(adminClient, false);
            List<String> previewTopics = topicNames.stream()
                .sorted()
                .limit(10)
                .toList();
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("bootstrapServers", profile.getBootstrapServers());
            preview.put("topicCount", topicNames.size());
            preview.put("topics", previewTopics);
            return ConnectionTestResponse.builder()
                .success(true)
                .message("Kafka 连接成功")
                .elapsedMs(Duration.ofMillis(System.currentTimeMillis() - start).toMillis())
                .preview(preview)
                .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Kafka 连接失败: " + exception.getMessage(), exception);
        }
    }

    public Properties buildAdminProperties(ConnectionProfile profile) {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, profile.getBootstrapServers());
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 8000);
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 8000);
        applySecurityProperties(properties, profile);
        return properties;
    }

    public Set<String> listTopics(AdminClient adminClient, boolean includeInternal) throws Exception {
        ListTopicsOptions options = new ListTopicsOptions().listInternal(includeInternal);
        ListTopicsResult topicsResult = adminClient.listTopics(options);
        return topicsResult.names().get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public void applySecurityProperties(Properties properties, ConnectionProfile profile) {
        String securityProtocol = StrUtil.blankToDefault(profile.getSecurityProtocol(), "PLAINTEXT");
        properties.put("security.protocol", securityProtocol);
        if (securityProtocol.startsWith("SASL")) {
            String mechanism = StrUtil.blankToDefault(profile.getSaslMechanism(), "PLAIN");
            properties.put("sasl.mechanism", mechanism);
            if (StrUtil.isNotBlank(profile.getUsername())) {
                properties.put("sasl.jaas.config",
                    "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"%s\" password=\"%s\";"
                        .formatted(profile.getUsername(), profile.getPassword() == null ? "" : profile.getPassword()));
            }
        }
    }
}
