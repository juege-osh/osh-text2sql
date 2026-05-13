package com.osh.text2sql.executor;

import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.QueryExecutionResult;

public interface QueryExecutor {

    QueryExecutionResult execute(ConnectionProfile profile, String query);
}
