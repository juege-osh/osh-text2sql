package com.osh.text2sql.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class QueryRequest {
    @NotBlank
    private String question;

    @NotNull
    private DatasourceType type;

    @NotNull
    private QueryMode mode;

    private String rawQuery;

    @Valid
    private ConnectionProfile connection;
}
