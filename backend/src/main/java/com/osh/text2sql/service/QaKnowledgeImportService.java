package com.osh.text2sql.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.osh.text2sql.config.Text2SqlProperties;
import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.DatasourceType;
import com.osh.text2sql.dto.KnowledgeImportRequest;
import com.osh.text2sql.dto.KnowledgeImportResponse;
import com.osh.text2sql.exception.BadRequestException;
import com.osh.text2sql.introspect.ElasticsearchIntrospector;
import com.osh.text2sql.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisDataException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * QA 知识库导入服务
 */
@Service
public class QaKnowledgeImportService {

    private static final DateTimeFormatter FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter FILE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final Text2SqlProperties properties;
    private final ConnectionProfileResolver profileResolver;
    private final ElasticsearchIntrospector elasticsearchIntrospector;
    public QaKnowledgeImportService(Text2SqlProperties properties,
                                    ConnectionProfileResolver profileResolver,
                                    ElasticsearchIntrospector elasticsearchIntrospector) {
        this.properties = properties;
        this.profileResolver = profileResolver;
        this.elasticsearchIntrospector = elasticsearchIntrospector;
    }

    public KnowledgeImportResponse importElasticsearchMappings(KnowledgeImportRequest request) {
        ConnectionProfile profile = profileResolver.resolve(request.getConnection(), DatasourceType.ELASTICSEARCH);
        RestTemplate esTemplate = elasticsearchIntrospector.createRestTemplate(profile);
        List<Map<String, Object>> indices = fetchIndices(esTemplate, profile);
        if (indices.isEmpty()) {
            throw new BadRequestException("当前 Elasticsearch 没有可导入的 index");
        }

        String markdown = buildMarkdown(esTemplate, profile, indices);
        String qaBaseUrl = normalizeQaBaseUrl(request.getQaBaseUrl());
        String token = loginAndGetToken(qaBaseUrl, request.getQaUsername(), request.getQaPassword());
        UploadResult uploadResult = uploadMarkdown(qaBaseUrl, token, request.getModule(), markdown);
        addKnowledgeFile(qaBaseUrl, token, request.getLibId(), uploadResult.storePath(), uploadResult.originalFilename());

        List<String> importedIndices = indices.stream()
            .map(index -> Objects.toString(index.get("index"), ""))
            .filter(name -> !name.isBlank())
            .toList();
        return KnowledgeImportResponse.builder()
            .libId(request.getLibId())
            .indexCount(importedIndices.size())
            .uploadedFileName(uploadResult.originalFilename())
            .storePath(uploadResult.storePath())
            .importedIndices(importedIndices)
            .message("ES index 与 mapping 已导入 QA 知识库")
            .build();
    }

    public KnowledgeImportResponse importElasticsearchMappingsFromConfig() {
        Text2SqlProperties.QaKnowledgeImportProperties qaConfig = properties.getQaKnowledgeImport();
        if (!qaConfig.isEnabled()) {
            throw new BadRequestException("QA 知识库导入功能未启用");
        }
        KnowledgeImportRequest request = new KnowledgeImportRequest();
        request.setLibId(qaConfig.getLibId());
        request.setQaBaseUrl(qaConfig.getBaseUrl());
        request.setQaUsername(qaConfig.getUsername());
        request.setQaPassword(qaConfig.getPassword());
        request.setModule(qaConfig.getModule());
        return importElasticsearchMappings(request);
    }

    public KnowledgeImportResponse importMarkdownToKnowledgeLib(String qaBaseUrl,
                                                                String username,
                                                                String password,
                                                                Long libId,
                                                                String module,
                                                                String fileName,
                                                                String markdown) {
        String normalizedBaseUrl = normalizeQaBaseUrl(qaBaseUrl);
        String token = loginAndGetToken(normalizedBaseUrl, username, password);
        UploadResult uploadResult = uploadMarkdown(normalizedBaseUrl, token, module, markdown, fileName);
        addKnowledgeFile(normalizedBaseUrl, token, libId, uploadResult.storePath(), uploadResult.originalFilename());
        return KnowledgeImportResponse.builder()
            .libId(libId)
            .indexCount(1)
            .uploadedFileName(uploadResult.originalFilename())
            .storePath(uploadResult.storePath())
            .importedIndices(List.of("backend-redis-knowledge"))
            .message("Redis 知识文档已导入 QA 知识库")
            .build();
    }

    public KnowledgeImportResponse importMarkdownDocuments(String qaBaseUrl,
                                                           String username,
                                                           String password,
                                                           Long libId,
                                                           String module,
                                                           Map<String, String> documents) {
        if (documents == null || documents.isEmpty()) {
            throw new BadRequestException("没有可导入的知识文档");
        }
        String normalizedBaseUrl = normalizeQaBaseUrl(qaBaseUrl);
        String token = loginAndGetToken(normalizedBaseUrl, username, password);
        List<String> importedFiles = new ArrayList<>();
        String lastStorePath = null;
        for (Map.Entry<String, String> entry : documents.entrySet()) {
            UploadResult uploadResult = uploadMarkdown(normalizedBaseUrl, token, module, entry.getValue(), entry.getKey());
            addKnowledgeFile(normalizedBaseUrl, token, libId, uploadResult.storePath(), uploadResult.originalFilename());
            importedFiles.add(uploadResult.originalFilename());
            lastStorePath = uploadResult.storePath();
        }
        return KnowledgeImportResponse.builder()
            .libId(libId)
            .indexCount(importedFiles.size())
            .uploadedFileName(importedFiles.get(importedFiles.size() - 1))
            .storePath(lastStorePath)
            .importedIndices(importedFiles)
            .message("知识文档已批量导入 QA 知识库")
            .build();
    }

