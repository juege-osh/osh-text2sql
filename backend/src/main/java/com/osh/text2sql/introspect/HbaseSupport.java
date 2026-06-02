package com.osh.text2sql.introspect;

import com.osh.text2sql.dto.ConnectionProfile;
import java.io.IOException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * HBase 连接支持工具。
 */
@Component
public class HbaseSupport {

    public Connection createConnection(ConnectionProfile profile) throws IOException {
        return ConnectionFactory.createConnection(createConfiguration(profile));
    }

    public Configuration createConfiguration(ConnectionProfile profile) {
        Configuration configuration = HBaseConfiguration.create();
        configuration.set("hbase.zookeeper.quorum", profile.getZookeeperQuorum());
        configuration.setInt("hbase.zookeeper.property.clientPort", profile.getZookeeperClientPort() == null ? 2181 : profile.getZookeeperClientPort());
        configuration.set("zookeeper.znode.parent", profile.getZnodeParent() == null ? "/hbase" : profile.getZnodeParent());
        configuration.setInt("hbase.rpc.timeout", 8000);
        configuration.setInt("hbase.client.operation.timeout", 10000);
        configuration.setInt("hbase.client.scanner.timeout.period", 10000);
        return configuration;
    }
}
