package com.osh.text2sql.service;

import cn.hutool.core.util.StrUtil;
import com.osh.text2sql.config.Text2SqlProperties;
import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.DatasourceType;
import org.springframework.stereotype.Component;

@Component
public class ConnectionProfileResolver {

    private final Text2SqlProperties properties;

    public ConnectionProfileResolver(Text2SqlProperties properties) {
        this.properties = properties;
    }

    public ConnectionProfile resolve(ConnectionProfile incoming, DatasourceType type) {
        ConnectionProfile profile = new ConnectionProfile();
        profile.setType(type);
        switch (type) {
            case MYSQL -> applyMysql(profile, incoming);
            case REDIS -> applyRedis(profile, incoming);
            case ELASTICSEARCH -> applyElasticsearch(profile, incoming);
            default -> throw new IllegalArgumentException("Unsupported type: " + type);
        }
        return profile;
    }

    private void applyMysql(ConnectionProfile target, ConnectionProfile incoming) {
        Text2SqlProperties.MysqlProperties mysql = properties.getDatasources().getMysql();
        target.setHost(resolveText(incoming == null ? null : incoming.getHost(), mysql.getHost()));
        target.setPort(resolveInt(incoming == null ? null : incoming.getPort(), mysql.getPort()));
        target.setDatabase(resolveText(incoming == null ? null : incoming.getDatabase(), mysql.getDatabase()));
        target.setUsername(resolveText(incoming == null ? null : incoming.getUsername(), mysql.getUsername()));
        target.setPassword(resolveText(incoming == null ? null : incoming.getPassword(), mysql.getPassword()));
    }

    private void applyRedis(ConnectionProfile target, ConnectionProfile incoming) {
        Text2SqlProperties.RedisProperties redis = properties.getDatasources().getRedis();
        target.setHost(resolveText(incoming == null ? null : incoming.getHost(), redis.getHost()));
        target.setPort(resolveInt(incoming == null ? null : incoming.getPort(), redis.getPort()));
        target.setDatabase(String.valueOf(resolveInt(parseInt(incoming == null ? null : incoming.getDatabase()), redis.getDatabase())));
        target.setPassword(resolveText(incoming == null ? null : incoming.getPassword(), redis.getPassword()));
    }

    private void applyElasticsearch(ConnectionProfile target, ConnectionProfile incoming) {
        Text2SqlProperties.ElasticsearchProperties elasticsearch = properties.getDatasources().getElasticsearch();
        target.setBaseUrl(resolveText(incoming == null ? null : incoming.getBaseUrl(), elasticsearch.getBaseUrl()));
        target.setUsername(resolveText(incoming == null ? null : incoming.getUsername(), elasticsearch.getUsername()));
        target.setPassword(resolveText(incoming == null ? null : incoming.getPassword(), elasticsearch.getPassword()));
    }

    private String resolveText(String preferred, String fallback) {
        return StrUtil.blankToDefault(preferred, fallback);
    }

    private Integer resolveInt(Integer preferred, int fallback) {
        return preferred == null ? fallback : preferred;
    }

    private Integer parseInt(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        return Integer.parseInt(value);
    }
}
