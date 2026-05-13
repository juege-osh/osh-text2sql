package com.osh.text2sql.service;

import com.osh.text2sql.config.Text2SqlProperties;
import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.ConnectionTestResponse;
import com.osh.text2sql.dto.DatasourceOverview;
import com.osh.text2sql.dto.DatasourceSchemaResponse;
import com.osh.text2sql.dto.DatasourceType;
import com.osh.text2sql.dto.GeneratedQuery;
import com.osh.text2sql.dto.QuerySuggestion;
import com.osh.text2sql.dto.QueryExecutionResult;
import com.osh.text2sql.dto.QueryMode;
import com.osh.text2sql.dto.QueryRequest;
import com.osh.text2sql.dto.QueryResponse;
import com.osh.text2sql.dto.WorkspaceSnapshotResponse;
import com.osh.text2sql.exception.BadRequestException;
import com.osh.text2sql.executor.ElasticsearchQueryExecutor;
import com.osh.text2sql.executor.MysqlQueryExecutor;
import com.osh.text2sql.executor.QueryExecutor;
import com.osh.text2sql.executor.RedisQueryExecutor;
import com.osh.text2sql.introspect.DatasourceIntrospector;
import com.osh.text2sql.introspect.ElasticsearchIntrospector;
import com.osh.text2sql.introspect.MysqlIntrospector;
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
    private final RedisIntrospector redisIntrospector;
    private final ElasticsearchIntrospector elasticsearchIntrospector;
    private final MysqlQueryExecutor mysqlQueryExecutor;
    private final RedisQueryExecutor redisQueryExecutor;
    private final ElasticsearchQueryExecutor elasticsearchQueryExecutor;

    public Text2QueryService(ConnectionProfileResolver profileResolver,
                             PromptService promptService,
                             Text2SqlProperties properties,
                             MysqlIntrospector mysqlIntrospector,
                             RedisIntrospector redisIntrospector,
                             ElasticsearchIntrospector elasticsearchIntrospector,
                             MysqlQueryExecutor mysqlQueryExecutor,
                             RedisQueryExecutor redisQueryExecutor,
                             ElasticsearchQueryExecutor elasticsearchQueryExecutor) {
        this.profileResolver = profileResolver;
        this.promptService = promptService;
        this.properties = properties;
        this.mysqlIntrospector = mysqlIntrospector;
        this.redisIntrospector = redisIntrospector;
        this.elasticsearchIntrospector = elasticsearchIntrospector;
        this.mysqlQueryExecutor = mysqlQueryExecutor;
        this.redisQueryExecutor = redisQueryExecutor;
        this.elasticsearchQueryExecutor = elasticsearchQueryExecutor;
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
                    .type(DatasourceType.ELASTICSEARCH)
                    .title("课程销量排行")
                    .prompt("查询 osh_course_index 中销量最高的 5 个课程")
                    .build(),
                QuerySuggestion.builder()
                    .type(DatasourceType.REDIS)
                    .title("查看 key")
                    .prompt("列出当前 Redis 数据库前 20 个 key")
                    .build()
            ))
            .build();
    }

    private DatasourceIntrospector introspector(DatasourceType type) {
        return switch (type) {
            case MYSQL -> mysqlIntrospector;
            case REDIS -> redisIntrospector;
            case ELASTICSEARCH -> elasticsearchIntrospector;
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
        };
    }
}
