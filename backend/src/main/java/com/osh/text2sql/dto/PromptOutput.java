package com.osh.text2sql.dto;

import lombok.Data;

@Data
public class PromptOutput {
    private Object query;
    private String reasoning;
    private String safetyNotes;
}
