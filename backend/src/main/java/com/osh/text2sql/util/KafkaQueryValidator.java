package com.osh.text2sql.util;

import cn.hutool.core.util.StrUtil;
import com.osh.text2sql.dto.KafkaQuerySpec;
import com.osh.text2sql.exception.BadRequestException;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class KafkaQueryValidator {

    private static final Set<String> ALLOWED_OPERATIONS = Set.of(
        "LIST_TOPICS",
        "DESCRIBE_TOPIC",
        "READ_MESSAGES"
    );

    private KafkaQueryValidator() {
    }

    public static KafkaQuerySpec validate(String queryJson) {
        if (StrUtil.isBlank(queryJson)) {
            throw new BadRequestException("Kafka 查询 DSL 不能为空");
        }
        KafkaQuerySpec spec = JsonUtils.fromJson(queryJson, KafkaQuerySpec.class);
        if (spec == null || StrUtil.isBlank(spec.getOperation())) {
            throw new BadRequestException("Kafka 查询 DSL 缺少 operation");
        }
        String operation = spec.getOperation().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_OPERATIONS.contains(operation)) {
            throw new BadRequestException("Kafka 仅支持 LIST_TOPICS、DESCRIBE_TOPIC、READ_MESSAGES");
        }
        spec.setOperation(operation);

        if (spec.getLimit() == null) {
            spec.setLimit("READ_MESSAGES".equals(operation) ? 10 : 20);
        }
        if (spec.getLimit() < 1 || spec.getLimit() > 100) {
            throw new BadRequestException("Kafka 查询 limit 仅允许 1 到 100");
        }

        switch (operation) {
            case "LIST_TOPICS" -> {
                if (spec.getIncludeInternal() == null) {
                    spec.setIncludeInternal(false);
                }
            }
            case "DESCRIBE_TOPIC" -> requireTopic(spec);
            case "READ_MESSAGES" -> {
                requireTopic(spec);
                normalizeReadSpec(spec);
            }
            default -> throw new BadRequestException("不支持的 Kafka 操作");
        }
        return spec;
    }

    private static void requireTopic(KafkaQuerySpec spec) {
        if (StrUtil.isBlank(spec.getTopic())) {
            throw new BadRequestException("Kafka 查询 DSL 缺少 topic");
        }
        spec.setTopic(spec.getTopic().trim());
    }

    private static void normalizeReadSpec(KafkaQuerySpec spec) {
        if (spec.getPartition() != null && spec.getPartition() < 0) {
            throw new BadRequestException("Kafka partition 不能小于 0");
        }
        if (spec.getOffset() != null && spec.getOffset() < 0) {
            throw new BadRequestException("Kafka offset 不能小于 0");
        }
        if (spec.getFrom() == null) {
            spec.setFrom("LATEST");
        } else {
            spec.setFrom(spec.getFrom().trim().toUpperCase(Locale.ROOT));
        }
        Set<String> allowedFrom = new LinkedHashSet<>(Set.of("LATEST", "EARLIEST", "OFFSET"));
        if (!allowedFrom.contains(spec.getFrom())) {
            throw new BadRequestException("Kafka from 仅支持 LATEST、EARLIEST、OFFSET");
        }
        if ("OFFSET".equals(spec.getFrom()) && spec.getOffset() == null) {
            throw new BadRequestException("当 from=OFFSET 时必须提供 offset");
        }
        if (StrUtil.isNotBlank(spec.getKeyContains())) {
            spec.setKeyContains(spec.getKeyContains().trim());
        }
        if (StrUtil.isNotBlank(spec.getValueContains())) {
            spec.setValueContains(spec.getValueContains().trim());
        }
    }
}