    private List<Map<String, Object>> fetchIndices(RestTemplate restTemplate, ConnectionProfile profile) {
        String indicesText = restTemplate.getForObject(profile.getBaseUrl() + "/_cat/indices?format=json", String.class);
        List<Map<String, Object>> indices = JsonUtils.fromJson(indicesText, new TypeReference<>() {
        });
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> index : indices) {
            String name = Objects.toString(index.get("index"), "");
            if (name.isBlank() || name.startsWith(".")) {
                continue;
            }
            result.add(index);
        }
        return result;
    }

    private String buildMarkdown(RestTemplate restTemplate, ConnectionProfile profile, List<Map<String, Object>> indices) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Elasticsearch 索引结构文档\n\n");
        builder.append("- 导出时间: ").append(LocalDateTime.now()).append("\n");
        builder.append("- 集群地址: ").append(profile.getBaseUrl()).append("\n");
        builder.append("- 索引数量: ").append(indices.size()).append("\n\n");

        for (Map<String, Object> index : indices) {
            String indexName = Objects.toString(index.get("index"), "");
            String mappingText = restTemplate.getForObject(profile.getBaseUrl() + "/" + indexName + "/_mapping", String.class);
            Map<String, Object> mappingMap = JsonUtils.fromJson(mappingText, new TypeReference<>() {
            });
            builder.append("## ").append(indexName).append("\n\n");
            builder.append("- 状态: ").append(Objects.toString(index.get("status"), "")).append("\n");
            builder.append("- 文档数: ").append(Objects.toString(index.get("docs.count"), "0")).append("\n");
            builder.append("- 主分片: ").append(Objects.toString(index.get("pri"), "")).append("\n");
            builder.append("- 副本分片: ").append(Objects.toString(index.get("rep"), "")).append("\n\n");
            builder.append("### 字段摘要\n\n");
            appendFieldSummary(builder, mappingMap, indexName, "");
            builder.append("\n### 原始 Mapping\n\n```json\n");
            builder.append(JsonUtils.toJson(mappingMap));
            builder.append("\n```\n\n");
        }
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private void appendFieldSummary(StringBuilder builder, Map<String, Object> mappingMap, String indexName, String prefix) {
        Object root = mappingMap.get(indexName);
        if (!(root instanceof Map<?, ?> rootMap)) {
            builder.append("- 未解析到 mapping\n");
            return;
        }
        Object mappings = ((Map<String, Object>) rootMap).get("mappings");
        if (!(mappings instanceof Map<?, ?> mappingsMap)) {
            builder.append("- 未解析到 mappings\n");
            return;
        }
        Object properties = ((Map<String, Object>) mappingsMap).get("properties");
        if (!(properties instanceof Map<?, ?> propertiesMap) || propertiesMap.isEmpty()) {
            builder.append("- 当前 index 没有显式 properties\n");
            return;
        }
        appendProperties(builder, (Map<String, Object>) propertiesMap, prefix, 0);
    }

    @SuppressWarnings("unchecked")
    private void appendProperties(StringBuilder builder, Map<String, Object> properties, String prefix, int depth) {
        String indent = "  ".repeat(Math.max(depth, 0));
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String fieldName = prefix.isBlank() ? entry.getKey() : prefix + "." + entry.getKey();
            Map<String, Object> fieldDef = entry.getValue() instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();
            String type = Objects.toString(fieldDef.get("type"), "object");
            builder.append(indent).append("- ").append(fieldName).append(": ").append(type);
            Object analyzer = fieldDef.get("analyzer");
            if (analyzer != null) {
                builder.append(" (analyzer=").append(analyzer).append(")");
            }
            builder.append("\n");
            Object subProperties = fieldDef.get("properties");
            if (subProperties instanceof Map<?, ?> subMap && !subMap.isEmpty()) {
                appendProperties(builder, (Map<String, Object>) subMap, fieldName, depth + 1);
            }
        }
    }

    private String normalizeQaBaseUrl(String qaBaseUrl) {
        String configured = qaBaseUrl;
        if (configured == null || configured.isBlank()) {
            configured = "http://43.242.200.67";
        }
        if (configured.endsWith("/")) {
            return configured.substring(0, configured.length() - 1);
        }
        return configured;
    }

    private String loginAndGetToken(String qaBaseUrl, String username, String password) {
        RestTemplate restTemplate = new RestTemplate();
        String captchaUrl = qaBaseUrl + "/consumer/user/getCode";
        String loginUrl = qaBaseUrl + "/consumer/user/login";

        Map<String, Object> captchaWrapper = restTemplate.getForObject(captchaUrl, Map.class);
        Map<String, Object> captchaData = extractData(captchaWrapper);
        String captchaId = Objects.toString(captchaData.get("captchaId"), "");
        if (captchaId.isBlank()) {
            throw new BadRequestException("获取 QA 验证码失败");
        }
        String captchaCode = fetchCaptchaCodeFromRedis(captchaId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("pwd", password);
        payload.put("captchaId", captchaId);
        payload.put("code", captchaCode);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.exchange(
            loginUrl,
            HttpMethod.POST,
            new HttpEntity<>(payload, headers),
            Map.class
        );
        Map<String, Object> wrapper = response.getBody();
        Map<String, Object> data = extractData(wrapper);
        String token = Objects.toString(data.get("token"), "");
        if (token.isBlank()) {
            throw new BadRequestException("QA 登录成功但未拿到 token");
        }
        return token;
    }

    private String fetchCaptchaCodeFromRedis(String captchaId) {
        Text2SqlProperties.QaRedisProperties redis = properties.getQaKnowledgeImport().getRedis();
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);
        try {
            return fetchCaptchaCode(poolConfig, redis, captchaId, redis.getPassword());
        } catch (JedisDataException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("ERR invalid password")) {
                return fetchCaptchaCode(poolConfig, redis, captchaId, null);
            }
            throw new BadRequestException("读取 QA 验证码失败: " + exception.getMessage());
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BadRequestException("读取 QA 验证码失败: " + exception.getMessage());
        }
    }

    private String fetchCaptchaCode(JedisPoolConfig poolConfig,
                                    Text2SqlProperties.QaRedisProperties redis,
                                    String captchaId,
                                    String password) {
        try (JedisPool pool = new JedisPool(poolConfig, redis.getHost(), redis.getPort(),
            2000, password, redis.getDatabase());
             Jedis jedis = pool.getResource()) {
            String captchaCode = jedis.get(captchaId);
            if (captchaCode == null || captchaCode.isBlank()) {
                throw new BadRequestException("未在 Redis 中读取到 QA 验证码");
            }
            return normalizeCaptchaCode(captchaCode);
        }
    }

    private String normalizeCaptchaCode(String captchaCode) {
        String normalized = captchaCode.trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        normalized = normalized.replace("\\\"", "\"");
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() >= 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private UploadResult uploadMarkdown(String qaBaseUrl, String token, String module, String markdown) {
        RestTemplate restTemplate = new RestTemplate();
        String filename = FILE_DATE_FORMATTER.format(LocalDate.now()) + "-es-index-mapping-" + FILE_TIME_FORMATTER.format(LocalDateTime.now()) + ".md";
        return uploadMarkdown(qaBaseUrl, token, module, markdown, filename);
    }

    private UploadResult uploadMarkdown(String qaBaseUrl, String token, String module, String markdown, String filename) {
        RestTemplate restTemplate = new RestTemplate();
        ByteArrayResource resource = new ByteArrayResource(markdown.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", resource);
        body.add("module", module);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set(HttpHeaders.AUTHORIZATION, token);
        ResponseEntity<Map> response = restTemplate.exchange(
            qaBaseUrl + "/consumer/storage/uploadFile",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            Map.class
        );
        Map<String, Object> data = extractData(response.getBody());
        String relativePath = Objects.toString(data.get("relativePath"), "");
        String originalFilename = Objects.toString(data.get("originalFilename"), filename);
        if (relativePath.isBlank()) {
            throw new BadRequestException("QA 上传文件成功但未返回存储路径");
        }
        return new UploadResult(relativePath, originalFilename);
    }

    private void addKnowledgeFile(String qaBaseUrl, String token, Long libId, String storePath, String originalFileName) {
        RestTemplate restTemplate = new RestTemplate();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("libId", libId);
        payload.put("storePath", storePath);
        payload.put("originalFileName", originalFileName);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, token);
        ResponseEntity<Map> response = restTemplate.exchange(
            qaBaseUrl + "/consumer/uploadFile/add",
            HttpMethod.POST,
            new HttpEntity<>(payload, headers),
            Map.class
        );
        Map<String, Object> body = response.getBody();
        if (body == null || !Boolean.TRUE.equals(body.get("success"))) {
            throw new BadRequestException("QA 知识库新增文件失败: " + (body == null ? "空响应" : Objects.toString(body.get("msg"), "未知错误")));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractData(Map<String, Object> wrapper) {
        if (wrapper == null) {
            throw new BadRequestException("QA 接口返回空响应");
        }
        Object success = wrapper.get("success");
        if (!Boolean.TRUE.equals(success)) {
            throw new BadRequestException("QA 接口调用失败: " + Objects.toString(wrapper.get("msg"), "未知错误"));
        }
        Object data = wrapper.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) {
            return Map.of();
        }
        return (Map<String, Object>) dataMap;
    }

    private record UploadResult(String storePath, String originalFilename) {
    }
}
