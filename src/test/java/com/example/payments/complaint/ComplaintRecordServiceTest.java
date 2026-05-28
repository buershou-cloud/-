package com.example.payments.complaint;

import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PaymentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ComplaintRecordServiceTest {

    @Test
    void successfulEmptyQueryDoesNotCreateSyntheticRecord() {
        ComplaintRecordService service = service();
        GatewayResponse response = new GatewayResponse(
                "ali-main",
                PaymentStatus.SUCCESS,
                "10000",
                "Success",
                null,
                null,
                null,
                null,
                Map.of("alipay_merchant_tradecomplain_batchquery_response", Map.of(
                        "code", "10000",
                        "msg", "Success"
                )),
                List.of()
        );

        assertThat(service.record(response)).isEmpty();
        assertThat(service.list(10)).isEmpty();
    }

    @Test
    void extractsNestedComplaintContentFromAlipayResponse() {
        ComplaintRecordService service = service();
        GatewayResponse response = new GatewayResponse(
                "ali-main",
                PaymentStatus.SUCCESS,
                "10000",
                "Success",
                null,
                null,
                null,
                null,
                Map.of("alipay_merchant_tradecomplain_query_response", Map.of(
                        "code", "10000",
                        "msg", "Success",
                        "complain_event_id", "C20260528001",
                        "alipay_trade_no", "2026052822001400000001",
                        "complain_status", "WAIT_PROCESS",
                        "message_info", Map.of("complain_content", "买家反馈未收到服务")
                )),
                List.of()
        );

        List<ComplaintRecordService.ComplaintRecordView> records = service.record(response);

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().complaintId()).isEqualTo("C20260528001");
        assertThat(records.getFirst().tradeNo()).isEqualTo("2026052822001400000001");
        assertThat(records.getFirst().content()).isEqualTo("买家反馈未收到服务");
    }

    private ComplaintRecordService service() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        return new ComplaintRecordService(beanFactory.getBeanProvider(JdbcTemplate.class), new ObjectMapper());
    }
}
