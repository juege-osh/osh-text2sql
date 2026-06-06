package com.osh.text2sql.controller;

import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.ConnectionTestResponse;
import com.osh.text2sql.dto.DatasourceSchemaResponse;
import com.osh.text2sql.dto.DatasourceType;
import com.osh.text2sql.dto.KnowledgeImportRequest;
import com.osh.text2sql.dto.KnowledgeImportResponse;
import com.osh.text2sql.dto.MysqlTableListResponse;
import com.osh.text2sql.dto.MysqlTableOperationRequest;
import com.osh.text2sql.dto.MysqlTableSchemaResponse;
import com.osh.text2sql.dto.QueryExecutionResult;
import com.osh.text2sql.dto.QueryRequest;
import com.osh.text2sql.dto.QueryResponse;
import com.osh.text2sql.dto.WorkspaceSnapshotResponse;
import com.osh.text2sql.service.MysqlKnowledgeImportService;
import com.osh.text2sql.service.QaKnowledgeImportService;
import com.osh.text2sql.service.RedisKnowledgeImportService;
import com.osh.text2sql.service.Text2QueryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/query")
public class Text2QueryController {

    private final Text2QueryService text2QueryService;
    private final QaKnowledgeImportService qaKnowledgeImportService;
    private final RedisKnowledgeImportService redisKnowledgeImportService;
    private final MysqlKnowledgeImportService mysqlKnowledgeImportService;

    public Text2QueryController(Text2QueryService text2QueryService,
                                QaKnowledgeImportService qaKnowledgeImportService,
                                RedisKnowledgeImportService redisKnowledgeImportService,
                                MysqlKnowledgeImportService mysqlKnowledgeImportService) {
        this.text2QueryService = text2QueryService;
        this.qaKnowledgeImportService = qaKnowledgeImportService;
        this.redisKnowledgeImportService = redisKnowledgeImportService;
        this.mysqlKnowledgeImportService = mysqlKnowledgeImportService;
    }

    @PostMapping
    public QueryResponse query(@Valid @RequestBody QueryRequest request) {
        return text2QueryService.query(request);
    }

    @PostMapping("/test-connection")
    public ConnectionTestResponse testConnection(@RequestBody ConnectionProfile profile) {
        return text2QueryService.test(profile.getType(), profile);
    }

    @GetMapping("/schema")
    public DatasourceSchemaResponse schema(@RequestParam DatasourceType type) {
        return text2QueryService.schema(type, null);
    }

    @PostMapping("/schema")
    public DatasourceSchemaResponse schemaByConnection(@RequestBody ConnectionProfile profile) {
        return text2QueryService.schema(profile.getType(), profile);
    }

    @GetMapping("/snapshot")
    public WorkspaceSnapshotResponse snapshot() {
        return text2QueryService.snapshot();
    }

    @PostMapping("/mysql/tables")
    public MysqlTableListResponse mysqlTables(@RequestBody ConnectionProfile profile) {
        return text2QueryService.mysqlTables(profile);
    }

    @PostMapping("/mysql/tables/refresh")
    public MysqlTableListResponse refreshMysqlTables(@RequestBody ConnectionProfile profile) {
        return text2QueryService.refreshMysqlTables(profile);
    }

    @PostMapping("/mysql/table-schema")
    public MysqlTableSchemaResponse mysqlTableSchema(@Valid @RequestBody MysqlTableOperationRequest request) {
        return text2QueryService.mysqlTableSchema(request);
    }

    @PostMapping("/mysql/table-preview")
    public QueryExecutionResult mysqlTablePreview(@Valid @RequestBody MysqlTableOperationRequest request) {
        return text2QueryService.mysqlTablePreview(request);
    }

    @PostMapping("/elasticsearch/import-knowledge")
    public KnowledgeImportResponse importElasticsearchKnowledge(@Valid @RequestBody KnowledgeImportRequest request) {
        return qaKnowledgeImportService.importElasticsearchMappings(request);
    }

    @GetMapping("/elasticsearch/import-knowledge/easy")
    public KnowledgeImportResponse importElasticsearchKnowledgeFromConfig() {
        return qaKnowledgeImportService.importElasticsearchMappingsFromConfig();
    }

    @GetMapping("/redis/import-knowledge/easy")
    public KnowledgeImportResponse importRedisKnowledgeFromConfig() {
        return redisKnowledgeImportService.importBackendRedisKnowledge();
    }

    @GetMapping("/mysql/import-knowledge/easy")
    public KnowledgeImportResponse importMysqlKnowledgeFromConfig() {
        return mysqlKnowledgeImportService.importMysqlSchemaKnowledge();
    }

    @GetMapping("/mysql/import-index-knowledge/easy")
    public KnowledgeImportResponse importMysqlIndexKnowledgeFromConfig() {
        return mysqlKnowledgeImportService.importMysqlIndexKnowledge();
    }
}
