package com.osh.text2sql.introspect;

import cn.hutool.core.util.StrUtil;
import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.ConnectionTestResponse;
import com.osh.text2sql.dto.DatasourceSchemaResponse;
import com.osh.text2sql.dto.DatasourceType;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RedisIntrospector implements DatasourceIntrospector {

    @Override
    public DatasourceSchemaResponse introspect(ConnectionProfile profile) {
        try (JedisPool pool = createPool(profile); Jedis jedis = pool.getResource()) {
            if (profile.getDatabase() != null) {
                jedis.select(Integer.parseInt(profile.getDatabase()));
            }
            List<String> keys = jedis.scan("0").getResult().stream().limit(20).toList();
            Map<String, Object> schema = new LinkedHashMap<>();
            for (String key : keys) {
                schema.put(key, Map.of(
                    "type", jedis.type(key),
                    "ttl", jedis.ttl(key)
                ));
            }
            return DatasourceSchemaResponse.builder()
                .type(DatasourceType.REDIS)
                .name("redis-db-" + profile.getDatabase())
                .summary("Redis DB %s，展示前 20 个 key 的类型与 TTL".formatted(profile.getDatabase()))
                .schema(schema)
                .build();
        }
    }

    @Override
    public ConnectionTestResponse test(ConnectionProfile profile) {
        long start = System.currentTimeMillis();
        try (JedisPool pool = createPool(profile); Jedis jedis = pool.getResource()) {
            if (profile.getDatabase() != null) {
                jedis.select(Integer.parseInt(profile.getDatabase()));
            }
            Map<String, Object> preview = Map.of(
                "pong", jedis.ping(),
                "dbSize", jedis.dbSize()
            );
            return ConnectionTestResponse.builder()
                .success(true)
                .message("Redis 连接成功")
                .elapsedMs(Duration.ofMillis(System.currentTimeMillis() - start).toMillis())
                .preview(preview)
                .build();
        }
    }

    private JedisPool createPool(ConnectionProfile profile) {
        String password = StrUtil.isBlank(profile.getPassword()) ? null : profile.getPassword();
        return new JedisPool(new JedisPoolConfig(), profile.getHost(), profile.getPort(), 5000, password);
    }
}
