package com.osh.text2sql.controller;

import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.ConnectionTestResponse;
import com.osh.text2sql.dto.DatasourceSchemaResponse;
import com.osh.text2sql.dto.DatasourceType;
import com.osh.text2sql.dto.MysqlTableListResponse;
import com.osh.text2sql.dto.MysqlTableOperationRequest;
import com.osh.text2sql.dto.MysqlTableSchemaResponse;
import com.osh.text2sql.dto.QueryExecutionResult;
import com.osh.text2sql.dto.QueryRequest;
import com.osh.text2sql.dto.QueryResponse;
import com.osh.text2sql.dto.WorkspaceSnapshotResponse;
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

    public Text2QueryController(Text2QueryService text2QueryService) {
        this.text2QueryService = text2QueryService;
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
}
