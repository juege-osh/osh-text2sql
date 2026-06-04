package com.osh.text2sql.service;

import com.osh.text2sql.config.Text2SqlProperties;
import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.ConnectionTestResponse;
import com.osh.text2sql.dto.DatasourceOverview;
import com.osh.text2sql.dto.DatasourceSchemaResponse;
import com.osh.text2sql.dto.DatasourceType;
import com.osh.text2sql.dto.GeneratedQuery;
import com.osh.text2sql.dto.MysqlTableListItem;
import com.osh.text2sql.dto.MysqlTableListResponse;
import com.osh.text2sql.dto.MysqlTableOperationRequest;
import com.osh.text2sql.dto.MysqlTableSchemaResponse;
import com.osh.text2sql.dto.QuerySuggestion;
import com.osh.text2sql.dto.QueryExecutionResult;
import com.osh.text2sql.dto.QueryMode;
import com.osh.text2sql.dto.QueryRequest;
import com.osh.text2sql.dto.QueryResponse;
import com.osh.text2sql.dto.WorkspaceSnapshotResponse;
import com.osh.text2sql.exception.BadRequestException;
import com.osh.text2sql.executor.ElasticsearchQueryExecutor;
import com.osh.text2sql.executor.HbaseQueryExecutor;
import com.osh.text2sql.executor.KafkaQueryExecutor;
import com.osh.text2sql.executor.MysqlQueryExecutor;
import com.osh.text2sql.executor.QueryExecutor;
import com.osh.text2sql.executor.RedisQueryExecutor;
import com.osh.text2sql.introspect.DatasourceIntrospector;
import com.osh.text2sql.introspect.ElasticsearchIntrospector;
import com.osh.text2sql.introspect.HbaseIntrospector;
import com.osh.text2sql.introspect.KafkaIntrospector;
import com.osh.text2sql.introspect.MysqlIntrospector;
import com.osh.text2sql.introspect.MysqlSchemaCachePayload;
import com.osh.text2sql.introspect.MysqlSchemaCacheService;
import com.osh.text2sql.introspect.RedisIntrospector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class Text2QueryService {

    private static final Logger log = LoggerFactory.getLogger(Text2QueryService.class);

    private final ConnectionProfileResolver profileResolver;
    private final PromptService promptService;
    private final Text2SqlProperties properties;
    private final MysqlIntrospector mysqlIntrospector;
    private final MysqlSchemaCacheService mysqlSchemaCacheService;
    private final RedisIntrospector redisIntrospector;
    private final ElasticsearchIntrospector elasticsearchIntrospector;
    private final KafkaIntrospector kafkaIntrospector;
    private final HbaseIntrospector hbaseIntrospector;
    private final MysqlQueryExecutor mysqlQueryExecutor;
    private final RedisQueryExecutor redisQueryExecutor;
    private final ElasticsearchQueryExecutor elasticsearchQueryExecutor;
    private final KafkaQueryExecutor kafkaQueryExecutor;
    private final HbaseQueryExecutor hbaseQueryExecutor;

    public Text2QueryService(ConnectionProfileResolver profileResolver,
                             PromptService promptService,
                             Text2SqlProperties properties,
                             MysqlIntrospector mysqlIntrospector,
                             MysqlSchemaCacheService mysqlSchemaCacheService,
                             RedisIntrospector redisIntrospector,
                             ElasticsearchIntrospector elasticsearchIntrospector,
                             KafkaIntrospector kafkaIntrospector,
                             HbaseIntrospector hbaseIntrospector,
                             MysqlQueryExecutor mysqlQueryExecutor,
                             RedisQueryExecutor redisQueryExecutor,
                             ElasticsearchQueryExecutor elasticsearchQueryExecutor,
                             KafkaQueryExecutor kafkaQueryExecutor,
                             HbaseQueryExecutor hbaseQueryExecutor) {
        this.profileResolver = profileResolver;
        this.promptService = promptService;
        this.properties = properties;
        this.mysqlIntrospector = mysqlIntrospector;
        this.mysqlSchemaCacheService = mysqlSchemaCacheService;
        this.redisIntrospector = redisIntrospector;
        this.elasticsearchIntrospector = elasticsearchIntrospector;
        this.kafkaIntrospector = kafkaIntrospector;
        this.hbaseIntrospector = hbaseIntrospector;
        this.mysqlQueryExecutor = mysqlQueryExecutor;
        this.redisQueryExecutor = redisQueryExecutor;
        this.elasticsearchQueryExecutor = elasticsearchQueryExecutor;
        this.kafkaQueryExecutor = kafkaQueryExecutor;
        this.hbaseQueryExecutor = hbaseQueryExecutor;
    }

    public QueryResponse query(QueryRequest request) {
        ConnectionProfile profile = profileResolver.resolve(request.getConnection(), request.getType());
        DatasourceSchemaResponse schema = schemaForQuestion(request.getType(), profile, request.getQuestion());
        GeneratedQuery generatedQuery = request.getMode() == QueryMode.RAW
            ? GeneratedQuery.builder()
                .type(request.getType())
                .query(request.getRawQuery())
                .reasoning("手动模式，直接执行输入查询")
                .safetyNotes("仍会经过服务端只读安全校验")
                .build()
            : promptService.generateQuery(request.getType(), request.getQuestion(), schema);
        if (generatedQuery.getQuery() == null || generatedQuery.getQuery().isBlank()) {
            throw new BadRequestException("未生成有效查询");
        }
        QueryExecutionResult result = executor(request.getType()).execute(profile, generatedQuery.getQuery());
        String answer = result.getSummary();
        if (properties.getAi().isExplainResult()) {
            try {
                answer = promptService.explainResult(request.getQuestion(), result);
            } catch (Exception exception) {
                log.warn("AI 结果总结失败，改用查询摘要兜底: {}", exception.getMessage());
            }
        }
        return QueryResponse.builder()
            .schema(schema)
            .generatedQuery(generatedQuery)
            .result(result)
            .answer(answer)
            .build();
    }

    public DatasourceSchemaResponse schema(DatasourceType type, ConnectionProfile connection) {
        return introspector(type).introspect(profileResolver.resolve(connection, type));
    }

    public ConnectionTestResponse test(DatasourceType type, ConnectionProfile connection) {
        return introspector(type).test(profileResolver.resolve(connection, type));
    }

    public WorkspaceSnapshotResponse snapshot() {
        Text2SqlProperties.MysqlProperties mysql = properties.getDatasources().getMysql();
        Text2SqlProperties.RedisProperties redis = properties.getDatasources().getRedis();
        Text2SqlProperties.ElasticsearchProperties elasticsearch = properties.getDatasources().getElasticsearch();
        Text2SqlProperties.KafkaProperties kafka = properties.getDatasources().getKafka();
        Text2SqlProperties.HbaseProperties hbase = properties.getDatasources().getHbase();
        return WorkspaceSnapshotResponse.builder()
            .datasources(java.util.List.of(
                DatasourceOverview.builder()
                    .type(DatasourceType.MYSQL)
                    .title("MySQL")
                    .subtitle("%s:%d / %s".formatted(mysql.getHost(), mysql.getPort(), mysql.getDatabase()))
                    .status("Ready")
                    .sampleTarget("assistant_feedback, osh_course, sys_user")
                    .build(),
                DatasourceOverview.builder()
                    .type(DatasourceType.REDIS)
                    .title("Redis")
                    .subtitle("%s:%d / db%s".formatted(redis.getHost(), redis.getPort(), redis.getDatabase()))
                    .status("Ready")
                    .sampleTarget("当前为空库，可直接测试只读命令")
                    .build(),
                DatasourceOverview.builder()
                    .type(DatasourceType.ELASTICSEARCH)
                    .title("Elasticsearch")
                    .subtitle(elasticsearch.getBaseUrl())
                    .status("Ready")
                    .sampleTarget("osh_course_index, osh_tool_search, osh_book_search_read")
                    .build(),
                DatasourceOverview.builder()
                    .type(DatasourceType.KAFKA)
                    .title("Kafka")
                    .subtitle(kafka.getBootstrapServers())
                    .status("Ready")
                    .sampleTarget("user-action, osh.tool.index, seckill.order.create")
                    .build(),
                DatasourceOverview.builder()
                    .type(DatasourceType.HBASE)
                    .title("HBase")
                    .subtitle("%s:%d / %s".formatted(hbase.getZookeeperQuorum(), hbase.getZookeeperClientPort(), hbase.getNamespace()))
                    .status("Ready")
                    .sampleTarget("default:demo, default:user_profile")
                    .build()
            ))
            .suggestions(java.util.List.of(
                QuerySuggestion.builder()
                    .type(DatasourceType.MYSQL)
                    .title("反馈工单")
                    .prompt("查询 assistant_feedback 表最近创建的 5 条反馈工单")
                    .build(),
                QuerySuggestion.builder()
                    .type(DatasourceType.MYSQL)
                    .title("论坛帖子")
                    .prompt("统计 osh_bbs_post 表总共有多少条帖子")
                    .build(),
                QuerySuggestion.builder()
                    .type(DatasourceType.MYSQL)
                    .title("可用工具数量")
                    .prompt("查询可用工具的数量")
                    .build(),
                QuerySuggestion.builder()
                    .type(DatasourceType.MYSQL)
                    .title("最近上架课程")
                    .prompt("查询 osh_course 表最近上架的 10 个课程")
                    .build(),
                QuerySuggestion.builder()
                    .type(DatasourceType.ELASTICSEARCH)
                    .title("课程销量排行")
                    .prompt("查询 osh_course_index 中销量最高的 5 个课程")
                    .build(),
                QuerySuggestion.builder()
                    .type(DatasourceType.ELASTICSEARCH)
                    .title("工具标题搜索")
                    .prompt("查询 osh_tool_search 中标题包含 AI 的工具")
                    .build(),
                QuerySuggestion.builder()
                    .type(DatasourceType.REDIS)
                    .title("查看 key")
                    .prompt("列出当前 Redis 数据库前 20 个 key")
                    .build(),
                QuerySuggestion.builder()
                    .type(DatasourceType.REDIS)
                    .title("查看指定 key")
                    .prompt("查看 key user:1001:profile 的类型和 TTL")
                    .build(),
                QuerySuggestion.builder()
                    .type(DatasourceType.KAFKA)
                    .title("查看主题")
                    .prompt("列出当前 Kafka 集群的 topic 列表")
                    .build(),
                QuerySuggestion.builder()
                    .type(DatasourceType.KAFKA)
                    .title("查看最近消息")
                    .prompt("查看 user-action topic 最近 10 条消息")
                    .build(),
                QuerySuggestion.builder()
                    .type(DatasourceType.KAFKA)
                    .title("按 key 查消费情况")
                    .prompt("查询 topic osh-kafka-key-status-test 对 consumer group osh-kafka-key-status-group 中 key 为 tool-1001 的消息消费情况")
                    .build(),
                QuerySuggestion.builder()
                    .type(DatasourceType.HBASE)
                    .title("查看表列表")
                    .prompt("列出当前 HBase 命名空间下的表")
                    .build(),
                QuerySuggestion.builder()
                    .type(DatasourceType.HBASE)
                    .title("按 rowKey 查询")
                    .prompt("查询 user_profile 表中 rowKey 为 user:1001 的数据")
                    .build(),
                QuerySuggestion.builder()
                    .type(DatasourceType.HBASE)
                    .title("查看表前 10 行")
                    .prompt("查看 user_profile 表前 10 行数据")
                    .build()
            ))
            .build();
    }

    public MysqlTableListResponse mysqlTables(ConnectionProfile connection) {
        ConnectionProfile profile = profileResolver.resolve(connection, DatasourceType.MYSQL);
        java.util.List<MysqlSchemaCachePayload.TableMeta> tables = mysqlSchemaCacheService.getTableList(profile)
            .orElseGet(() -> {
                DatasourceSchemaResponse refreshed = mysqlIntrospector.refresh(profile, null);
                log.info("MySQL 表列表缓存未命中，已自动回源 MySQL 并刷新 Redis：database={}, schemaSummary={}",
                    profile.getDatabase(), refreshed.getSummary());
                return mysqlSchemaCacheService.getTableList(profile).orElseGet(() -> mysqlIntrospector.loadTableList(profile));
            });
        return MysqlTableListResponse.builder()
            .database(profile.getDatabase())
            .total(tables.size())
            .tables(tables.stream().map(table -> MysqlTableListItem.builder()
                .tableName(table.tableName())
                .tableComment(table.tableComment())
                .build()).toList())
            .build();
    }

    public MysqlTableListResponse refreshMysqlTables(ConnectionProfile connection) {
        ConnectionProfile profile = profileResolver.resolve(connection, DatasourceType.MYSQL);
        mysqlIntrospector.refresh(profile, null);
        return mysqlTables(connection);
    }

    public MysqlTableSchemaResponse mysqlTableSchema(MysqlTableOperationRequest request) {
        ConnectionProfile profile = profileResolver.resolve(request.getConnection(), DatasourceType.MYSQL);
        return mysqlIntrospector.loadTableSchemaLive(profile, request.getTableName());
    }

    public QueryExecutionResult mysqlTablePreview(MysqlTableOperationRequest request) {
        ConnectionProfile profile = profileResolver.resolve(request.getConnection(), DatasourceType.MYSQL);
        String sql = "SELECT * FROM %s LIMIT 20".formatted(request.getTableName());
        return mysqlQueryExecutor.execute(profile, sql);
    }

    private DatasourceIntrospector introspector(DatasourceType type) {
        return switch (type) {
            case MYSQL -> mysqlIntrospector;
            case REDIS -> redisIntrospector;
            case ELASTICSEARCH -> elasticsearchIntrospector;
            case KAFKA -> kafkaIntrospector;
            case HBASE -> hbaseIntrospector;
        };
    }

    private DatasourceSchemaResponse schemaForQuestion(DatasourceType type, ConnectionProfile profile, String question) {
        if (type == DatasourceType.MYSQL) {
            return mysqlIntrospector.introspect(profile, question);
        }
        return introspector(type).introspect(profile);
    }

    private QueryExecutor executor(DatasourceType type) {
        return switch (type) {
            case MYSQL -> mysqlQueryExecutor;
            case REDIS -> redisQueryExecutor;
            case ELASTICSEARCH -> elasticsearchQueryExecutor;
            case KAFKA -> kafkaQueryExecutor;
            case HBASE -> hbaseQueryExecutor;
        };
    }
}
