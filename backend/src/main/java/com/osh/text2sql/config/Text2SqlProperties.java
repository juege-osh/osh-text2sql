package com.osh.text2sql.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "osh.text2sql")
public class Text2SqlProperties {

    @Min(1)
    private int queryLimit = 100;

    @Min(1)
    private int defaultTimeoutSeconds = 20;

    private AiProperties ai = new AiProperties();

    private DatasourceProperties datasources = new DatasourceProperties();

    @Data
    public static class AiProperties {
        private String provider = "aicodee";
        private boolean explainResult = true;
        private String reasoningEffort = "medium";
    }

    @Data
    public static class DatasourceProperties {
        private MysqlProperties mysql = new MysqlProperties();
        private RedisProperties redis = new RedisProperties();
        private ElasticsearchProperties elasticsearch = new ElasticsearchProperties();
        private KafkaProperties kafka = new KafkaProperties();
        private HbaseProperties hbase = new HbaseProperties();
    }

    @Data
    public static class MysqlProperties {
        private boolean enabled = true;
        @NotBlank
        private String host;
        private int port = 3306;
        @NotBlank
        private String database;
        @NotBlank
        private String username;
        private String password;
        private SchemaCacheProperties schemaCache = new SchemaCacheProperties();
    }

    @Data
    public static class RedisProperties {
        private boolean enabled = true;
        @NotBlank
        private String host;
        private int port = 6379;
        private String password;
        private int database;
    }

    @Data
    public static class ElasticsearchProperties {
        private boolean enabled = true;
        @NotBlank
        private String baseUrl;
        private String username;
        private String password;
    }

    @Data
    public static class KafkaProperties {
        private boolean enabled = true;
        @NotBlank
        private String bootstrapServers;
        private String securityProtocol = "PLAINTEXT";
        private String saslMechanism;
        private String username;
        private String password;
    }

    @Data
    public static class HbaseProperties {
        private boolean enabled = true;
        @NotBlank
        private String zookeeperQuorum;
        private int zookeeperClientPort = 2181;
        private String znodeParent = "/hbase";
        private String namespace = "default";
    }

    @Data
    public static class SchemaCacheProperties {
        private boolean enabled = true;
        private int ttlMinutes = 180;
        private String keyPrefix = "osh:text2sql:mysql:schema";
    }
}
