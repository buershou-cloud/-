package com.example.payments.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "payment.database.enabled", havingValue = "true")
public class DatabaseConfig {

    @Bean(destroyMethod = "close")
    @ConfigurationProperties("payment.database.pool")
    public HikariDataSource paymentDataSource(
            @Value("${payment.database.url:}") String url,
            @Value("${payment.database.username:}") String username,
            @Value("${payment.database.password:}") String password,
            @Value("${payment.database.driver-class-name:com.mysql.cj.jdbc.Driver}") String driverClassName
    ) {
        if (!hasText(url)) {
            throw new IllegalStateException("payment.database.url is required when payment.database.enabled=true");
        }
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("payment-database");
        dataSource.setMaximumPoolSize(10);
        dataSource.setMinimumIdle(2);
        dataSource.setDriverClassName(driverClassName);
        dataSource.setJdbcUrl(url.trim());
        dataSource.setUsername(username == null ? "" : username.trim());
        dataSource.setPassword(password == null ? "" : password);
        return dataSource;
    }

    @Bean
    public JdbcTemplate paymentJdbcTemplate(DataSource paymentDataSource) {
        return new JdbcTemplate(paymentDataSource);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
