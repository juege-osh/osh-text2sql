package com.osh.text2sql.introspect;

import java.util.List;

public record MysqlQueryPlan(String rawQuestion,
                             List<String> normalizedTerms,
                             List<String> explicitTables,

                             MysqlQueryIntent intent,
                             Integer limit,
                             int candidateLimit,
                             List<String> candidateTables,
                             String preferredTable) {
}
