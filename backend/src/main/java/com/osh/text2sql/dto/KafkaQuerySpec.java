package com.osh.text2sql.dto;

import lombok.Data;

@Data
public class KafkaQuerySpec {
    private String operation;
    private Integer limit;
    private Boolean includeInternal;
    private String topic;
    private Integer partition;
    private Long offset;
    private String from;
    private String keyContains;
    private String valueContains;
}
