package com.example.payments.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseConfigTest {
    @Test
    void reusesConnectionsForSequentialQueriesAndClosesPool() {
        String url = "jdbc:h2:mem:pool_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        DriverManagerDataSource oldDataSource = new DriverManagerDataSource(url, "sa", "");
        HikariDataSource pool = new DatabaseConfig().paymentDataSource(url, "sa", "", "org.h2.Driver");
        pool.setMaximumPoolSize(1);
        pool.setMinimumIdle(1);
        try (pool) {
            assertThat(sessionIds(oldDataSource, 25)).hasSize(25);
            assertThat(sessionIds(pool, 25)).hasSize(1);
            assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isEqualTo(1);
        }
        assertThat(pool.isClosed()).isTrue();
    }

    @Test
    void pooledConnectionKeepsTransactionRollbackAndAutoCommitIsolation() {
        String url = "jdbc:h2:mem:tx_" + UUID.randomUUID();
        try (HikariDataSource pool = new DatabaseConfig().paymentDataSource(url, "sa", "", "org.h2.Driver")) {
            pool.setMaximumPoolSize(1);
            pool.setMinimumIdle(1);
            JdbcTemplate jdbc = new JdbcTemplate(pool);
            jdbc.execute("CREATE TABLE receipts (id INT PRIMARY KEY)");
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(pool));
            tx.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO receipts VALUES (1)");
                status.setRollbackOnly();
            });
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receipts", Integer.class)).isZero();
            jdbc.update("INSERT INTO receipts VALUES (2)");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receipts", Integer.class)).isEqualTo(1);
        }
    }

    private static Set<Long> sessionIds(DataSource source, int queries) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Set<Long> sessions = new HashSet<>();
        for (int i = 0; i < queries; i++) sessions.add(jdbc.queryForObject("CALL SESSION_ID()", Long.class));
        return sessions;
    }
}
