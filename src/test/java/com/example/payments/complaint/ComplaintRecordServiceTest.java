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

    @Test
    void extractsOfficialSecurityRiskBatchQueryComplaintList() {
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
                Map.of("alipay_security_risk_complaint_info_batchquery_response", Map.of(
                        "code", "10000",
                        "msg", "Success",
                        "current_page", 1,
                        "page_size", 10,
                        "total_size", 1,
                        "complaint_list", List.of(Map.of(
                                "id", 1000000001L,
                                "task_id", "TSK20260529001",
                                "trade_no", "2026052922001400000001",
                                "status", "WAIT_PROCESS",
                                "status_description", "待商家处理",
                                "complain_content", "用户投诉未收到服务",
                                "gmt_complain", "2026-05-29 10:00:00",
                                "complaint_trade_info_list", List.of(Map.of(
                                        "id", "9001",
                                        "complaint_record_id", "1000000001",
                                        "out_no", "ORDER20260529001",
                                        "trade_no", "2026052922001400000001",
                                        "status", "WAIT_PROCESS",
                                        "gmt_trade", "2026-05-29 09:50:00"
                                ))
                        ))
                )),
                List.of()
        );

        List<ComplaintRecordService.ComplaintRecordView> records = service.record(response);

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().complaintId()).isEqualTo("TSK20260529001");
        assertThat(records.getFirst().outTradeNo()).isEqualTo("ORDER20260529001");
        assertThat(records.getFirst().tradeNo()).isEqualTo("2026052922001400000001");
        assertThat(records.getFirst().status()).isEqualTo("WAIT_PROCESS");
        assertThat(records.getFirst().content()).isEqualTo("用户投诉未收到服务");
    }

    @Test
    void extractsOfficialSecurityRiskComplaintDetail() {
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
                Map.of("alipay_security_risk_complaint_info_query_response", Map.ofEntries(
                        Map.entry("code", "10000"),
                        Map.entry("msg", "Success"),
                        Map.entry("id", 1000000001L),
                        Map.entry("task_id", "TSK20260529001"),
                        Map.entry("trade_no", "2026052922001400000001"),
                        Map.entry("status", "WAIT_PROCESS"),
                        Map.entry("status_description", "待商家处理"),
                        Map.entry("complain_content", "用户投诉未收到服务"),
                        Map.entry("upgrade_content", "用户补充投诉内容"),
                        Map.entry("opposite_pid", "2088123456789012"),
                        Map.entry("opposite_name", "演示商户"),
                        Map.entry("gmt_complain", "2026-05-29 10:00:00"),
                        Map.entry("complaint_trade_info_list", List.of(Map.of(
                                "complaint_record_id", "1000000001",
                                "out_no", "ORDER20260529001",
                                "trade_no", "2026052922001400000001",
                                "status", "WAIT_PROCESS",
                                "gmt_trade", "2026-05-29 09:50:00"
                        )))
                )),
                List.of()
        );

        List<ComplaintRecordService.ComplaintRecordView> records = service.record(response);

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().complaintId()).isEqualTo("TSK20260529001");
        assertThat(records.getFirst().merchantId()).isEqualTo("2088123456789012");
        assertThat(records.getFirst().outTradeNo()).isEqualTo("ORDER20260529001");
        assertThat(records.getFirst().content()).isEqualTo("用户投诉未收到服务");
    }

    private ComplaintRecordService service() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        return new ComplaintRecordService(beanFactory.getBeanProvider(JdbcTemplate.class), new ObjectMapper());
    }
}
