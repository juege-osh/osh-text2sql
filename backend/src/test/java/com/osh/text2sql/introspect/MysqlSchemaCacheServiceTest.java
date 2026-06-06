package com.osh.text2sql.introspect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.util.JsonUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

class MysqlSchemaCacheServiceTest {

    @Test
    void shouldReturnCachedSchemaWhenRedisContainsEntry() {
        InMemoryRedis redis = new InMemoryRedis();
        MysqlSchemaCacheService service = new MysqlSchemaCacheService(redis, true, "osh:text2sql:mysql:schema", java.time.Duration.ofMinutes(180));
        ConnectionProfile profile = mysqlProfile("analytics");
        MysqlSchemaCachePayload payload = new MysqlSchemaCachePayload(
            "analytics",
            "cached summary",
            Map.of("osh_user", Map.of(
                "columns", java.util.List.of(Map.of("columnName", "id")),
                "indexes", java.util.List.of(Map.of("indexName", "PRIMARY", "columnName", "id"))
            )),
            java.util.List.of(new MysqlSchemaCachePayload.TableMeta("osh_user", "用户主表"))
        );
        service.putSchema(profile, payload);

        Optional<MysqlSchemaCachePayload> cached = service.getSchema(profile);

        Assertions.assertTrue(cached.isPresent());
        Assertions.assertEquals("cached summary", cached.get().summary());
        Map<?, ?> tableSchema = (Map<?, ?>) cached.get().schema().get("osh_user");
        Assertions.assertEquals("id", ((Map<?, ?>) ((java.util.List<?>) tableSchema.get("columns")).get(0)).get("columnName"));
    }

    @Test
    void shouldWriteSchemaIntoRedisUsingStableKey() {
        InMemoryRedis redis = new InMemoryRedis();
        MysqlSchemaCacheService service = new MysqlSchemaCacheService(redis, true, "osh:text2sql:mysql:schema", java.time.Duration.ofMinutes(180));
        ConnectionProfile profile = mysqlProfile("backstage");
        MysqlSchemaCachePayload payload = new MysqlSchemaCachePayload(
            "backstage",
            "schema summary",
            Map.of("assistant_feedback", Map.of(
                "columns", java.util.List.of(Map.of("columnName", "create_time")),
                "indexes", java.util.List.of(Map.of("indexName", "idx_create_time", "columnName", "create_time"))
            )),
            java.util.List.of(new MysqlSchemaCachePayload.TableMeta("assistant_feedback", "反馈表"))
        );

        service.putSchema(profile, payload);

        Assertions.assertNotNull(redis.values.get(service.schemaMetaKey(profile)));
        Assertions.assertNotNull(redis.values.get(service.schemaTablesKey(profile)));
        Assertions.assertNotNull(redis.values.get(service.tableColumnsKey(profile, "assistant_feedback")));
        Assertions.assertNotNull(redis.values.get(service.tableIndexesKey(profile, "assistant_feedback")));
    }

    @Test
    void shouldDeleteSchemaCacheWhenEvictingProfile() {
        InMemoryRedis redis = new InMemoryRedis();
        MysqlSchemaCacheService service = new MysqlSchemaCacheService(redis, true, "osh:text2sql:mysql:schema", java.time.Duration.ofMinutes(180));
        ConnectionProfile profile = mysqlProfile("backstage");
        service.putSchema(profile, new MysqlSchemaCachePayload(
            "backstage",
            "schema summary",
            Map.of("assistant_feedback", Map.of(
                "columns", java.util.List.of(Map.of("columnName", "create_time")),
                "indexes", java.util.List.of()
            )),
            java.util.List.of(new MysqlSchemaCachePayload.TableMeta("assistant_feedback", "反馈表"))
        ));

        service.evictSchema(profile);

        Assertions.assertFalse(redis.values.containsKey(service.schemaMetaKey(profile)));
        Assertions.assertFalse(redis.values.containsKey(service.schemaTablesKey(profile)));
        Assertions.assertFalse(redis.values.containsKey(service.tableColumnsKey(profile, "assistant_feedback")));
        Assertions.assertFalse(redis.values.containsKey(service.tableIndexesKey(profile, "assistant_feedback")));
    }

    private ConnectionProfile mysqlProfile(String database) {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setType(com.osh.text2sql.dto.DatasourceType.MYSQL);
        profile.setHost("127.0.0.1");
        profile.setPort(3306);
        profile.setDatabase(database);
        profile.setUsername("root");
        profile.setPassword("secret");
        return profile;
    }

    private static final class InMemoryRedis implements MysqlSchemaCacheService.RedisStringStore {
        private final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public java.util.List<String> getMany(java.util.List<String> keys) {
            return keys.stream().map(values::get).toList();
        }

        @Override
        public void set(String key, String value, java.time.Duration ttl) {
            values.put(key, value);
        }

        @Override
        public void delete(String key) {
            values.remove(key);
        }
    }
}
