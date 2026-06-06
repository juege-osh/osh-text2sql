package com.osh.text2sql.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 导入知识库结果
 */
@Data
@Builder
public class KnowledgeImportResponse {

    private Long libId;

    private int indexCount;

    private String uploadedFileName;

    private String storePath;

    private List<String> importedIndices;

    private String message;
}
