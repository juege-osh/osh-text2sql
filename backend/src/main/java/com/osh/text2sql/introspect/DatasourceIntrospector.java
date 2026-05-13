package com.osh.text2sql.introspect;

import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.ConnectionTestResponse;
import com.osh.text2sql.dto.DatasourceSchemaResponse;

public interface DatasourceIntrospector {

    DatasourceSchemaResponse introspect(ConnectionProfile profile);

    ConnectionTestResponse test(ConnectionProfile profile);
}
