package com.osh.text2sql.service;

import com.osh.text2sql.config.Text2SqlProperties;
import com.osh.text2sql.dto.KnowledgeImportResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Redis 知识导入服务
 */
@Service
public class RedisKnowledgeImportService {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Text2SqlProperties properties;
    private final QaKnowledgeImportService qaKnowledgeImportService;

    public RedisKnowledgeImportService(Text2SqlProperties properties,
                                       QaKnowledgeImportService qaKnowledgeImportService) {
        this.properties = properties;
        this.qaKnowledgeImportService = qaKnowledgeImportService;
    }

    public KnowledgeImportResponse importBackendRedisKnowledge() {
        Text2SqlProperties.QaKnowledgeImportProperties qaConfig = properties.getQaKnowledgeImport();
        String markdown = buildBackendRedisKnowledgeMarkdown();
        String dateSuffix = LocalDate.now().format(DATE_FORMATTER);
        return qaKnowledgeImportService.importMarkdownToKnowledgeLib(
            qaConfig.getBaseUrl(),
            qaConfig.getUsername(),
            qaConfig.getPassword(),
            qaConfig.getLibId(),
            "redis-knowledge",
            "backend-redis-knowledge-" + dateSuffix + ".md",
            markdown
        );
    }

    private String buildBackendRedisKnowledgeMarkdown() {
        Text2SqlProperties.MysqlProperties mysql = properties.getDatasources().getMysql();
        Text2SqlProperties.SchemaCacheProperties schemaCache = mysql.getSchemaCache();
        String baseKeyExample = "%s:%s:%d:%s:v2".formatted(
            schemaCache.getKeyPrefix(),
            mysql.getHost(),
            mysql.getPort(),
            mysql.getDatabase()
        );
        String metaKey = baseKeyExample + ":meta";
        String tablesKey = baseKeyExample + ":tables";
        String columnsKey = baseKeyExample + ":table:assistant_feedback:columns";
        String indexesKey = baseKeyExample + ":table:assistant_feedback:indexes";

        return """
            # osh-text2sql backend Redis 设计说明

            ## 总览

            当前 `backend` 项目自身并没有大量业务缓存 key，Redis 主要用于 **MySQL 表结构缓存**，目的是减少每次自然语言问数时对 `information_schema` 的重复扫描，提高结构分析速度。

            ## Redis 连接角色

            - 用途：缓存 MySQL 库表结构
            - 典型使用位置：`MysqlSchemaCacheService`
            - 当前 key 前缀：`%s`
            - 当前 TTL：`%d` 分钟

            ## Key 设计

            这套缓存 key 的基础格式为：

            ```text
            <keyPrefix>:<mysqlHost>:<mysqlPort>:<database>:v2
            ```

            按这个基础 key 再拆成 4 类：

            1. Meta 信息
            ```text
            %s
            ```
            作用：
            - 保存当前缓存快照的摘要信息
            - 主要包含数据库名称、结构摘要说明

            2. 表清单
            ```text
            %s
            ```
            作用：
            - 保存当前数据库下参与缓存的表列表
            - 每项通常包含 `tableName` 和 `tableComment`

            3. 单表字段缓存
            ```text
            %s
            ```
            作用：
            - 保存某张表的字段结构
            - 内容通常包含字段名、字段类型、字段注释

            4. 单表索引缓存
            ```text
            %s
            ```
            作用：
            - 保存某张表的索引信息
            - 内容通常包含索引名、字段名、唯一性、索引类型等

            ## 数据内容说明

            这组 key 的 value 都是 **JSON 字符串**，不是 Redis Hash。

            - `:meta`
              - 结构摘要
              - 便于快速判断缓存是否可用

            - `:tables`
              - 表列表数组
              - 便于后续批量拼接 `columns/indexes` key

            - `:table:<tableName>:columns`
              - 某张表的字段数组
              - 供 MySQL 结构摘要和自然语言生成 SQL 使用

            - `:table:<tableName>:indexes`
              - 某张表的索引数组
              - 用于提示模型优先使用索引字段作为过滤条件

            ## 写入时机

            主要写入入口：

            - `MysqlSchemaCacheService.putSchema(...)`
              - 全量刷新 MySQL 结构缓存时写入

            - `MysqlSchemaCacheService.putTableList(...)`
              - 更新表清单时写入

            ## 读取时机

            主要读取入口：

            - `getSchema(...)`
              - 读取完整缓存，包括表清单、字段、索引

            - `getSchemaColumnsOnly(...)`
              - 只读取列摘要，加快按问题做候选表筛选

            - `attachIndexes(...)`
              - 先只拿列，再按需补索引

            ## 过期与失效策略

            - TTL 默认 `%d` 分钟
            - 当缓存未命中或字段缺失时，系统会回退到直接读取 MySQL `information_schema`
            - 当刷新结构缓存时，会覆盖旧值
            - 当清理缓存时，会删除 `meta/tables/columns/indexes` 全套 key

            ## 业务价值

            这组 Redis key 的核心价值不是承载业务数据，而是：

            - 降低 MySQL 元数据扫描频率
            - 提高自然语言问数时的结构分析速度
            - 让模型能快速拿到字段与索引摘要
            - 在多次问数场景下复用同一份结构缓存

            ## 查询建议

            如果要在 Redis 中人工排查这组缓存，优先看：

            - `%s`
            - `%s`
            - `%s`
            - `%s`

            ## 额外说明

            当前 `backend` 工程里的 Redis 相关能力还包括：

            - `RedisIntrospector`
              - 用于浏览 Redis key、类型和 TTL

            - `RedisQueryExecutor`
              - 支持只读 Redis 命令执行，例如 `GET`、`HGETALL`、`LRANGE`、`SMEMBERS`、`ZRANGE`、`TTL`、`SCAN`

            ## Redis 查询能力

            当前后端已经内置 Redis 只读查询执行能力，主要服务于自然语言问数和人工排查场景。

            ### 当前支持的只读命令

            - `GET`
            - `MGET`
            - `HGET`
            - `HGETALL`
            - `HKEYS`
            - `HLEN`
            - `LRANGE`
            - `LLEN`
            - `SMEMBERS`
            - `SCARD`
            - `SISMEMBER`
            - `ZRANGE`
            - `ZREVRANGE`
            - `ZRANGEBYSCORE`
            - `ZCARD`
            - `ZRANK`
            - `ZSCORE`
            - `TYPE`
            - `TTL`
            - `PTTL`
            - `EXISTS`
            - `SCAN`
            - `STRLEN`
            - `LINDEX`

            ### 查询限制

            为了保证安全，系统只允许执行 **只读 Redis 命令**。

            明确不允许：

            - `SET`
            - `DEL`
            - `HSET`
            - `LPUSH`
            - `RPUSH`
            - `SADD`
            - `ZADD`
            - `EXPIRE`
            - 以及其他任何写命令

            另外：

            - `SCAN` 如果没有传 cursor，系统会自动补成 `SCAN 0`
            - 该后端目前不会执行 Redis 写操作

            ### 适合导入知识库的提问范式

            可以引导模型使用下面这类问法：

            - 列出当前 Redis 数据库前 20 个 key
            - 查看 key `osh:text2sql:mysql:schema:*` 的类型和 TTL
            - 查看某个 schema 缓存 key 的值
            - 查看某张表缓存的字段定义
            - 查看某张表缓存的索引定义
            - 查看 Redis 中某个 key 是否存在
            - 查看某个 zset 的前 10 个成员

            ### Redis 问答建议

            当知识库用于指导模型时，建议强调：

            - 先判断 key 属于哪一类缓存
            - 先看 `TYPE` 和 `TTL`
            - 如果是 schema 缓存，优先识别 `meta/tables/columns/indexes`
            - 如果要全库浏览，优先使用 `SCAN`，不要建议 `KEYS *`

            但从“系统自身 Redis key 设计”角度看，最核心、最明确的一套规范仍然是上面的 **MySQL 结构缓存 key 设计**。
            """.formatted(
            schemaCache.getKeyPrefix(),
            schemaCache.getTtlMinutes(),
            metaKey,
            tablesKey,
            columnsKey,
            indexesKey,
            schemaCache.getTtlMinutes(),
            metaKey,
            tablesKey,
            columnsKey,
            indexesKey
        );
    }
}
