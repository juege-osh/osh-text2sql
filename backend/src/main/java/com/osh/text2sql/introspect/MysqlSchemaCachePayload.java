package com.osh.text2sql.introspect;

import java.util.List;
import java.util.Map;

public record MysqlSchemaCachePayload(String name,
                                      String summary,
                                      Map<String, Object> schema,
                                      List<TableMeta> tables) {

    public MysqlSchemaCachePayload(String name, String summary, Map<String, Object> schema) {
        this(name, summary, schema, List.of());
    }

    public record TableMeta(String tableName, String tableComment) {
    }
}
