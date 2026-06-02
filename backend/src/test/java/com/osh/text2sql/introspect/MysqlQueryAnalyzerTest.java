package com.osh.text2sql.introspect;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class MysqlQueryAnalyzerTest {

    private final MysqlQueryAnalyzer analyzer = new MysqlQueryAnalyzer();

    @Test
    void shouldDetectExplicitTableAndCountIntent() {
        MysqlQueryPlan plan = analyzer.analyze(
            "查询 osh_user 表看看有多少用户数据",
            List.of(
                new MysqlSchemaCachePayload.TableMeta("osh_user", "用户主表"),
                new MysqlSchemaCachePayload.TableMeta("assistant_feedback", "反馈工单")
            ),
            Map.of(
                "osh_user", Map.of(
                    "columns", List.of(Map.of("columnName", "id"), Map.of("columnName", "create_time")),
                    "indexes", List.of(Map.of("indexName", "PRIMARY", "columnName", "id"))
                )
            ),
            30
        );

        Assertions.assertEquals(MysqlQueryIntent.COUNT, plan.intent());
        Assertions.assertEquals(List.of("osh_user"), plan.explicitTables());
        Assertions.assertEquals("osh_user", plan.preferredTable());
    }

    @Test
    void shouldNormalizeSynonymsAndPreferUserLikeTable() {
        MysqlQueryPlan plan = analyzer.analyze(
            "统计平台现在有多少账号",
            List.of(
                new MysqlSchemaCachePayload.TableMeta("sys_user", "系统用户"),
                new MysqlSchemaCachePayload.TableMeta("order_log", "订单日志")
            ),
            Map.of(
                "sys_user", List.of(Map.of("columnName", "id"), Map.of("columnName", "nickname")),
                "order_log", List.of(Map.of("columnName", "id"))
            ),
            30
        );

        Assertions.assertTrue(plan.normalizedTerms().contains("user"));
        Assertions.assertEquals(MysqlQueryIntent.COUNT, plan.intent());
        Assertions.assertEquals("sys_user", plan.preferredTable());
    }

    @Test
    void shouldDetectRecentIntentAndReasonableLimit() {
        MysqlQueryPlan plan = analyzer.analyze(
            "看看最近注册的成员",
            Map.of(
                "sys_user", List.of(
                    Map.of("columnName", "id"),
                    Map.of("columnName", "create_time"),
                    Map.of("columnName", "nickname")
                )
            )
        );

        Assertions.assertEquals(MysqlQueryIntent.LIST_RECENT, plan.intent());
        Assertions.assertEquals(5, plan.limit());
    }

    @Test
    void shouldPreferOshPrefixedTablesByDefault() {
        MysqlQueryPlan plan = analyzer.analyze(
            "统计现在有多少用户",
            List.of(
                new MysqlSchemaCachePayload.TableMeta("sys_user", "系统用户"),
                new MysqlSchemaCachePayload.TableMeta("osh_user", "业务用户主表"),
                new MysqlSchemaCachePayload.TableMeta("user_log", "用户日志")
            ),
            Map.of(
                "sys_user", List.of(Map.of("columnName", "id")),
                "osh_user", List.of(Map.of("columnName", "id"), Map.of("columnName", "create_time")),
                "user_log", List.of(Map.of("columnName", "id"))
            ),
            30
        );

        Assertions.assertEquals("osh_user", plan.preferredTable());
        Assertions.assertTrue(plan.candidateTables().indexOf("osh_user") < plan.candidateTables().indexOf("sys_user"));
    }

    @Test
    void shouldPreferToolModuleForToolQueries() {
        MysqlQueryPlan plan = analyzer.analyze(
            "统计现有多少已发布的工具",
            List.of(
                new MysqlSchemaCachePayload.TableMeta("osh_tool", "工具主表"),
                new MysqlSchemaCachePayload.TableMeta("osh_user_tool_quotas", "用户工具配额"),
                new MysqlSchemaCachePayload.TableMeta("osh_group_user", "用户组关系"),
            new MysqlSchemaCachePayload.TableMeta("sys_user", "系统用户")
            ),
            Map.of(
                "osh_tool", Map.of(
                    "columns", List.of(Map.of("columnName", "publish_status"), Map.of("columnName", "tool_name")),
                    "indexes", List.of(Map.of("indexName", "idx_publish_status", "columnName", "publish_status"))
                ),
                "osh_user_tool_quotas", Map.of(
                    "columns", List.of(Map.of("columnName", "user_id"), Map.of("columnName", "tool_quota")),
                    "indexes", List.of(Map.of("indexName", "idx_user_id", "columnName", "user_id"))
                ),
                "osh_group_user", Map.of(
                    "columns", List.of(Map.of("columnName", "user_id")),
                    "indexes", List.of()
                ),
                "sys_user", Map.of(
                    "columns", List.of(Map.of("columnName", "id")),
                    "indexes", List.of(Map.of("indexName", "PRIMARY", "columnName", "id"))
                )
            ),
            30
        );

        Assertions.assertEquals("osh_tool", plan.preferredTable());
        Assertions.assertTrue(plan.candidateTables().indexOf("osh_tool") < plan.candidateTables().indexOf("osh_user_tool_quotas"));
    }

    @Test
    void shouldReduceCandidateTableCountForSimpleToolCountQuestion() {
        List<MysqlSchemaCachePayload.TableMeta> tables = java.util.stream.IntStream.range(0, 20)
            .mapToObj(index -> new MysqlSchemaCachePayload.TableMeta("osh_dummy_" + index, "无关表"))
            .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        tables.add(new MysqlSchemaCachePayload.TableMeta("osh_tool", "工具主表"));
        tables.add(new MysqlSchemaCachePayload.TableMeta("osh_tool_search", "工具搜索表"));
        tables.add(new MysqlSchemaCachePayload.TableMeta("sys_user", "系统用户"));

        MysqlQueryPlan plan = analyzer.analyze(
            "查询可用工具的数量",
            tables,
            Map.of(
                "osh_tool", Map.of(
                    "columns", List.of(Map.of("columnName", "status"), Map.of("columnName", "delete_flag")),
                    "indexes", List.of(Map.of("indexName", "idx_status", "columnName", "status"))
                ),
                "osh_tool_search", Map.of(
                    "columns", List.of(Map.of("columnName", "tool_id")),
                    "indexes", List.of()
                ),
                "sys_user", Map.of(
                    "columns", List.of(Map.of("columnName", "id")),
                    "indexes", List.of()
                )
            ),
            30
        );

        Assertions.assertEquals(MysqlQueryIntent.COUNT, plan.intent());
        Assertions.assertEquals("osh_tool", plan.preferredTable());
        Assertions.assertEquals(8, plan.candidateLimit());
        Assertions.assertTrue(plan.candidateTables().size() <= 8);
    }

    @Test
    void shouldPreferQuotaTableForUserToolQuotaQuestions() {
        MysqlQueryPlan plan = analyzer.analyze(
            "用户id23的用户有分别有多少工具的可用次数",
            List.of(
                new MysqlSchemaCachePayload.TableMeta("osh_user", "用户主表"),
                new MysqlSchemaCachePayload.TableMeta("osh_tool", "工具主表"),
                new MysqlSchemaCachePayload.TableMeta("osh_user_tool_quota", "用户工具配额")
            ),
            Map.of(
                "osh_user", List.of(Map.of("columnName", "id")),
                "osh_tool", List.of(Map.of("columnName", "id"), Map.of("columnName", "tool_name")),
                "osh_user_tool_quota", Map.of(
                    "columns", List.of(
                        Map.of("columnName", "user_id"),
                        Map.of("columnName", "tool_id"),
                        Map.of("columnName", "remaining_count")
                    ),
                    "indexes", List.of(
                        Map.of("indexName", "idx_user_remaining", "columnName", "user_id"),
                        Map.of("indexName", "uk_user_tool", "columnName", "tool_id")
                    )
                )
            ),
            30
        );

        Assertions.assertEquals(MysqlQueryIntent.FILTER, plan.intent());
        Assertions.assertEquals("osh_user_tool_quota", plan.preferredTable());
    }

    @Test
    void shouldRecognizeWebsiteAndOpenProjectModules() {
        MysqlQueryPlan websitePlan = analyzer.analyze(
            "列出最近上线的实用网站",
            Map.of(
                "osh_practical_website", List.of(Map.of("columnName", "create_time"), Map.of("columnName", "title")),
                "osh_open_project", List.of(Map.of("columnName", "create_time"))
            )
        );
        MysqlQueryPlan projectPlan = analyzer.analyze(
            "统计开源项目总数",
            Map.of(
                "osh_practical_website", List.of(Map.of("columnName", "id")),
                "osh_open_project", List.of(Map.of("columnName", "id"))
            )
        );

        Assertions.assertEquals("osh_practical_website", websitePlan.preferredTable());
        Assertions.assertEquals("osh_open_project", projectPlan.preferredTable());
    }

    @Test
    void shouldRecognizeQaAndInfoGapModules() {
        MysqlQueryPlan questionPlan = analyzer.analyze(
            "统计当前问答问题数量",
            Map.of(
                "osh_question_answer_question", List.of(Map.of("columnName", "id")),
                "osh_question_answer_answer", List.of(Map.of("columnName", "id"))
            )
        );
        MysqlQueryPlan answerPlan = analyzer.analyze(
            "看看最近的问答答案",
            Map.of(
                "osh_question_answer_question", List.of(Map.of("columnName", "create_time")),
                "osh_question_answer_answer", List.of(Map.of("columnName", "create_time"))
            )
        );
        MysqlQueryPlan infoGapPlan = analyzer.analyze(
            "统计信息差内容数量",
            Map.of(
                "osh_info_gap", List.of(Map.of("columnName", "id")),
                "osh_tool", List.of(Map.of("columnName", "id"))
            )
        );

        Assertions.assertEquals("osh_question_answer_question", questionPlan.preferredTable());
        Assertions.assertEquals("osh_question_answer_answer", answerPlan.preferredTable());
        Assertions.assertEquals("osh_info_gap", infoGapPlan.preferredTable());
    }
}
