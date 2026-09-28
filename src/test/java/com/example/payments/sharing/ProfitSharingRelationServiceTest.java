package com.example.payments.sharing;

import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.ProfitSharingRelationBindRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProfitSharingRelationServiceTest {

    @Test
    void canonicalizesDouyinReceiverTypeAndKeepsBindingIsolatedByChannel() {
        ProfitSharingRelationService service = service();
        ProfitSharingRelationBindRequest bind = request(" merchant_id ", "douyin");

        service.recordBind(bind, response("douyin"));

        assertThat(service.isBound("douyin", "MERCHANT_ID", "receiver")).isTrue();
        assertThat(service.isBound("douyin", "merchant_id", "receiver")).isTrue();
        assertThat(service.isBound("alipay", "MERCHANT_ID", "receiver")).isFalse();
        assertThat(service.list("douyin").getFirst().receiverType()).isEqualTo("MERCHANT_ID");

        service.recordUnbind(request("MERCHANT_ID", "douyin"), response("douyin"));

        assertThat(service.isBound("douyin", "merchant_id", "receiver")).isFalse();
        assertThat(service.list("douyin")).hasSize(1);
    }

    @Test
    void leavesAlipayReceiverTypeAndDefaultUnchangedWhenDouyinIsUsed() {
        ProfitSharingRelationService service = service();
        service.recordBind(request(null, "alipay"), response("alipay"));
        service.recordBind(request("personal_openid", "douyin"), response("douyin"));

        assertThat(service.list("alipay").getFirst().receiverType()).isEqualTo("loginName");
        assertThat(service.list("douyin").getFirst().receiverType()).isEqualTo("PERSONAL_OPENID");
        assertThat(service.isBound("alipay", null, "receiver")).isTrue();

        service.recordUnbind(request("PERSONAL_OPENID", "douyin"), response("douyin"));

        assertThat(service.isBound("alipay", "loginName", "receiver")).isTrue();
    }

    private static ProfitSharingRelationService service() {
        return new ProfitSharingRelationService(new DefaultListableBeanFactory().getBeanProvider(JdbcTemplate.class));
    }

    private static ProfitSharingRelationBindRequest request(String type, String channel) {
        return new ProfitSharingRelationBindRequest("receiver", type, "Receiver", null,
                "RELATION-1", null, List.of(channel), Map.of());
    }

    private static GatewayResponse response(String channel) {
        return new GatewayResponse(channel, PaymentStatus.SUCCESS, "SUCCESS", "Success",
                null, null, null, null, Map.of(), List.of());
    }
}
