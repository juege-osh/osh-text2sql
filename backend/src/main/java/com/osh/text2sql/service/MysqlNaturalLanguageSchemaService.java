package com.osh.text2sql.service;

import com.osh.text2sql.config.Text2SqlProperties;
import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.DatasourceSchemaResponse;
import com.osh.text2sql.introspect.MysqlIntrospector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * MySQL 自然语言结构路由服务
 */
@Service
public class MysqlNaturalLanguageSchemaService {

    private static final Logger log = LoggerFactory.getLogger(MysqlNaturalLanguageSchemaService.class);

    private final Text2SqlProperties properties;
    private final MysqlIntrospector mysqlIntrospector;
    private final QaAssistantMysqlTableSelectorService qaAssistantMysqlTableSelectorService;
    private final MysqlRedisSchemaLookupService mysqlRedisSchemaLookupService;

    public MysqlNaturalLanguageSchemaService(Text2SqlProperties properties,
                                             MysqlIntrospector mysqlIntrospector,
                                             QaAssistantMysqlTableSelectorService qaAssistantMysqlTableSelectorService,
                                             MysqlRedisSchemaLookupService mysqlRedisSchemaLookupService) {
        this.properties = properties;
        this.mysqlIntrospector = mysqlIntrospector;
        this.qaAssistantMysqlTableSelectorService = qaAssistantMysqlTableSelectorService;
        this.mysqlRedisSchemaLookupService = mysqlRedisSchemaLookupService;
    }

    public DatasourceSchemaResponse resolve(ConnectionProfile profile, String question) {
        if (shouldUseQaAssistant(question)) {
            log.info("MySQL 自然语言结构路由：route=QA_ASSISTANT_TABLES_WITH_REDIS_SCHEMA, database={}, question={}",
                profile.getDatabase(), question);
            return resolveByQaAndRedis(profile, question);
        }
        log.info("MySQL 自然语言结构路由：route=LOCAL_SCHEMA, database={}, question={}",
            profile.getDatabase(), question);
        return mysqlIntrospector.introspect(profile, question);
    }

    public String currentRoute(String question) {
        return shouldUseQaAssistant(question) ? "QA_ASSISTANT_TABLES_WITH_REDIS_SCHEMA" : "LOCAL_SCHEMA";
    }

    private DatasourceSchemaResponse resolveByQaAndRedis(ConnectionProfile profile, String question) {
        var selectionResult = qaAssistantMysqlTableSelectorService.selectTables(profile, question);
        return mysqlRedisSchemaLookupService.loadSchemaByTables(profile, selectionResult.getTables(), selectionResult.getReason());
    }

    private boolean shouldUseQaAssistant(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        Text2SqlProperties.MysqlTableSelectorProperties selector = properties.getMysqlTableSelector();
        return selector.getMode() == Text2SqlProperties.SelectorMode.QA_ASSISTANT
            && selector.getQaAssistant().isEnabled();
    }
}
