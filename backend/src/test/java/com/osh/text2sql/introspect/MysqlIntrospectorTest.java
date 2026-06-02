package com.osh.text2sql.introspect;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class MysqlIntrospectorTest {

//    @Test
//    @SuppressWarnings("unchecked")
//    void shouldKeepUserTableInTopRankedTablesForUserCountQuestion() throws Exception {
//        MysqlIntrospector introspector = new MysqlIntrospector();
//        Method rankTables = MysqlIntrospector.class.getDeclaredMethod("rankTables", List.class, String.class);
//        rankTables.setAccessible(true);
//
//        List<Map<String, Object>> tables = new ArrayList<>();
//        for (int i = 1; i <= 35; i++) {
//            tables.add(table("aaa_table_" + i, "普通业务表" + i));
//        }
//        tables.add(table("osh_comment", "评论记录"));
//        tables.add(table("osh_user", "用户主表"));
//
//        List<Map<String, Object>> ranked = (List<Map<String, Object>>) rankTables.invoke(
//            introspector,
//            tables,
//            "统计总共有多少个用户"
//        );
//
//        int userIndex = indexOf(ranked, "osh_user");
//        int commentIndex = indexOf(ranked, "osh_comment");
//
//        Assertions.assertTrue(userIndex >= 0, "用户主表必须进入结构摘要");
//        Assertions.assertTrue(commentIndex < 0 || userIndex < commentIndex, "用户主表优先级必须高于评论类业务表");
//        Assertions.assertTrue(ranked.size() <= 30, "结构摘要仍应保留最大表数量限制");
//    }

    private Map<String, Object> table(String tableName, String tableComment) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tableName", tableName);
        row.put("tableComment", tableComment);
        return row;
    }

    private int indexOf(List<Map<String, Object>> tables, String tableName) {
        for (int i = 0; i < tables.size(); i++) {
            if (tableName.equals(tables.get(i).get("tableName"))) {
                return i;
            }
        }
        return -1;
    }
}
