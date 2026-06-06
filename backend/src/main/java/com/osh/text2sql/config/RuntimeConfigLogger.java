package com.osh.text2sql.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 运行时配置日志
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeConfigLogger {

    private final Text2SqlProperties properties;

    @PostConstruct
    public void logRuntimeConfig() {
        Text2SqlProperties.MysqlProperties mysql = properties.getDatasources().getMysql();
        Text2SqlProperties.RedisProperties redis = properties.getDatasources().getRedis();
        Text2SqlProperties.ElasticsearchProperties es = properties.getDatasources().getElasticsearch();
        Text2SqlProperties.KafkaProperties kafka = properties.getDatasources().getKafka();
        Text2SqlProperties.HbaseProperties hbase = properties.getDatasources().getHbase();

        log.info("运行时 MySQL 配置: host={}, port={}, database={}, username={}",
            mysql.getHost(), mysql.getPort(), mysql.getDatabase(), mysql.getUsername());
        log.info("运行时 Redis 配置: host={}, port={}, database={}",
            redis.getHost(), redis.getPort(), redis.getDatabase());
        log.info("运行时 Elasticsearch 配置: baseUrl={}, username={}",
            es.getBaseUrl(), es.getUsername());
        log.info("运行时 Kafka 配置: bootstrapServers={}",
            kafka.getBootstrapServers());
        log.info("运行时 HBase 配置: zookeeperQuorum={}, zookeeperClientPort={}, namespace={}",
            hbase.getZookeeperQuorum(), hbase.getZookeeperClientPort(), hbase.getNamespace());
    }
}
