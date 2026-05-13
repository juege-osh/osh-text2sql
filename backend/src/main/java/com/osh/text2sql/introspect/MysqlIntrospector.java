package com.osh.text2sql.introspect;

import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.ConnectionTestResponse;
import com.osh.text2sql.dto.DatasourceSchemaResponse;
import com.osh.text2sql.dto.DatasourceType;
import org.springframework.lang.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class MysqlIntrospector implements DatasourceIntrospector {

    private static final int MAX_TABLES = 30;
    private static final int MAX_COLUMNS = 20;

    @Override
    public DatasourceSchemaResponse introspect(ConnectionProfile profile) {
        return introspect(profile, null);
    }

    public DatasourceSchemaResponse introspect(ConnectionProfile profile, @Nullable String question) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(createDataSource(profile));
        List<Map<String, Object>> tables = jdbcTemplate.queryForList("""
            SELECT table_name AS tableName, table_comment AS tableComment
            FROM information_schema.tables
            WHERE table_schema = ?
            ORDER BY table_name
            """, profile.getDatabase());
        List<Map<String, Object>> rankedTables = rankTables(tables, question);

        Map<String, Object> schema = new LinkedHashMap<>();
        for (Map<String, Object> table : rankedTables) {
            String tableName = String.valueOf(table.get("tableName"));
            List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name AS columnName, data_type AS dataType, column_comment AS columnComment
                FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ?
                ORDER BY ordinal_position
                LIMIT ?
                """, profile.getDatabase(), tableName, MAX_COLUMNS);
            schema.put(tableName, columns);
        }

        return DatasourceSchemaResponse.builder()
            .type(DatasourceType.MYSQL)
            .name(profile.getDatabase())
            .summary(buildSummary(profile.getDatabase(), question, rankedTables.size()))
            .schema(schema)
            .build();
    }

    @Override
    public ConnectionTestResponse test(ConnectionProfile profile) {
        long start = System.currentTimeMillis();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(createDataSource(profile));
        List<Map<String, Object>> preview = jdbcTemplate.queryForList("SELECT DATABASE() AS currentDatabase, NOW() AS currentTime");
        return ConnectionTestResponse.builder()
            .success(true)
            .message("MySQL 连接成功")
            .elapsedMs(Duration.ofMillis(System.currentTimeMillis() - start).toMillis())
            .preview(preview)
            .build();
    }

    private DataSource createDataSource(ConnectionProfile profile) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl("jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai"
            .formatted(profile.getHost(), profile.getPort(), profile.getDatabase()));
        dataSource.setUsername(profile.getUsername());
        dataSource.setPassword(profile.getPassword());
        return dataSource;
    }

    private List<Map<String, Object>> rankTables(List<Map<String, Object>> tables, @Nullable String question) {
        if (tables.size() <= MAX_TABLES) {
            return tables;
        }
        if (question == null || question.isBlank()) {
            return tables.stream().limit(MAX_TABLES).toList();
        }

        Set<String> keywords = extractKeywords(question);
        List<Map<String, Object>> scored = new ArrayList<>(tables);
        scored.sort(Comparator
            .comparingInt((Map<String, Object> table) -> scoreTable(table, keywords)).reversed()
            .thenComparing(table -> String.valueOf(table.get("tableName"))));

        List<Map<String, Object>> top = scored.stream()
            .limit(MAX_TABLES)
            .collect(Collectors.toCollection(ArrayList::new));

        boolean hasUserLikeTable = top.stream().anyMatch(table -> isUserLikeTable(String.valueOf(table.get("tableName"))));
        if (!hasUserLikeTable && keywords.stream().anyMatch(this::isUserKeyword)) {
            scored.stream()
                .filter(table -> isUserLikeTable(String.valueOf(table.get("tableName"))))
                .findFirst()
                .ifPresent(candidate -> {
                    if (!top.contains(candidate)) {
                        top.set(top.size() - 1, candidate);
                    }
                });
        }
        return top;
    }

    private int scoreTable(Map<String, Object> table, Set<String> keywords) {
        String tableName = String.valueOf(table.get("tableName")).toLowerCase(Locale.ROOT);
        String tableComment = String.valueOf(table.getOrDefault("tableComment", "")).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String keyword : keywords) {
            if (tableName.contains(keyword)) {
                score += 8;
            }
            if (tableComment.contains(keyword)) {
                score += 6;
            }
            if (isUserKeyword(keyword) && isUserLikeTable(tableName)) {
                score += 40;
            }
        }
        if (isUserLikeTable(tableName)) {
            score += 2;
        }
        return score;
    }

    private Set<String> extractKeywords(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        Set<String> keywords = new java.util.LinkedHashSet<>();
        if (lower.contains("user")) keywords.add("user");
        if (question.contains("用户")) keywords.add("用户");
        if (lower.contains("comment")) keywords.add("comment");
        if (question.contains("评论")) keywords.add("评论");
        if (lower.contains("feedback")) keywords.add("feedback");
        if (question.contains("反馈")) keywords.add("反馈");
        if (lower.contains("post")) keywords.add("post");
        if (question.contains("帖子")) keywords.add("帖子");
        if (lower.contains("course")) keywords.add("course");
        if (question.contains("课程")) keywords.add("课程");
        if (lower.contains("book")) keywords.add("book");
        if (question.contains("电子书")) keywords.add("电子书");
        return keywords;
    }

    private boolean isUserLikeTable(String tableName) {
        String lower = tableName.toLowerCase(Locale.ROOT);
        return lower.equals("sys_user")
            || lower.equals("user")
            || lower.equals("users")
            || lower.endsWith("_user")
            || lower.contains("user");
    }

    private boolean isUserKeyword(String keyword) {
        return "user".equals(keyword) || "用户".equals(keyword);
    }

    private String buildSummary(String database, @Nullable String question, int selectedCount) {
        if (question == null || question.isBlank()) {
            return "MySQL 库 %s，展示前 %d 张表及字段摘要".formatted(database, selectedCount);
        }
        return "MySQL 库 %s，按当前问题筛选出最相关的 %d 张表及字段摘要".formatted(database, selectedCount);
    }
}
