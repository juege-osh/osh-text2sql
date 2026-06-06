package com.osh.text2sql.util;

import com.osh.text2sql.dto.HbaseQuerySpec;
import com.osh.text2sql.exception.BadRequestException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class HbaseQueryValidatorTest {

    @Test
    void shouldNormalizeListTablesSpec() {
        HbaseQuerySpec spec = HbaseQueryValidator.validate("""
            {
              "operation": "list_tables"
            }
            """);

        Assertions.assertEquals("LIST_TABLES", spec.getOperation());
        Assertions.assertEquals("default", spec.getNamespace());
        Assertions.assertEquals(50, spec.getLimit());
    }

    @Test
    void shouldRequireTableForGetRow() {
        Assertions.assertThrows(BadRequestException.class, () -> HbaseQueryValidator.validate("""
            {
              "operation": "GET_ROW",
              "rowKey": "user:1001"
            }
            """));
    }

    @Test
    void shouldRequireRowKeyForGetRow() {
        Assertions.assertThrows(BadRequestException.class, () -> HbaseQueryValidator.validate("""
            {
              "operation": "GET_ROW",
              "table": "user_profile"
            }
            """));
    }
}
