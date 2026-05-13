package com.osh.text2sql.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ConnectionTestResponse {
    private boolean success;
    private String message;
    private long elapsedMs;
    private Object preview;
}
