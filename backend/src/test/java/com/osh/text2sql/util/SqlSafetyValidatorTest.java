package com.osh.text2sql.util;

import com.osh.text2sql.exception.BadRequestException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SqlSafetyValidatorTest {

    @Test
    void shouldAppendLimitWhenMissing() {
        String sql = SqlSafetyValidator.validateSelectQuery("select * from app", 100);
        Assertions.assertTrue(sql.toLowerCase().contains("limit 100"));
    }

    @Test
    void shouldRejectDelete() {
        Assertions.assertThrows(BadRequestException.class,
            () -> SqlSafetyValidator.validateSelectQuery("delete from app", 100));
    }
}
