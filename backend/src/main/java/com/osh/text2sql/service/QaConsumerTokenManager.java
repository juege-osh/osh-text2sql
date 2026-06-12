package com.osh.text2sql.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.osh.text2sql.config.Text2SqlProperties;
import com.osh.text2sql.exception.BadRequestException;
import com.osh.text2sql.util.JsonUtils;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisDataException;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * QA 用户端 token 管理服务
 */
@Service
public class QaConsumerTokenManager {

    private static final Logger log = LoggerFactory.getLogger(QaConsumerTokenManager.class);

    private final Text2SqlProperties properties;

    private volatile CachedToken cachedToken;

    public QaConsumerTokenManager(Text2SqlProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void warmUpToken() {
        Text2SqlProperties.QaKnowledgeImportProperties qaConfig = properties.getQaKnowledgeImport();
        if (!qaConfig.isEnabled()) {
            log.info("QA token 预热已跳过：qa-knowledge-import 未启用");
            return;
        }
        try {
            CachedToken token = refreshToken();
            log.info("QA token 预热成功：expireAt={}", token.expireAt());
        } catch (Exception exception) {
            log.warn("QA token 预热失败，后续将在调用时重试：message={}", exception.getMessage());
        }
    }

    public String getValidToken() {
        CachedToken current = cachedToken;
        if (current != null && !shouldRefresh(current)) {
            return current.token();
        }
        synchronized (this) {
            current = cachedToken;
            if (current != null && !shouldRefresh(current)) {
                return current.token();
            }
            CachedToken refreshed = refreshToken();
            cachedToken = refreshed;
            return refreshed.token();
        }
    }

    private boolean shouldRefresh(CachedToken token) {
        Instant expireAt = token.expireAt();
        if (expireAt == null) {
            return true;
        }
        int aheadSeconds = Math.max(30, properties.getQaKnowledgeImport().getTokenRefreshAheadSeconds());
        return Instant.now().plusSeconds(aheadSeconds).isAfter(expireAt);
    }

    private CachedToken refreshToken() {
        Text2SqlProperties.QaKnowledgeImportProperties qaConfig = properties.getQaKnowledgeImport();
        String qaBaseUrl = normalizeQaBaseUrl(qaConfig.getBaseUrl());
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
        payload.put("username", qaConfig.getUsername());
        payload.put("pwd", qaConfig.getPassword());
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
        Instant expireAt = decodeExpireAt(token);
        log.info("QA token 已刷新：expireAt={}", expireAt);
        return new CachedToken(token, expireAt);
    }

    private Instant decodeExpireAt(String token) {
        try {
            String[] segments = token.split("\\.");
            if (segments.length < 2) {
                return null;
            }
            byte[] payloadBytes = Base64.getUrlDecoder().decode(segments[1]);
            Map<String, Object> payload = JsonUtils.fromJson(new String(payloadBytes), new TypeReference<>() {
            });
            Object exp = payload.get("exp");
            if (!(exp instanceof Number number)) {
                return null;
            }
            return Instant.ofEpochSecond(number.longValue());
        } catch (Exception exception) {
            log.warn("解析 QA token 过期时间失败：message={}", exception.getMessage());
            return null;
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

    private record CachedToken(String token, Instant expireAt) {
    }
}
