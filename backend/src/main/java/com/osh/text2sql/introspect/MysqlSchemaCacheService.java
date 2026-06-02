package com.osh.text2sql.introspect;

import com.osh.text2sql.config.Text2SqlProperties;
import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.util.JsonUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import jakarta.annotation.PreDestroy;

@Service
public class MysqlSchemaCacheService {

    private static final Logger log = LoggerFactory.getLogger(MysqlSchemaCacheService.class);
    private static final int DEFAULT_REDIS_CONNECT_TIMEOUT_MS = 1500;
    private static final int DEFAULT_REDIS_SOCKET_TIMEOUT_MS = 1500;
    private static final String SCHEMA_CACHE_VERSION = "v2";
    private final RedisStringStore redis;
    private final boolean enabled;
    private final String keyPrefix;
    private final Duration ttl;

    @Autowired
    public MysqlSchemaCacheService(Text2SqlProperties properties, RedisProperties redisProperties) {
        this(
            new JedisRedisStringStore(redisProperties),
            properties.getDatasources().getMysql().getSchemaCache().isEnabled(),
            properties.getDatasources().getMysql().getSchemaCache().getKeyPrefix(),
            Duration.ofMinutes(properties.getDatasources().getMysql().getSchemaCache().getTtlMinutes())
        );
    }

    MysqlSchemaCacheService(RedisStringStore redis, boolean enabled, String keyPrefix, Duration ttl) {
        this.redis = redis;
        this.enabled = enabled;
        this.keyPrefix = keyPrefix;
        this.ttl = ttl;
    }

