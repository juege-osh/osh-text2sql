package com.osh.text2sql.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 导入知识库请求参数
 */
@Data
public class KnowledgeImportRequest {

    @NotNull
    private Long libId;

    private ConnectionProfile connection;

    @NotBlank
    private String qaUsername;

    @NotBlank
    private String qaPassword;

    private String qaBaseUrl;

    private String module = "es-schema";
}
