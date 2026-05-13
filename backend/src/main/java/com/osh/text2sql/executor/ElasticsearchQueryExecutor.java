package com.osh.text2sql.executor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.DatasourceType;
import com.osh.text2sql.dto.QueryExecutionResult;
import com.osh.text2sql.exception.BadRequestException;
import com.osh.text2sql.introspect.ElasticsearchIntrospector;
import com.osh.text2sql.util.ElasticsearchQueryValidator;
import com.osh.text2sql.util.JsonUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ElasticsearchQueryExecutor implements QueryExecutor {

    private final ElasticsearchIntrospector introspector;

    public ElasticsearchQueryExecutor(ElasticsearchIntrospector introspector) {
        this.introspector = introspector;
    }

    @Override
    public QueryExecutionResult execute(ConnectionProfile profile, String query) {
        String safeDsl = ElasticsearchQueryValidator.validate(query);
        Map<String, Object> payload = JsonUtils.fromJson(safeDsl, new TypeReference<>() {
        });
        Object index = payload.remove("_index");
        if (index == null) {
            throw new BadRequestException("Elasticsearch DSL 需要包含 _index 字段");
        }
        String indexName = String.valueOf(index);
        RestTemplate restTemplate = introspector.createRestTemplate(profile);
        long start = System.currentTimeMillis();
        HttpHeaders headers = new HttpHeaders();
        ResponseEntity<String> response = restTemplate.exchange(
            profile.getBaseUrl() + "/" + indexName + "/_search",
            HttpMethod.POST,
            new HttpEntity<>(JsonUtils.toJson(payload), headers),
            String.class
        );
        long elapsed = System.currentTimeMillis() - start;
        Map<String, Object> body = JsonUtils.fromJson(response.getBody(), new TypeReference<>() {
        });
        Map<String, Object> hitsNode = castMap(body.get("hits"));
        List<Map<String, Object>> hits = hitsNode == null ? List.of() : castList(hitsNode.get("hits"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> hit : hits) {
            Map<String, Object> row = castMap(hit.get("_source"));
            if (row == null) {
                row = Map.of("_id", hit.get("_id"));
            }
            rows.add(row);
        }
        List<String> columns = rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet());
        long total = extractTotal(hitsNode == null ? null : hitsNode.get("total"));
        return QueryExecutionResult.builder()
            .type(DatasourceType.ELASTICSEARCH)
            .executedQuery("""
                POST /%s/_search
                %s
                """.formatted(indexName, JsonUtils.toJson(payload)))
            .queryLanguage("Elasticsearch DSL")
            .summary("命中 %d 条文档，当前返回 %d 条".formatted(total, rows.size()))
            .columns(columns)
            .rows(rows)
            .total(total)
            .elapsedMs(elapsed)
            .rawResponse(body)
            .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private long extractTotal(Object totalNode) {
        if (totalNode instanceof Map<?, ?> map) {
            Object value = map.get("value");
            if (value instanceof Number number) {
                return number.longValue();
            }
        }
        if (totalNode instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }
}
