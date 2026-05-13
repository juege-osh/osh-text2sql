package com.osh.text2sql.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ConnectionProfile {
    @NotNull
    private DatasourceType type;

    private String host;

    private Integer port;

    private String database;

    private String username;

    private String password;

    private String baseUrl;

    private String bootstrapServers;

    private String securityProtocol;

    private String saslMechanism;
}
