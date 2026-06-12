package com.osh.text2sql.service;

import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.DatasourceSchemaResponse;
import com.osh.text2sql.dto.DatasourceType;
import com.osh.text2sql.exception.BadRequestException;
import com.osh.text2sql.introspect.MysqlSchemaCachePayload;
import com.osh.text2sql.introspect.MysqlSchemaCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * MySQL Redis 结构精准读取服务
 */
@Service
public class MysqlRedisSchemaLookupService {

    private static final Logger log = LoggerFactory.getLogger(MysqlRedisSchemaLookupService.class);

    private final MysqlSchemaCacheService mysqlSchemaCacheService;

    public MysqlRedisSchemaLookupService(MysqlSchemaCacheService mysqlSchemaCacheService) {
        this.mysqlSchemaCacheService = mysqlSchemaCacheService;
    }

    public DatasourceSchemaResponse loadSchemaByTables(ConnectionProfile profile,
                                                      List<String> tableNames,
                                                      String summaryReason) {
        if (tableNames == null || tableNames.isEmpty()) {
            throw new BadRequestException("没有可用于读取 Redis 结构缓存的表名");
        }
        Optional<MysqlSchemaCachePayload> cachePayload = mysqlSchemaCacheService.getSchema(profile);
        if (cachePayload.isEmpty()) {
            throw new BadRequestException("MySQL Redis 结构缓存未命中，无法按表名精准读取结构");
        }
        MysqlSchemaCachePayload payload = cachePayload.get();
        Map<String, Object> schema = new LinkedHashMap<>();
        for (String tableName : tableNames) {
            Object tableSchema = payload.schema().get(tableName);
            if (tableSchema != null) {
                schema.put(tableName, tableSchema);
            }
        }
        if (schema.isEmpty()) {
            throw new BadRequestException("Redis 结构缓存中未找到 QA assistant 返回的候选表");
        }
        log.info("MySQL Redis 精准结构读取完成：database={}, selectedTables={}", profile.getDatabase(), schema.keySet());
        return DatasourceSchemaResponse.builder()
            .type(DatasourceType.MYSQL)
            .name(profile.getDatabase())
            .summary(buildSummary(schema.size(), summaryReason))
            .schema(schema)
            .build();
    }

    private String buildSummary(int tableCount, String summaryReason) {
        String reason = Objects.toString(summaryReason, "").trim();
        if (reason.isBlank()) {
            return "根据 QA assistant 返回结果，从 Redis 结构缓存中命中 %d 张候选表".formatted(tableCount);
        }
        return "根据 QA assistant 返回结果，从 Redis 结构缓存中命中 %d 张候选表。原因：%s".formatted(tableCount, reason);
    }
}
