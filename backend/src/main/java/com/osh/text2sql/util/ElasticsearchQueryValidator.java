package com.osh.text2sql.util;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.osh.text2sql.exception.BadRequestException;

import java.util.Map;

public final class ElasticsearchQueryValidator {

    private ElasticsearchQueryValidator() {
    }

    public static String validate(String queryJson) {
        if (StrUtil.isBlank(queryJson)) {
            throw new BadRequestException("Elasticsearch DSL 不能为空");
        }
        Map<String, Object> map = JsonUtils.fromJson(queryJson, new TypeReference<>() {
        });
        if (map.containsKey("script") || map.containsKey("aggs") && map.containsKey("delete")) {
            throw new BadRequestException("DSL 包含不被允许的高风险字段");
        }
        return JsonUtils.toJson(map);
    }
}
