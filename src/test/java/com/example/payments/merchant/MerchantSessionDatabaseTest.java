package com.example.payments.merchant;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantSessionDatabaseTest {
    @TempDir Path directory;

    @Test
    void persistedCredentialsDetermineSessionValidityAcrossServiceRestartWithoutOrderQueries() {
        DemoMerchantService memory = new DemoMerchantService();
        DemoMerchantView merchant = memory.create(new DemoMerchantCreateRequest("M1", "Merchant", null, null, null, null, null));
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        // Deliberately no order or routing tables: validation reads only credential columns.
        jdbc.execute("CREATE TABLE merchant (merchant_id VARCHAR(96) PRIMARY KEY, status VARCHAR(32), md5_key VARCHAR(128), rsa2_public_key CLOB, sign_mode VARCHAR(32))");
        jdbc.update("INSERT INTO merchant VALUES (?, ?, ?, ?, ?)", merchant.merchantId(), merchant.status(), merchant.md5Key(), merchant.rsa2PublicKey(), merchant.signMode());
        Path keys = directory.resolve("platform.properties");
        DemoMerchantService first = new DemoMerchantService(jdbc, keys);
        String fingerprint = DemoMerchantService.portalSessionFingerprint(merchant);
        assertThat(first.matchesPortalSession("M1", fingerprint)).isTrue();
        assertThat(new DemoMerchantService(jdbc, keys).matchesPortalSession("M1", fingerprint)).isTrue();
        jdbc.update("UPDATE merchant SET md5_key = ? WHERE merchant_id = ?", "rotated-credential", "M1");
        assertThat(first.matchesPortalSession("M1", fingerprint)).isFalse();
        assertThat(new DemoMerchantService(jdbc, keys).matchesPortalSession("M1", fingerprint)).isFalse();
        jdbc.update("UPDATE merchant SET md5_key = ?, status = ? WHERE merchant_id = ?", merchant.md5Key(), "禁用", "M1");
        assertThat(first.matchesPortalSession("M1", fingerprint)).isFalse();
        assertThat(first.matchesPortalSession("missing", fingerprint)).isFalse();
    }
}
