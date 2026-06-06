package com.osh.text2sql.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

class QaKnowledgeImportServiceTest {

    @Test
    void shouldAppendNestedFieldSummary() throws Exception {
        QaKnowledgeImportService service = new QaKnowledgeImportService(null, null, null, null);
        Method method = QaKnowledgeImportService.class.getDeclaredMethod("appendFieldSummary",
            StringBuilder.class, Map.class, String.class, String.class);
        method.setAccessible(true);

        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("demo_index", Map.of(
            "mappings", Map.of(
                "properties", Map.of(
                    "title", Map.of("type", "text", "analyzer", "ik_max_word"),
                    "author", Map.of(
                        "properties", Map.of(
                            "id", Map.of("type", "keyword"),
                            "name", Map.of("type", "text")
                        )
                    )
                )
            )
        ));

        StringBuilder builder = new StringBuilder();
        method.invoke(service, builder, mapping, "demo_index", "");
        String summary = builder.toString();

        Assertions.assertTrue(summary.contains("- title: text (analyzer=ik_max_word)"));
        Assertions.assertTrue(summary.contains("- author: object"));
        Assertions.assertTrue(summary.contains("- author.id: keyword"));
        Assertions.assertTrue(summary.contains("- author.name: text"));
    }
}
