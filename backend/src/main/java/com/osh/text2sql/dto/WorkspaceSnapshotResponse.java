package com.osh.text2sql.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class WorkspaceSnapshotResponse {
    private List<DatasourceOverview> datasources;
    private List<QuerySuggestion> suggestions;
}
