package com.osh.text2sql.executor;

import com.osh.text2sql.config.Text2SqlProperties;
import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.DatasourceType;
import com.osh.text2sql.dto.QueryExecutionResult;
import com.osh.text2sql.util.SqlSafetyValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class MysqlQueryExecutor implements QueryExecutor {

    private final Text2SqlProperties properties;

    public MysqlQueryExecutor(Text2SqlProperties properties) {
        this.properties = properties;
    }

    @Override
    public QueryExecutionResult execute(ConnectionProfile profile, String query) {
        String safeSql = SqlSafetyValidator.validateSelectQuery(query, properties.getQueryLimit());
        JdbcTemplate jdbcTemplate = new JdbcTemplate(createDataSource(profile));
        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(safeSql);
        long elapsed = System.currentTimeMillis() - start;
        List<String> columns = rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet());
        return QueryExecutionResult.builder()
            .type(DatasourceType.MYSQL)
            .executedQuery(safeSql)
            .queryLanguage("SQL")
            .summary("返回 %d 行结果".formatted(rows.size()))
            .columns(columns)
            .rows(rows)
            .total(rows.size())
            .elapsedMs(elapsed)
            .rawResponse(rows)
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
}
