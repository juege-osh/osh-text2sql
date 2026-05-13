package com.osh.text2sql.executor;

import cn.hutool.core.util.StrUtil;
import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.DatasourceType;
import com.osh.text2sql.dto.QueryExecutionResult;
import com.osh.text2sql.exception.BadRequestException;
import com.osh.text2sql.util.RedisCommandValidator;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RedisQueryExecutor implements QueryExecutor {

    @Override
    public QueryExecutionResult execute(ConnectionProfile profile, String query) {
        List<String> tokens = RedisCommandValidator.validate(query);
        long start = System.currentTimeMillis();
        try (JedisPool pool = createPool(profile); Jedis jedis = pool.getResource()) {
            if (profile.getDatabase() != null) {
                jedis.select(Integer.parseInt(profile.getDatabase()));
            }
            Object response = executeCommand(jedis, tokens);
            List<Map<String, Object>> rows = toRows(response);
            List<String> columns = rows.isEmpty() ? List.of("value") : new ArrayList<>(rows.get(0).keySet());
            return QueryExecutionResult.builder()
                .type(DatasourceType.REDIS)
                .executedQuery(String.join(" ", tokens))
                .queryLanguage("Redis Command")
                .summary("Redis 返回 %d 条记录".formatted(rows.size()))
                .columns(columns)
                .rows(rows)
                .total(rows.size())
                .elapsedMs(System.currentTimeMillis() - start)
                .rawResponse(response)
                .build();
        }
    }

    private Object executeCommand(Jedis jedis, List<String> tokens) {
        String command = tokens.get(0).toUpperCase();
        return switch (command) {
            case "GET" -> jedis.get(required(tokens, 1));
            case "MGET" -> jedis.mget(tokens.subList(1, tokens.size()).toArray(String[]::new));
            case "HGET" -> jedis.hget(required(tokens, 1), required(tokens, 2));
            case "HGETALL" -> jedis.hgetAll(required(tokens, 1));
            case "HKEYS" -> jedis.hkeys(required(tokens, 1));
            case "LRANGE" -> jedis.lrange(required(tokens, 1), parseLong(required(tokens, 2)), parseLong(required(tokens, 3)));
            case "LLEN" -> jedis.llen(required(tokens, 1));
            case "SMEMBERS" -> jedis.smembers(required(tokens, 1));
            case "SCARD" -> jedis.scard(required(tokens, 1));
            case "ZRANGE" -> jedis.zrange(required(tokens, 1), parseLong(required(tokens, 2)), parseLong(required(tokens, 3)));
            case "ZREVRANGE" -> jedis.zrevrange(required(tokens, 1), parseLong(required(tokens, 2)), parseLong(required(tokens, 3)));
            case "ZRANGEBYSCORE" -> jedis.zrangeByScore(required(tokens, 1), required(tokens, 2), required(tokens, 3));
            case "ZCARD" -> jedis.zcard(required(tokens, 1));
            case "TYPE" -> jedis.type(required(tokens, 1));
            case "TTL" -> jedis.ttl(required(tokens, 1));
            case "PTTL" -> jedis.pttl(required(tokens, 1));
            case "EXISTS" -> jedis.exists(required(tokens, 1));
            case "KEYS" -> jedis.keys(required(tokens, 1));
            case "SCAN" -> jedis.scan(tokens.size() > 1 ? tokens.get(1) : "0").getResult();
            case "STRLEN" -> jedis.strlen(required(tokens, 1));
            case "LINDEX" -> jedis.lindex(required(tokens, 1), parseLong(required(tokens, 2)));
            case "ZSCORE" -> jedis.zscore(required(tokens, 1), required(tokens, 2));
            case "SISMEMBER" -> jedis.sismember(required(tokens, 1), required(tokens, 2));
            default -> throw new BadRequestException("暂不支持该 Redis 命令: " + command);
        };
    }

    private List<Map<String, Object>> toRows(Object response) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (response instanceof Map<?, ?> map) {
            map.forEach((key, value) -> rows.add(Map.of("key", key, "value", value)));
            return rows;
        }
        if (response instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object item : iterable) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("index", index++);
                row.put("value", item);
                rows.add(row);
            }
            return rows;
        }
        rows.add(Map.of("value", response));
        return rows;
    }

    private JedisPool createPool(ConnectionProfile profile) {
        String password = StrUtil.isBlank(profile.getPassword()) ? null : profile.getPassword();
        return new JedisPool(new JedisPoolConfig(), profile.getHost(), profile.getPort(), 5000, password);
    }

    private String required(List<String> tokens, int index) {
        if (tokens.size() <= index) {
            throw new BadRequestException("Redis 命令参数不足");
        }
        return tokens.get(index);
    }

    private long parseLong(String value) {
        return Long.parseLong(value);
    }
}
