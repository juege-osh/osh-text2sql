package com.osh.text2sql.util;

import com.osh.text2sql.dto.KafkaQuerySpec;
import com.osh.text2sql.exception.BadRequestException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class KafkaQueryValidatorTest {

    @Test
    void shouldNormalizeListTopicsSpec() {
        KafkaQuerySpec spec = KafkaQueryValidator.validate("""
            {
              "operation": "list_topics"
            }
            """);

        Assertions.assertEquals("LIST_TOPICS", spec.getOperation());
        Assertions.assertEquals(20, spec.getLimit());
        Assertions.assertFalse(spec.getIncludeInternal());
    }

    @Test
    void shouldNormalizeReadMessagesSpec() {
        KafkaQuerySpec spec = KafkaQueryValidator.validate("""
            {
              "operation": "read_messages",
              "topic": "user-action"
            }
            """);

        Assertions.assertEquals("READ_MESSAGES", spec.getOperation());
        Assertions.assertEquals("LATEST", spec.getFrom());
        Assertions.assertEquals(10, spec.getLimit());
        Assertions.assertEquals("user-action", spec.getTopic());
    }

    @Test
    void shouldRequireTopicForReadMessages() {
        Assertions.assertThrows(BadRequestException.class, () -> KafkaQueryValidator.validate("""
            {
              "operation": "READ_MESSAGES"
            }
            """));
    }

    @Test
    void shouldRequireOffsetWhenFromOffset() {
        Assertions.assertThrows(BadRequestException.class, () -> KafkaQueryValidator.validate("""
            {
              "operation": "READ_MESSAGES",
              "topic": "user-action",
              "from": "OFFSET"
            }
            """));
    }

    @Test
    void shouldNormalizeCountUnconsumedMessagesWithKeyFilter() {
        KafkaQuerySpec spec = KafkaQueryValidator.validate("""
            {
              "operation": "count_unconsumed_messages",
              "topic": "user-action",
              "consumerGroup": "user-action-group",
              "keyContains": "tool-1001"
            }
            """);

        Assertions.assertEquals("COUNT_UNCONSUMED_MESSAGES", spec.getOperation());
        Assertions.assertEquals("user-action", spec.getTopic());
        Assertions.assertEquals("user-action-group", spec.getConsumerGroup());
        Assertions.assertEquals("tool-1001", spec.getKeyContains());
    }
}
