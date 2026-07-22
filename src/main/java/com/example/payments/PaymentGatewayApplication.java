package com.example.payments;

import com.example.payments.config.PaymentGatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties(PaymentGatewayProperties.class)
public class PaymentGatewayApplication {

    public static void main(String[] args) {
        if (System.getProperty("java.net.preferIPv4Stack") == null
                && System.getProperty("java.net.preferIPv6Addresses") == null) {
            System.setProperty("java.net.preferIPv4Stack", "true");
        }
        SpringApplication.run(PaymentGatewayApplication.class, args);
    }
}
