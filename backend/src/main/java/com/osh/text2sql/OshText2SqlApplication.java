package com.osh.text2sql;

import com.osh.text2sql.config.Text2SqlProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(Text2SqlProperties.class)
public class OshText2SqlApplication {

    public static void main(String[] args) {
        SpringApplication.run(OshText2SqlApplication.class, args);
    }
}
