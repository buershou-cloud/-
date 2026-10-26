package com.example.payments.merchant;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.RoutingMode;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class MerchantAndChannelPoolTest {
    @TempDir Path directory;

    @Test
    void merchantDetailAndListWorkWithOneConnectionAndRoutingDoesNotReadOrders() {
        try (HikariDataSource source = singleConnectionPool()) {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                    CREATE TABLE merchant (merchant_id VARCHAR(64) PRIMARY KEY, name VARCHAR(128), fee_rate DECIMAL(8,4),
                        status VARCHAR(32), settlement_status VARCHAR(32), md5_key VARCHAR(128), platform_public_key CLOB,
                        rsa2_public_key CLOB, rsa2_private_key CLOB, sign_mode VARCHAR(32), routing_mode VARCHAR(32))
                    """);
            jdbc.execute("CREATE TABLE pay_order (merchant_id VARCHAR(64), amount DECIMAL(18,2), status VARCHAR(32))");
            jdbc.execute("CREATE TABLE merchant_channel (merchant_id VARCHAR(64), channel_id VARCHAR(64), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            jdbc.update("""
                    INSERT INTO merchant (merchant_id, name, fee_rate, status, md5_key, routing_mode)
                    VALUES ('M1', 'First', 0.60, 'ACTIVE', 'key-one', 'PRIORITY'), ('M2', 'Second', 0.50, 'ACTIVE', 'key-two', 'ROUND_ROBIN')
                    """);
            jdbc.update("INSERT INTO pay_order VALUES ('M1', 10.00, 'COMPLETED'), ('M1', 5.00, 'FROZEN'), ('M1', 999.00, 'UNPAID'), ('M2', 3.75, 'COMPLETED')");
            jdbc.update("INSERT INTO merchant_channel (merchant_id, channel_id) VALUES ('M1', 'ali'), ('M1', 'dy'), ('M2', 'dy')");
            var merchants = new DemoMerchantService(jdbc, directory.resolve("platform.properties"));

            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                DemoMerchantView first = merchants.detail("M1");
                assertThat(first.todayAmount()).isEqualByComparingTo("15.00");
                assertThat(first.channelIds()).containsExactlyInAnyOrder("ali", "dy");
                assertThat(first.md5Key()).isEqualTo("key-one");
                assertThat(first.routingMode()).isEqualTo(RoutingMode.PRIORITY);
                var listed = merchants.list();
                assertThat(listed).extracting(DemoMerchantView::merchantId).containsExactly("M1", "M2");
                assertThat(listed.get(0)).isEqualTo(first);
                assertThat(listed.get(1).todayAmount()).isEqualByComparingTo("3.75");
                assertThat(listed.get(1).channelIds()).containsExactly("dy");
                jdbc.execute("DROP TABLE pay_order");
                MerchantRouting routing = merchants.routing("M1");
                assertThat(routing.channelIds()).containsExactlyInAnyOrder("ali", "dy");
                assertThat(routing.routingMode()).isEqualTo(RoutingMode.PRIORITY);
                assertThatThrownBy(() -> merchants.routing("missing")).isInstanceOf(IllegalArgumentException.class);
            });
            assertThat(source.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }

    @Test
    void channelLoadingAndRefreshUseOneConnectionAndPreserveProductSets() {
        try (HikariDataSource source = singleConnectionPool()) {
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                    CREATE TABLE pay_channel (
                        id VARCHAR(64) PRIMARY KEY, provider VARCHAR(32), enabled BOOLEAN DEFAULT TRUE,
                        daily_enabled BOOLEAN DEFAULT TRUE, priority INT DEFAULT 100, weight INT DEFAULT 1,
                        pay_min DECIMAL(18,2) DEFAULT 0.01, pay_max DECIMAL(18,2) DEFAULT 50000,
                        gateway_url VARCHAR(255), app_id VARCHAR(64), alipay_public_key CLOB, merchant_private_key CLOB,
                        credential_mode VARCHAR(32), app_cert_sn VARCHAR(128), alipay_cert_sn VARCHAR(128), alipay_root_cert_sn VARCHAR(512),
                        app_cert_content CLOB, alipay_cert_content CLOB, alipay_root_cert_content CLOB,
                        app_auth_token CLOB, sub_merchant_id VARCHAR(64), notify_url VARCHAR(512), return_url VARCHAR(512),
                        charset_name VARCHAR(32), sign_type VARCHAR(16), douyin_gateway_url VARCHAR(255),
                        douyin_app_id VARCHAR(64), douyin_mch_id VARCHAR(64), douyin_merchant_serial_no VARCHAR(128),
                        douyin_merchant_certificate CLOB, douyin_merchant_private_key CLOB, douyin_platform_certificate CLOB,
                        douyin_encrypt_key VARCHAR(128), douyin_notify_url VARCHAR(512), douyin_return_url VARCHAR(512), douyin_h5_app_name VARCHAR(127))
                    """);
            jdbc.execute("CREATE TABLE pay_channel_product (channel_id VARCHAR(64), product VARCHAR(64))");
            jdbc.update("INSERT INTO pay_channel (id, provider, priority, weight, app_id) VALUES ('ali', 'ALIPAY', 1, 3, 'app-one'), ('dy', 'DOUYIN', 2, 1, '')");
            jdbc.update("INSERT INTO pay_channel_product VALUES ('ali', 'ALIPAY_WAP'), ('ali', 'ALIPAY_PAGE'), ('ali', 'UNKNOWN_OLD_PRODUCT'), ('dy', 'DOUYIN_H5')");
            StaticListableBeanFactory beans = new StaticListableBeanFactory();
            beans.addBean("jdbcTemplate", jdbc);

            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                var registry = new ChannelRegistry(new PaymentGatewayProperties(), beans.getBeanProvider(JdbcTemplate.class));
                assertThat(registry.all()).extracting(PaymentGatewayProperties.Channel::getId).containsExactly("ali", "dy");
                var alipay = registry.find("ali").orElseThrow();
                assertThat(alipay.getProducts()).containsExactlyInAnyOrder(PaymentProduct.ALIPAY_WAP, PaymentProduct.ALIPAY_PAGE);
                assertThat(alipay.getWeight()).isEqualTo(3);
                assertThat(alipay.getAlipay().getAppId()).isEqualTo("app-one");
                assertThat(registry.find("dy").orElseThrow().getProducts()).containsExactly(PaymentProduct.DOUYIN_H5);
                jdbc.update("INSERT INTO pay_channel_product VALUES ('ali', 'ALIPAY_APP')");
                assertThat(registry.find("ali").orElseThrow().getProducts()).contains(PaymentProduct.ALIPAY_APP);
            });
            assertThat(source.getHikariPoolMXBean().getActiveConnections()).isZero();
        }
    }

    private static HikariDataSource singleConnectionPool() {
        HikariDataSource source = new HikariDataSource();
        source.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        source.setMaximumPoolSize(1);
        source.setMinimumIdle(0);
        source.setConnectionTimeout(500);
        return source;
    }
}
