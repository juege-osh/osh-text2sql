package com.osh.text2sql.util;

import cn.hutool.core.util.StrUtil;
import com.osh.text2sql.exception.BadRequestException;

import java.util.Locale;
import java.util.Set;

public final class SqlSafetyValidator {

    private static final Set<String> BLOCKED_TOKENS = Set.of(
        " insert ",
        " update ",
        " delete ",
        " drop ",
        " truncate ",
        " alter ",
        " create ",
        " replace ",
        " grant ",
        " revoke ",
        " rename ",
        " call ",
        " into outfile ",
        " load data "
    );

    private SqlSafetyValidator() {
    }

    public static String validateSelectQuery(String sql, int queryLimit) {
        if (StrUtil.isBlank(sql)) {
            throw new BadRequestException("SQL 不能为空");
        }
        String sanitized = sql.trim();
        if (sanitized.endsWith(";")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1).trim();
        }
        String normalized = " " + sanitized.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT) + " ";
        if (!normalized.startsWith(" select ") && !normalized.startsWith(" with ")) {
            throw new BadRequestException("只允许执行 SELECT 或 WITH 查询");
        }
        if (normalized.contains(";")) {
            throw new BadRequestException("不允许一次执行多条 SQL");
        }
        for (String token : BLOCKED_TOKENS) {
            if (normalized.contains(token)) {
                throw new BadRequestException("SQL 包含高风险指令: " + token.trim());
            }
        }
        if (!normalized.contains(" limit ")) {
            return sanitized + " LIMIT " + queryLimit;
        }
        return sanitized;
    }
}
