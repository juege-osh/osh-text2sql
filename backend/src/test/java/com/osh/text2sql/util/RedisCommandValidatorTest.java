package com.osh.text2sql.util;

import com.osh.text2sql.exception.BadRequestException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class RedisCommandValidatorTest {

    @Test
    void shouldAcceptGet() {
        Assertions.assertEquals("GET", RedisCommandValidator.validate("GET foo").get(0).toUpperCase());
    }

    @Test
    void shouldRejectSet() {
        Assertions.assertThrows(BadRequestException.class, () -> RedisCommandValidator.validate("SET foo bar"));
    }

    @Test
    void shouldDefaultScanCursor() {
        Assertions.assertEquals(List.of("SCAN", "0"), RedisCommandValidator.validate("SCAN"));
    }

    @Test
    void shouldRejectKeys() {
        Assertions.assertThrows(BadRequestException.class, () -> RedisCommandValidator.validate("KEYS *"));
    }
}
