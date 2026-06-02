package com.osh.text2sql.introspect;

import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MysqlQueryAnalyzer {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private static final Map<String, List<String>> TERM_DICTIONARY = Map.ofEntries(
        Map.entry("user", List.of("用户", "账号", "成员", "注册用户", "user", "users")),
        Map.entry("feedback", List.of("反馈", "工单", "反馈单", "feedback")),
        Map.entry("post", List.of("帖子", "贴文", "论坛内容", "post")),
        Map.entry("course", List.of("课程", "班课", "course", "osh_course", "osh_course_search_read", "课程搜索")),
        Map.entry("qa_question", List.of("问答问题", "问题", "提问", "题目", "问答题", "qa_question", "osh_question_answer_question")),
        Map.entry("qa_answer", List.of("问答答案", "答案", "回答", "解答", "答复", "qa_answer", "osh_question_answer_answer")),
        Map.entry("book", List.of("电子书", "书籍", "图书", "book", "osh_book", "阅读内容")),
        Map.entry("tool", List.of("工具", "插件", "能力", "工具集", "tool", "osh_tool", "osh_tool_search", "工具搜索")),
        Map.entry("website", List.of("网站", "站点", "网址", "实用网站", "导航站", "website", "osh_practical_website")),
        Map.entry("open_project", List.of("开源项目", "项目", "仓库", "repo", "github项目", "open_project", "osh_open_project")),
        Map.entry("info_gap", List.of("信息差", "资讯差", "机会", "套利信息", "情报", "info_gap", "osh_info_gap")),
        Map.entry("order", List.of("订单", "交易", "order")),
        Map.entry("quota", List.of("配额", "额度", "可用次数", "剩余次数", "剩余可用次数", "quota", "remaining_count"))
    );

    private static final List<String> COUNT_TERMS = List.of("多少", "总数", "总共", "数量", "统计", "count");
    private static final List<String> RECENT_TERMS = List.of("最近", "最新", "近", "刚刚");
    private static final List<String> TOP_TERMS = List.of("最高", "最大", "最贵", "最热门", "销量最高", "排行");
    private static final List<String> FILTER_TERMS = List.of("包含", "等于", "按", "筛选", "查询");

    public MysqlQueryPlan analyze(String question,
                                  List<MysqlSchemaCachePayload.TableMeta> tables,
                                  Map<String, Object> schema,
                                  int maxTables) {
        List<String> normalizedTerms = normalizedTerms(question);
        List<String> explicitTables = explicitTables(question, tables);
        MysqlQueryIntent intent = detectIntent(question);
        Integer limit = detectLimit(question, intent);
        SemanticProfile semanticProfile = new SemanticProfile(normalizedTerms, explicitTables, intent);
        int candidateLimit = determineCandidateLimit(semanticProfile, maxTables);
        List<String> candidateTables = rankTables(semanticProfile, tables, schema, candidateLimit);
        String preferredTable = candidateTables.isEmpty() ? null : candidateTables.get(0);
        return new MysqlQueryPlan(question, normalizedTerms, explicitTables, intent, limit, candidateLimit, candidateTables, preferredTable);
    }

    public MysqlQueryPlan analyze(String question, Map<String, Object> schema) {
        List<MysqlSchemaCachePayload.TableMeta> tables = schema.keySet().stream()
            .sorted()
            .map(name -> new MysqlSchemaCachePayload.TableMeta(name, ""))
            .toList();
        return analyze(question, tables, schema, Math.min(30, tables.size()));
    }

    private List<String> normalizedTerms(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        Set<String> result = new LinkedHashSet<>();
        TERM_DICTIONARY.forEach((normalized, variants) -> {
            for (String variant : variants) {
                if (lower.contains(variant.toLowerCase(Locale.ROOT)) || question.contains(variant)) {
                    result.add(normalized);
                    break;
                }
            }
        });
        return new ArrayList<>(result);
    }

    private List<String> explicitTables(String question, List<MysqlSchemaCachePayload.TableMeta> tables) {
        String lower = question.toLowerCase(Locale.ROOT);
        return tables.stream()
            .map(MysqlSchemaCachePayload.TableMeta::tableName)
            .filter(name -> lower.contains(name.toLowerCase(Locale.ROOT)))
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
    }

    private MysqlQueryIntent detectIntent(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        if (isQuotaDetailQuestion(lower, question)) {
            return MysqlQueryIntent.FILTER;
        }
        if (containsAny(lower, question, COUNT_TERMS)) {
            return MysqlQueryIntent.COUNT;
        }
        if (containsAny(lower, question, RECENT_TERMS)) {
            return MysqlQueryIntent.LIST_RECENT;
        }
        if (containsAny(lower, question, TOP_TERMS)) {
            return MysqlQueryIntent.TOP_N;
        }
        if (containsAny(lower, question, FILTER_TERMS)) {
            return MysqlQueryIntent.FILTER;
        }
        return MysqlQueryIntent.LIST;
    }

    private Integer detectLimit(String question, MysqlQueryIntent intent) {
        Matcher matcher = NUMBER_PATTERN.matcher(question);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return switch (intent) {
            case COUNT -> null;
            case LIST_RECENT -> 5;
            case TOP_N -> 10;
            default -> 10;
        };
    }

    private List<String> rankTables(SemanticProfile semanticProfile,
                                    List<MysqlSchemaCachePayload.TableMeta> tables,
                                    Map<String, Object> schema,
                                    int maxTables) {
        List<TableScore> scored = new ArrayList<>();
        for (MysqlSchemaCachePayload.TableMeta table : tables) {
            StructuralProfile structuralProfile = new StructuralProfile(
                table.tableName(),
                table.tableComment() == null ? "" : table.tableComment(),
                columnsOf(schema.get(table.tableName()))
            );
            int score = scoreTable(semanticProfile, structuralProfile);
            scored.add(new TableScore(table.tableName(), score));
        }
        scored.sort(Comparator.comparingInt(TableScore::score).reversed().thenComparing(TableScore::tableName));
        return scored.stream()
            .limit(maxTables)
            .map(TableScore::tableName)
            .toList();
    }

    private int determineCandidateLimit(SemanticProfile semanticProfile, int maxTables) {
        if (!semanticProfile.explicitTables().isEmpty()) {
            return Math.min(4, maxTables);
        }
        if (semanticProfile.normalizedTerms().isEmpty()) {
            return Math.min(12, maxTables);
        }
        if (semanticProfile.intent() == MysqlQueryIntent.COUNT) {
            return Math.min(8, maxTables);
        }
        if (semanticProfile.intent() == MysqlQueryIntent.FILTER
            && semanticProfile.normalizedTerms().contains("user")
            && semanticProfile.normalizedTerms().contains("tool")
            && semanticProfile.normalizedTerms().contains("quota")) {
            return Math.min(6, maxTables);
        }
        return Math.min(12, maxTables);
    }

    private int scoreTable(SemanticProfile semanticProfile, StructuralProfile structuralProfile) {
        int score = 0;

        score += explicitTableScore(semanticProfile, structuralProfile);
        score += prefixScore(structuralProfile);
        score += normalizedTermScore(semanticProfile, structuralProfile);
        score += intentScore(semanticProfile, structuralProfile);

        return score;
    }

    private int explicitTableScore(SemanticProfile semanticProfile, StructuralProfile structuralProfile) {
        if (semanticProfile.explicitTables().isEmpty()) {
            return 0;
        }
        if (semanticProfile.explicitTables().stream().anyMatch(name -> name.equalsIgnoreCase(structuralProfile.tableName()))) {
            return 120;
        }
        return -20;
    }

    private int prefixScore(StructuralProfile structuralProfile) {
        return structuralProfile.oshPrefixed() ? 35 : -8;
    }

    private int normalizedTermScore(SemanticProfile semanticProfile, StructuralProfile structuralProfile) {
        int score = 0;
        for (String term : semanticProfile.normalizedTerms()) {
            if (structuralProfile.tableNameLower().contains(term)) {
                score += 16;
            }
            if (structuralProfile.tableCommentLower().contains(term)) {
                score += 12;
            }
            if (isPrimaryModuleTable(term, structuralProfile.tableName())) {
                score += 40;
            }
            for (String column : structuralProfile.columnsLower()) {
                if (column.contains(term)) {
                    score += 5;
                }
            }
            if ("user".equals(term) && structuralProfile.userLike()) {
                score += 50;
            }
            if ("user".equals(term) && "osh_user".equalsIgnoreCase(structuralProfile.tableName())) {
                score += 45;
            }
            if ("quota".equals(term) && hasAnyColumn(structuralProfile.columns(), List.of("remaining_count", "quota", "tool_id", "user_id"))) {
                score += 28;
            }
        }
        return score;
    }

    private int intentScore(SemanticProfile semanticProfile, StructuralProfile structuralProfile) {
        int score = 0;
        if (semanticProfile.intent() == MysqlQueryIntent.COUNT
            && semanticProfile.normalizedTerms().contains("user")
            && structuralProfile.userLike()) {
            score += 20;
        }
        if (semanticProfile.intent() == MysqlQueryIntent.LIST_RECENT
            && hasAnyColumn(structuralProfile.columns(), List.of("create_time", "created_at", "gmt_create", "created_time"))) {
            score += 18;
        }
        if (semanticProfile.intent() == MysqlQueryIntent.TOP_N
            && hasAnyColumn(structuralProfile.columns(), List.of("sales", "sale_count", "price", "amount", "score", "hot"))) {
            score += 18;
        }
        if (semanticProfile.intent() == MysqlQueryIntent.FILTER
            && hasAnyColumn(structuralProfile.columns(), List.of("name", "title", "username", "nickname", "mobile"))) {
            score += 10;
        }
        if (semanticProfile.intent() == MysqlQueryIntent.FILTER
            && semanticProfile.normalizedTerms().contains("user")
            && semanticProfile.normalizedTerms().contains("tool")
            && semanticProfile.normalizedTerms().contains("quota")
            && hasAnyColumn(structuralProfile.columns(), List.of("user_id", "tool_id", "remaining_count"))) {
            score += 42;
        }
        return score;
    }

    private boolean isQuotaDetailQuestion(String lower, String raw) {
        boolean mentionsUser = lower.contains("user") || raw.contains("用户");
        boolean mentionsTool = lower.contains("tool") || raw.contains("工具");
        boolean mentionsQuota = raw.contains("可用次数")
            || raw.contains("剩余次数")
            || raw.contains("剩余可用次数")
            || raw.contains("配额")
            || raw.contains("额度")
            || lower.contains("quota")
            || lower.contains("remaining_count");
        return mentionsUser && mentionsTool && mentionsQuota;
    }

    private boolean containsAny(String lower, String raw, List<String> terms) {
        for (String term : terms) {
            if (lower.contains(term.toLowerCase(Locale.ROOT)) || raw.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyColumn(List<String> columns, List<String> candidates) {
        for (String candidate : candidates) {
            for (String column : columns) {
                if (column.equalsIgnoreCase(candidate) || column.toLowerCase(Locale.ROOT).contains(candidate.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isUserLikeName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("sys_user")
            || lower.equals("osh_user")
            || lower.equals("user")
            || lower.equals("users")
            || lower.endsWith("_user")
            || lower.contains("user");
    }

    private boolean isPrimaryModuleTable(String normalizedTerm, String tableName) {
        String lower = tableName.toLowerCase(Locale.ROOT);
        return switch (normalizedTerm) {
            case "course" -> "osh_course".equals(lower) || "osh_course_search_read".equals(lower);
            case "qa_question" -> "osh_question_answer_question".equals(lower);
            case "qa_answer" -> "osh_question_answer_answer".equals(lower);
            case "book" -> "osh_book".equals(lower);
            case "tool" -> "osh_tool".equals(lower) || "osh_tool_search".equals(lower);
            case "website" -> "osh_practical_website".equals(lower);
            case "open_project" -> "osh_open_project".equals(lower);
            case "info_gap" -> "osh_info_gap".equals(lower);
            case "quota" -> "osh_user_tool_quota".equals(lower) || "osh_user_tool_quotas".equals(lower);
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private List<String> columnsOf(@Nullable Object rawColumns) {
        Object value = rawColumns;
        if (rawColumns instanceof Map<?, ?>) {
            value = ((Map<String, Object>) rawColumns).getOrDefault("columns", List.of());
        }
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object columnName = ((Map<String, Object>) map).get("columnName");
                if (columnName != null) {
                    result.add(String.valueOf(columnName));
                }
                Object columnComment = ((Map<String, Object>) map).get("columnComment");
                if (columnComment != null && !String.valueOf(columnComment).isBlank()) {
                    result.add(String.valueOf(columnComment));
                }
            }
        }
        return result;
    }

    private record TableScore(String tableName, int score) {
    }

    private record SemanticProfile(List<String> normalizedTerms,
                                   List<String> explicitTables,
                                   MysqlQueryIntent intent) {
    }

    private record StructuralProfile(String tableName,
                                     String tableComment,
                                     List<String> columns) {
        private String tableNameLower() {
            return tableName.toLowerCase(Locale.ROOT);
        }

        private String tableCommentLower() {
            return tableComment.toLowerCase(Locale.ROOT);
        }

        private List<String> columnsLower() {
            return columns.stream().map(column -> column.toLowerCase(Locale.ROOT)).toList();
        }

        private boolean oshPrefixed() {
            return tableNameLower().startsWith("osh_");
        }

        private boolean userLike() {
            String lower = tableNameLower();
            return lower.equals("sys_user")
                || lower.equals("osh_user")
                || lower.equals("user")
                || lower.equals("users")
                || lower.endsWith("_user")
                || lower.contains("user");
        }
    }
}
