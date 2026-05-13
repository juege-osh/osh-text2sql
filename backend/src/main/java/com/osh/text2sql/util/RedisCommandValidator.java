package com.osh.text2sql.util;

import cn.hutool.core.util.StrUtil;
import com.osh.text2sql.exception.BadRequestException;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class RedisCommandValidator {

    private static final Set<String> READ_ONLY_COMMANDS = Set.of(
        "GET", "MGET", "HGET", "HGETALL", "HKEYS", "HLEN",
        "LRANGE", "LLEN", "SCARD", "SMEMBERS", "SISMEMBER",
        "ZRANGE", "ZREVRANGE", "ZRANGEBYSCORE", "ZCARD",
        "TYPE", "TTL", "PTTL", "EXISTS", "SCAN",
        "STRLEN", "LINDEX", "ZRANK", "ZSCORE"
    );

    private RedisCommandValidator() {
    }

    public static List<String> validate(String command) {
        if (StrUtil.isBlank(command)) {
            throw new BadRequestException("Redis 查询不能为空");
        }
        List<String> tokens = List.of(command.trim().split("\\s+"));
        String cmd = tokens.get(0).toUpperCase(Locale.ROOT);
        if (!READ_ONLY_COMMANDS.contains(cmd)) {
            throw new BadRequestException("只允许执行只读 Redis 命令");
        }
        if ("SCAN".equals(cmd) && tokens.size() == 1) {
            return List.of("SCAN", "0");
        }
        return tokens;
    }
}