    public Optional<MysqlSchemaCachePayload> getSchema(ConnectionProfile profile) {
        if (!enabled) {
            return Optional.empty();
        }
        long start = System.currentTimeMillis();
        try {
            log.info("开始读取 MySQL 结构缓存：database={}, key={}", profile.getDatabase(), schemaMetaKey(profile));
            long metaStart = System.currentTimeMillis();
            String metaRaw = redis.get(schemaMetaKey(profile));
            if (metaRaw == null || metaRaw.isBlank()) {
                log.info("MySQL 结构缓存未命中：database={}", profile.getDatabase());
                return Optional.empty();
            }
            SchemaMeta meta = JsonUtils.fromJson(metaRaw, SchemaMeta.class);
            long metaElapsed = System.currentTimeMillis() - metaStart;
            long tablesStart = System.currentTimeMillis();
            String tablesRaw = redis.get(schemaTablesKey(profile));
            if (tablesRaw == null || tablesRaw.isBlank()) {
                log.info("MySQL 结构缓存中的表清单未命中：database={}", profile.getDatabase());
                return Optional.empty();
            }
            List<MysqlSchemaCachePayload.TableMeta> tables = JsonUtils.fromJson(
                tablesRaw,
                new com.fasterxml.jackson.core.type.TypeReference<List<MysqlSchemaCachePayload.TableMeta>>() {
                }
            );
            long tablesElapsed = System.currentTimeMillis() - tablesStart;
            List<String> columnKeys = tables.stream()
                .map(table -> tableColumnsKey(profile, table.tableName()))
                .toList();
            List<String> indexKeys = tables.stream()
                .map(table -> tableIndexesKey(profile, table.tableName()))
                .toList();
            long batchStart = System.currentTimeMillis();
            List<String> columnsRawList = redis.getMany(columnKeys);
            List<String> indexesRawList = redis.getMany(indexKeys);
            long batchElapsed = System.currentTimeMillis() - batchStart;
            long deserializeStart = System.currentTimeMillis();
            Map<String, Object> schema = new LinkedHashMap<>();
            for (int index = 0; index < tables.size(); index++) {
                MysqlSchemaCachePayload.TableMeta table = tables.get(index);
                String columnsRaw = index < columnsRawList.size() ? columnsRawList.get(index) : null;
                String indexesRaw = index < indexesRawList.size() ? indexesRawList.get(index) : null;
                if (columnsRaw == null || columnsRaw.isBlank()) {
                    log.info("MySQL 结构缓存中的表字段未命中：database={}, table={}", profile.getDatabase(), table.tableName());
                    return Optional.empty();
                }
                List<Map<String, Object>> columns = JsonUtils.fromJson(
                    columnsRaw,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                    }
                );
                List<Map<String, Object>> indexes = indexesRaw == null || indexesRaw.isBlank()
                    ? List.of()
                    : JsonUtils.fromJson(
                        indexesRaw,
                        new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                        }
                    );
                schema.put(table.tableName(), Map.of(
                    "columns", columns,
                    "indexes", indexes
                ));
            }
            long deserializeElapsed = System.currentTimeMillis() - deserializeStart;
            log.info("MySQL 结构缓存命中：database={}, tableCount={}, redisKeyReads={}, metaElapsedMs={}, tablesElapsedMs={}, batchElapsedMs={}, deserializeElapsedMs={}, elapsedMs={}",
                profile.getDatabase(),
                tables.size(),
                2 + (tables.size() * 2),
                metaElapsed,
                tablesElapsed,
                batchElapsed,
                deserializeElapsed,
                System.currentTimeMillis() - start);
            return Optional.of(new MysqlSchemaCachePayload(meta.name(), meta.summary(), schema, tables));
        } catch (Exception exception) {
            log.warn("MySQL 结构缓存不可用，改为直接读取 MySQL 结构：database={}, message={}",
                profile.getDatabase(), exception.getMessage());
            return Optional.empty();
        }
    }

    public Optional<List<MysqlSchemaCachePayload.TableMeta>> getTableList(ConnectionProfile profile) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            String tablesRaw = redis.get(schemaTablesKey(profile));
            if (tablesRaw == null || tablesRaw.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(JsonUtils.fromJson(
                tablesRaw,
                new com.fasterxml.jackson.core.type.TypeReference<List<MysqlSchemaCachePayload.TableMeta>>() {
                }
            ));
        } catch (Exception exception) {
            log.warn("MySQL 表清单缓存读取失败：database={}, message={}",
                profile.getDatabase(), exception.getMessage());
            return Optional.empty();
        }
    }

    public void putSchema(ConnectionProfile profile, MysqlSchemaCachePayload payload) {
        if (!enabled) {
            return;
        }
        redis.set(schemaMetaKey(profile), JsonUtils.toJson(new SchemaMeta(payload.name(), payload.summary())), ttl);
        redis.set(schemaTablesKey(profile), JsonUtils.toJson(payload.tables()), ttl);
        for (MysqlSchemaCachePayload.TableMeta table : payload.tables()) {
            Map<String, Object> tableSchema = payload.schema().get(table.tableName()) instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of("columns", List.of(), "indexes", List.of());
            redis.set(
                tableColumnsKey(profile, table.tableName()),
                JsonUtils.toJson(tableSchema.getOrDefault("columns", List.of())),
                ttl
            );
            redis.set(
                tableIndexesKey(profile, table.tableName()),
                JsonUtils.toJson(tableSchema.getOrDefault("indexes", List.of())),
                ttl
            );
        }
    }

    public void putTableList(ConnectionProfile profile, List<MysqlSchemaCachePayload.TableMeta> tables) {
        if (!enabled) {
            return;
        }
        redis.set(schemaTablesKey(profile), JsonUtils.toJson(tables), ttl);
    }

    public void evictSchema(ConnectionProfile profile) {
        if (!enabled) {
            return;
        }
        Optional<MysqlSchemaCachePayload> existing = getSchema(profile);
        redis.delete(schemaMetaKey(profile));
        redis.delete(schemaTablesKey(profile));
        existing.ifPresent(payload -> payload.tables().forEach(table -> {
            redis.delete(tableColumnsKey(profile, table.tableName()));
            redis.delete(tableIndexesKey(profile, table.tableName()));
        }));
    }

    String schemaBaseKey(ConnectionProfile profile) {
        return "%s:%s:%d:%s:%s".formatted(keyPrefix, profile.getHost(), profile.getPort(), profile.getDatabase(), SCHEMA_CACHE_VERSION);
    }

    String schemaMetaKey(ConnectionProfile profile) {
        return schemaBaseKey(profile) + ":meta";
    }

    String schemaTablesKey(ConnectionProfile profile) {
        return schemaBaseKey(profile) + ":tables";
    }

    String tableColumnsKey(ConnectionProfile profile, String tableName) {
        return schemaBaseKey(profile) + ":table:" + tableName + ":columns";
    }

    String tableIndexesKey(ConnectionProfile profile, String tableName) {
        return schemaBaseKey(profile) + ":table:" + tableName + ":indexes";
    }

    @PreDestroy
    void destroy() {
        if (redis instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception exception) {
                log.warn("关闭 MySQL 结构缓存 Redis 连接池失败：message={}", exception.getMessage());
            }
        }
    }

    private record SchemaMeta(String name, String summary) {
    }

    interface RedisStringStore {
        String get(String key);

        List<String> getMany(List<String> keys);

        void set(String key, String value, Duration ttl);

        void delete(String key);
    }

    private static final class JedisRedisStringStore implements RedisStringStore, AutoCloseable {
        private final JedisPool jedisPool;

        private JedisRedisStringStore(RedisProperties redisProperties) {
            this.jedisPool = createPool(redisProperties);
        }

        @Override
        public String get(String key) {
            try (Jedis jedis = jedisPool.getResource()) {
                return jedis.get(key);
            }
        }

        @Override
        public List<String> getMany(List<String> keys) {
            if (keys == null || keys.isEmpty()) {
                return List.of();
            }
            try (Jedis jedis = jedisPool.getResource()) {
                return jedis.mget(keys.toArray(String[]::new));
            }
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.setex(key, Math.toIntExact(ttl.getSeconds()), value);
            }
        }

        @Override
        public void delete(String key) {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.del(key);
            }
        }

        @Override
        public void close() {
            jedisPool.close();
        }

        private JedisPool createPool(RedisProperties redisProperties) {
            String password = redisProperties.getPassword();
            int database = redisProperties.getDatabase();
            DefaultJedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis((int) (redisProperties.getConnectTimeout() == null
                    ? DEFAULT_REDIS_CONNECT_TIMEOUT_MS
                    : redisProperties.getConnectTimeout().toMillis()))
                .socketTimeoutMillis(DEFAULT_REDIS_SOCKET_TIMEOUT_MS)
                .password(password)
                .database(database)
                .build();
            String host = redisProperties.getHost() == null || redisProperties.getHost().isBlank()
                ? "127.0.0.1"
                : redisProperties.getHost();
            int port = redisProperties.getPort() == 0 ? 6379 : redisProperties.getPort();
            return new JedisPool(new JedisPoolConfig(), new HostAndPort(host, port), clientConfig);
        }
    }
}
