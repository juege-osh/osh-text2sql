package com.osh.text2sql.introspect;

import com.osh.text2sql.dto.ConnectionProfile;
import com.osh.text2sql.dto.ConnectionTestResponse;
import com.osh.text2sql.dto.DatasourceSchemaResponse;
import com.osh.text2sql.dto.DatasourceType;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.hbase.NamespaceDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptor;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.util.Bytes;
import org.springframework.stereotype.Component;

/**
 * HBase 结构探测器。
 */
@Component
public class HbaseIntrospector implements DatasourceIntrospector {

    private final HbaseSupport hbaseSupport;

    public HbaseIntrospector(HbaseSupport hbaseSupport) {
        this.hbaseSupport = hbaseSupport;
    }

    @Override
    public DatasourceSchemaResponse introspect(ConnectionProfile profile) {
        try (Connection connection = hbaseSupport.createConnection(profile);
             Admin admin = connection.getAdmin()) {
            String namespace = profile.getNamespace() == null || profile.getNamespace().isBlank() ? "default" : profile.getNamespace();
            TableName[] tableNames = admin.listTableNamesByNamespace(namespace);
            Map<String, Object> schema = new LinkedHashMap<>();
            for (TableName tableName : tableNames) {
                var descriptor = admin.getDescriptor(tableName);
                List<Map<String, Object>> columnFamilies = new ArrayList<>();
                for (ColumnFamilyDescriptor family : descriptor.getColumnFamilies()) {
                    columnFamilies.add(Map.of(
                        "family", family.getNameAsString(),
                        "maxVersions", family.getMaxVersions(),
                        "compression", family.getCompressionType().name()
                    ));
                }
                schema.put(tableName.getQualifierAsString(), Map.of(
                    "namespace", tableName.getNamespaceAsString(),
                    "columnFamilies", columnFamilies
                ));
            }
            return DatasourceSchemaResponse.builder()
                .type(DatasourceType.HBASE)
                .name(profile.getZookeeperQuorum())
                .summary("HBase 命名空间 %s，共 %d 张表".formatted(namespace, schema.size()))
                .schema(schema)
                .build();
        } catch (Exception exception) {
            throw new IllegalStateException("HBase 结构读取失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public ConnectionTestResponse test(ConnectionProfile profile) {
        long start = System.currentTimeMillis();
        try (Connection connection = hbaseSupport.createConnection(profile);
             Admin admin = connection.getAdmin()) {
            NamespaceDescriptor[] namespaces = admin.listNamespaceDescriptors();
            Map<String, Object> preview = new LinkedHashMap<>();
            preview.put("zookeeperQuorum", profile.getZookeeperQuorum());
            preview.put("znodeParent", profile.getZnodeParent());
            preview.put("namespace", profile.getNamespace());
            preview.put("namespaces", java.util.Arrays.stream(namespaces).map(NamespaceDescriptor::getName).sorted().toList());
            return ConnectionTestResponse.builder()
                .success(true)
                .message("HBase 连接成功")
                .elapsedMs(Duration.ofMillis(System.currentTimeMillis() - start).toMillis())
                .preview(preview)
                .build();
        } catch (IOException exception) {
            throw new IllegalStateException("HBase 连接失败: " + exception.getMessage(), exception);
        }
    }
}
