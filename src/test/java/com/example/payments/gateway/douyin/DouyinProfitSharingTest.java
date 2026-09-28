package com.example.payments.gateway.douyin;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.domain.ProfitSharingFinishRequest;
import com.example.payments.domain.ProfitSharingQueryRequest;
import com.example.payments.domain.ProfitSharingRelationBindRequest;
import com.example.payments.domain.ProfitSharingRequest;
import com.example.payments.domain.ProfitSharingReturnQueryRequest;
import com.example.payments.domain.ProfitSharingReturnRequest;
import com.example.payments.gateway.GatewayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DouyinProfitSharingTest {

    private static final String ORDERS = "/v1/trade/profitsharing/orders";
    private static final String RETURNS = "/v1/trade/profitsharing/return-orders";
    private static final String FINISH = "/v1/trade/profitsharing/finish-orders";
    private static final String QUERY = ORDERS + "/SPLIT_1001?mchid=dy-mch-1&transaction_id=DY1001";
    private final DouyinPayClient client = mock(DouyinPayClient.class);
    private final DouyinPaymentProvider provider = new DouyinPaymentProvider(client);
    private final PaymentGatewayProperties.Channel channel = channel();

    @Test
    void submitsExactDirectMerchantRequestAndKeepsRemainingFundsFrozenByDefault() {
        when(client.postSensitive(any(), eq(ORDERS), anyMap()))
                .thenReturn(response(Map.of("state", "PROCESSING", "out_order_no", "SPLIT_1001")));

        GatewayResponse result = provider.profitSharing(channel, split("SPLIT_1001", List.of(receiver("1.23"))));

        assertThat(result.status()).isEqualTo(PaymentStatus.PENDING);
        ArgumentCaptor<Map<String, Object>> body = bodyCaptor();
        verify(client).postSensitive(eq(channel), eq(ORDERS), body.capture());
        assertThat(body.getValue()).isEqualTo(Map.of(
                "appid", "dy-app-1", "mchid", "dy-mch-1", "transaction_id", "DY1001",
                "out_order_no", "SPLIT_1001", "unfreeze_unsplit", false,
                "notify_url", "https://merchant.example.com/api/v1/douyin/notify/douyin-test",
                "receivers", List.of(Map.of("type", "PERSONAL_OPENID", "account", "receiver-openid",
                        "amount", 123L, "description", "合作方分账"))));
    }

    @Test
    void usesPlatformEncryptionForMerchantNameAndAllowsExplicitUnfreeze() {
        channel.getDouyin().setPlatformCertificate("platform-certificate");
        Map<String, Object> receiver = new LinkedHashMap<>(receiver("0.01"));
        receiver.put("trans_in_type", "MERCHANT_ID");
        receiver.put("receiver_name", "接收商户");
        when(client.postSensitive(any(), eq(ORDERS), anyMap()))
                .thenReturn(response(Map.of("state", "PROCESSING")));

        try (MockedStatic<DouyinSignatureSupport> encryption = mockStatic(DouyinSignatureSupport.class)) {
            encryption.when(() -> DouyinSignatureSupport.encryptSensitive("接收商户", "platform-certificate"))
                    .thenReturn("encrypted-merchant-name");
            provider.profitSharing(channel, new ProfitSharingRequest("ORDER-1001", "DY1001", "SPLIT_1001",
                    List.of(receiver), null, null, List.of("douyin-test"), Map.of("unfreeze_unsplit", true)));
            encryption.verify(() -> DouyinSignatureSupport.encryptSensitive("接收商户", "platform-certificate"));
        }

        ArgumentCaptor<Map<String, Object>> body = bodyCaptor();
        verify(client).postSensitive(eq(channel), eq(ORDERS), body.capture());
        assertThat(body.getValue()).containsEntry("unfreeze_unsplit", true);
        assertThat(receivers(body.getValue())).singleElement().satisfies(value -> assertThat(value)
                .containsEntry("type", "MERCHANT_ID").containsEntry("name", "encrypted-merchant-name")
                .containsEntry("amount", 1L));
    }

    @Test
    void requiresMerchantNameBeforeAttemptingEncryptionOrSendingMoney() {
        Map<String, Object> receiver = new LinkedHashMap<>(receiver("0.50"));
        receiver.put("trans_in_type", "MERCHANT_ID");
        assertThatThrownBy(() -> provider.profitSharing(channel, split("SPLIT_1001", List.of(receiver))))
                .isInstanceOf(GatewayException.class).hasMessageContaining("名称");
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.01", "0.001", "92233720368547758.08", "not-an-amount"})
    void rejectsInvalidReceiverAmountsBeforeCallingGateway(String amount) {
        assertThatThrownBy(() -> provider.profitSharing(channel, split("SPLIT_1001", List.of(receiver(amount)))))
                .isInstanceOf(GatewayException.class);
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"userId", "loginName", "ALIPAY_USER_ID", ""})
    void rejectsReceiverTypesBelongingToOtherPaymentProviders(String type) {
        Map<String, Object> receiver = new LinkedHashMap<>(receiver("0.50"));
        receiver.put("trans_in_type", type);
        assertThatThrownBy(() -> provider.profitSharing(channel, split("SPLIT_1001", List.of(receiver))))
                .isInstanceOf(GatewayException.class);
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "SPLIT/1001", "SPLIT?1001", "SPLIT_12345678901234567890123456789"})
    void rejectsInvalidSplitIdsForCreateAndQuery(String outOrderNo) {
        assertThatThrownBy(() -> provider.profitSharing(channel, split(outOrderNo, List.of(receiver("0.50")))))
                .isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> provider.queryProfitSharing(channel, query(outOrderNo)))
                .isInstanceOf(GatewayException.class);
        verifyNoInteractions(client);
    }

    @Test
    void rejectsMoreThanFiftyReceiversWithoutSubmittingAnyPartialRequest() {
        List<Map<String, Object>> receivers = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            Map<String, Object> receiver = new LinkedHashMap<>(receiver("0.01"));
            receiver.put("trans_in", "openid-" + i);
            receivers.add(receiver);
        }
        assertThatThrownBy(() -> provider.profitSharing(channel, split("SPLIT_1001", receivers)))
                .isInstanceOf(GatewayException.class);
        verifyNoInteractions(client);
    }

    @Test
    void createsSuccessfullyOnlyWhenEveryFinishedReceiverSucceeded() {
        when(client.postSensitive(any(), eq(ORDERS), anyMap()))
                .thenReturn(response(Map.of("data", Map.of("state", "FINISHED", "transaction_id", "DY1001",
                        "out_order_no", "SPLIT_1001", "receivers", List.of(
                                Map.of("result", "SUCCESS"), Map.of("result", "SUCCESS"))))));

        GatewayResponse result = provider.profitSharing(channel, split("SPLIT_1001", List.of(receiver("0.50"))));

        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(result.tradeNo()).isEqualTo("DY1001");
    }

    @Test
    void finishedWithoutReceiverResultsDoesNotClaimSplitSuccess() {
        when(client.postSensitive(any(), eq(ORDERS), anyMap())).thenReturn(response(Map.of("state", "FINISHED")));
        when(client.get(channel, QUERY)).thenReturn(response(Map.of("state", "FINISHED")));
        assertThat(provider.profitSharing(channel, split("SPLIT_1001", List.of(receiver("0.50")))).status())
                .isEqualTo(PaymentStatus.PENDING);
        assertThat(provider.queryProfitSharing(channel, query("SPLIT_1001")).status())
                .isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void finishedWithClosedReceiverReportsFailureAndReason() {
        when(client.get(channel, QUERY)).thenReturn(response(Map.of("data", Map.of(
                "state", "FINISHED", "transaction_id", "DY1001", "out_order_no", "SPLIT_1001",
                "receivers", List.of(Map.of("result", "SUCCESS"),
                        Map.of("result", "CLOSED", "fail_reason", "ACCOUNT_ABNORMAL"))))));

        GatewayResponse result = provider.queryProfitSharing(channel, query("SPLIT_1001"));

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.message()).contains("失败").doesNotContain("已受理");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.raw().get("data");
        assertThat(receivers(data)).anySatisfy(value -> assertThat(value)
                .containsEntry("result", "CLOSED").containsEntry("fail_reason", "ACCOUNT_ABNORMAL"));
        verify(client).get(channel, QUERY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "UNRECOGNIZED", ""})
    void finishedWithPendingOrUnknownReceiverRemainsPending(String result) {
        when(client.get(channel, QUERY)).thenReturn(response(Map.of("state", "FINISHED", "receivers",
                List.of(Map.of("result", "SUCCESS"), Map.of("result", result)))));
        assertThat(provider.queryProfitSharing(channel, query("SPLIT_1001")).status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void finishCanCompleteWithoutReceiversAndUsesItsOwnOrderNumber() {
        when(client.post(any(), eq(FINISH), anyMap()))
                .thenReturn(response(Map.of("data", Map.of("state", "FINISHED", "finish_amount", 150))));

        GatewayResponse result = provider.finishProfitSharing(channel, new ProfitSharingFinishRequest(
                "ORDER-1001", "DY1001", "FINISH_1001", "全部完成", List.of("douyin-test"), Map.of()));

        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
        ArgumentCaptor<Map<String, Object>> body = bodyCaptor();
        verify(client).post(eq(channel), eq(FINISH), body.capture());
        assertThat(body.getValue()).containsEntry("transaction_id", "DY1001")
                .containsEntry("out_order_no", "FINISH_1001").containsEntry("mchid", "dy-mch-1")
                .containsEntry("description", "全部完成");
    }

    @Test
    void remainingAmountQueryIsSuccessfulWithoutAStateField() {
        String path = "/v1/trade/profitsharing/order/DY1001/amounts?mchid=dy-mch-1";
        when(client.get(channel, path)).thenReturn(response(Map.of("data", Map.of(
                "mchid", "dy-mch-1", "transaction_id", "DY1001", "unsplit_amount", 123))));

        GatewayResponse result = provider.profitSharingRemainingAmount(channel, query("SPLIT_1001"));

        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(result.tradeNo()).isEqualTo("DY1001");
        verify(client).get(channel, path);
    }

    @Test
    void queryingFinishedUnfreezeDoesNotClaimRecipientsWerePaid() {
        when(client.get(channel, QUERY)).thenReturn(response(Map.of("state", "FINISHED",
                "finish_amount", 123, "finish_description", "解冻剩余资金")));

        GatewayResponse result = provider.queryProfitSharing(channel, query("SPLIT_1001"));

        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(result.raw()).containsEntry("profit_sharing_operation", "FINISH");
    }

    @Test
    void splitSuccessWithoutRecipientEvidenceRemainsUnconfirmed() {
        when(client.get(channel, QUERY)).thenReturn(response(Map.of("state", "SUCCESS")));
        assertThat(provider.queryProfitSharing(channel, query("SPLIT_1001")).status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void returnNumberCanHaveOneCharacterUnlikeSplitNumber() {
        when(client.post(any(), eq(RETURNS), anyMap())).thenReturn(response(Map.of("result", "PROCESSING")));
        String returnPath = RETURNS + "/R?mchid=dy-mch-1&out_order_no=SPLIT_1001";
        when(client.get(channel, returnPath)).thenReturn(response(Map.of("result", "SUCCESS")));

        assertThat(provider.returnProfitSharing(channel, new ProfitSharingReturnRequest(
                "SPLIT_1001", "R", "receiver-mch", new BigDecimal("0.12"), "退款回退",
                List.of("douyin-test"), Map.of())).status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(provider.queryProfitSharingReturn(channel, new ProfitSharingReturnQueryRequest(
                "SPLIT_1001", "R", List.of("douyin-test"), Map.of())).status()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "123456789012345678901234567890123"})
    void rejectsBlankOrOversizedReturnNumbers(String id) {
        assertThatThrownBy(() -> provider.returnProfitSharing(channel, new ProfitSharingReturnRequest(
                "SPLIT_1001", id, "receiver-mch", new BigDecimal("0.12"), "退款回退",
                List.of("douyin-test"), Map.of()))).isInstanceOf(GatewayException.class);
        verifyNoInteractions(client);
    }

    @Test
    void returnsFundsWithExactIntegerFenPayloadAndQueriesSameReturnId() {
        when(client.post(any(), eq(RETURNS), anyMap()))
                .thenReturn(response(Map.of("result", "PROCESSING", "out_return_no", "RETURN_1001")));
        String returnQuery = RETURNS + "/RETURN_1001?mchid=dy-mch-1&out_order_no=SPLIT_1001";
        when(client.get(channel, returnQuery))
                .thenReturn(response(Map.of("data", Map.of("result", "SUCCESS", "out_return_no", "RETURN_1001"))));

        GatewayResponse created = provider.returnProfitSharing(channel, new ProfitSharingReturnRequest(
                "SPLIT_1001", "RETURN_1001", "receiver-mch", new BigDecimal("0.12"), "退款回退",
                List.of("douyin-test"), Map.of()));
        GatewayResponse queried = provider.queryProfitSharingReturn(channel, new ProfitSharingReturnQueryRequest(
                "SPLIT_1001", "RETURN_1001", List.of("douyin-test"), Map.of()));

        assertThat(created.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(queried.status()).isEqualTo(PaymentStatus.SUCCESS);
        ArgumentCaptor<Map<String, Object>> body = bodyCaptor();
        verify(client).post(eq(channel), eq(RETURNS), body.capture());
        assertThat(body.getValue()).isEqualTo(Map.of("mchid", "dy-mch-1", "out_order_no", "SPLIT_1001",
                "out_return_no", "RETURN_1001", "return_mchid", "receiver-mch", "amount", 12L,
                "description", "退款回退"));
        verify(client).get(channel, returnQuery);
    }

    @Test
    void returnFailureUsesBusinessResultInsteadOfSuccessfulHttpStatus() {
        when(client.post(any(), eq(RETURNS), anyMap())).thenReturn(response(Map.of("data", Map.of(
                "result", "FAILED", "fail_reason", "BALANCE_NOT_ENOUGH"))));
        GatewayResponse result = provider.returnProfitSharing(channel, new ProfitSharingReturnRequest(
                "SPLIT_1001", "RETURN_1001", "receiver-mch", new BigDecimal("0.12"), "退款回退",
                List.of("douyin-test"), Map.of()));
        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.message()).contains("BALANCE_NOT_ENOUGH").doesNotContain("已受理");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.01", "0.001", "92233720368547758.08"})
    void rejectsInvalidReturnAmountBeforeCallingGateway(String amount) {
        assertThatThrownBy(() -> provider.returnProfitSharing(channel, new ProfitSharingReturnRequest(
                "SPLIT_1001", "RETURN_1001", "receiver-mch", new BigDecimal(amount), "退款回退",
                List.of("douyin-test"), Map.of()))).isInstanceOf(GatewayException.class);
        verifyNoInteractions(client);
    }

    @Test
    void preservesPlaintextReceiverNameLocallyWhenGatewayReturnsCiphertext() {
        channel.getDouyin().setPlatformCertificate("platform-certificate");
        String addPath = "/v1/trade/profitsharing/receivers/add";
        when(client.postSensitive(any(), eq(addPath), anyMap()))
                .thenReturn(response(Map.of("type", "MERCHANT_ID", "account", "receiver-mch", "name", "response-ciphertext")));

        try (MockedStatic<DouyinSignatureSupport> encryption = mockStatic(DouyinSignatureSupport.class)) {
            encryption.when(() -> DouyinSignatureSupport.encryptSensitive("接收商户", "platform-certificate"))
                    .thenReturn("request-ciphertext");
            GatewayResponse result = provider.bindProfitSharingRelation(channel, new ProfitSharingRelationBindRequest(
                    "receiver-mch", "MERCHANT_ID", "接收商户", null, "REL_1001", null,
                    List.of("douyin-test"), Map.of("relation_type", "PARTNER")));
            assertThat(result.raw()).containsEntry("name", "接收商户");
        }
        ArgumentCaptor<Map<String, Object>> body = bodyCaptor();
        verify(client).postSensitive(eq(channel), eq(addPath), body.capture());
        assertThat(body.getValue()).containsEntry("name", "request-ciphertext");
    }

    @ParameterizedTest
    @EnumSource(value = PaymentProduct.class, names = {"DOUYIN_H5", "DOUYIN_NATIVE"})
    void paymentDefaultsToProfitSharingAndPreservesExplicitChoice(PaymentProduct product) {
        String path = product == PaymentProduct.DOUYIN_H5 ? "/v1/trade/transactions/h5" : "/v1/trade/transactions/native";
        String urlKey = product == PaymentProduct.DOUYIN_H5 ? "h5_url" : "code_url";
        when(client.post(any(), eq(path), anyMap())).thenReturn(response(Map.of(urlKey, "https://pay.example.com/order")));

        provider.pay(channel, pay(product, null));
        provider.pay(channel, pay(product, Map.of("profit_sharing", false)));
        provider.pay(channel, pay(product, Map.of("profit_sharing", true)));

        ArgumentCaptor<Map<String, Object>> body = bodyCaptor();
        verify(client, org.mockito.Mockito.times(3)).post(eq(channel), eq(path), body.capture());
        assertThat(body.getAllValues().get(0)).containsEntry("settle_info", Map.of("profit_sharing", true));
        assertThat(body.getAllValues().get(1)).containsEntry("settle_info", Map.of("profit_sharing", false));
        assertThat(body.getAllValues().get(2)).containsEntry("settle_info", Map.of("profit_sharing", true));
    }

    @ParameterizedTest
    @EnumSource(value = PaymentProduct.class, names = {"DOUYIN_H5", "DOUYIN_NATIVE"})
    void defaultSharingPreservesSettlementFieldsWithoutMutatingRequest(PaymentProduct product) {
        String path = product == PaymentProduct.DOUYIN_H5 ? "/v1/trade/transactions/h5" : "/v1/trade/transactions/native";
        String urlKey = product == PaymentProduct.DOUYIN_H5 ? "h5_url" : "code_url";
        when(client.post(any(), eq(path), anyMap())).thenReturn(response(Map.of(urlKey, "https://pay.example.com/order")));
        Map<String, Object> settlement = Map.of("other_option", "original");
        PayCreateRequest request = pay(product, settlement);

        provider.pay(channel, request);

        ArgumentCaptor<Map<String, Object>> body = bodyCaptor();
        verify(client).post(eq(channel), eq(path), body.capture());
        assertThat(body.getValue()).containsEntry("settle_info", Map.of("other_option", "original", "profit_sharing", true));
        assertThat(body.getValue().get("settle_info")).isNotSameAs(settlement);
        assertThat(request.settleInfo()).containsExactlyEntriesOf(Map.of("other_option", "original"));
        assertThat(request.extra()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentProduct.class, names = {"DOUYIN_H5", "DOUYIN_NATIVE"})
    void extraSettlementStillOverridesTypedSettlementIncludingExplicitFalse(PaymentProduct product) {
        String path = product == PaymentProduct.DOUYIN_H5 ? "/v1/trade/transactions/h5" : "/v1/trade/transactions/native";
        String urlKey = product == PaymentProduct.DOUYIN_H5 ? "h5_url" : "code_url";
        when(client.post(any(), eq(path), anyMap())).thenReturn(response(Map.of(urlKey, "https://pay.example.com/order")));
        Map<String, Object> typedSettlement = Map.of("profit_sharing", true, "typed_only", "typed");
        Map<String, Object> extraSettlement = Map.of("profit_sharing", false, "other_option", "extra");
        Map<String, Object> extra = Map.of("settle_info", extraSettlement, "attach", "cashier-order");
        PayCreateRequest request = pay(product, typedSettlement, extra);

        provider.pay(channel, request);

        ArgumentCaptor<Map<String, Object>> body = bodyCaptor();
        verify(client).post(eq(channel), eq(path), body.capture());
        assertThat(body.getValue()).containsEntry("settle_info", extraSettlement).containsEntry("attach", "cashier-order");
        assertThat(body.getValue().get("settle_info")).isNotSameAs(extraSettlement);
        assertThat(request.settleInfo()).containsExactlyEntriesOf(typedSettlement);
        assertThat(request.extra()).containsExactlyEntriesOf(extra);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentProduct.class, names = {"DOUYIN_H5", "DOUYIN_NATIVE"})
    void defaultSharingUsesFinalExtraSettlementWithoutMergingTypedFields(PaymentProduct product) {
        String path = product == PaymentProduct.DOUYIN_H5 ? "/v1/trade/transactions/h5" : "/v1/trade/transactions/native";
        String urlKey = product == PaymentProduct.DOUYIN_H5 ? "h5_url" : "code_url";
        when(client.post(any(), eq(path), anyMap())).thenReturn(response(Map.of(urlKey, "https://pay.example.com/order")));
        Map<String, Object> typedSettlement = Map.of("profit_sharing", false, "typed_only", "typed");
        Map<String, Object> extraSettlement = Map.of("other_option", "extra");
        Map<String, Object> extra = Map.of("settle_info", extraSettlement);
        PayCreateRequest request = pay(product, typedSettlement, extra);

        provider.pay(channel, request);

        ArgumentCaptor<Map<String, Object>> body = bodyCaptor();
        verify(client).post(eq(channel), eq(path), body.capture());
        assertThat(body.getValue()).containsEntry("settle_info", Map.of("other_option", "extra", "profit_sharing", true));
        assertThat(request.settleInfo()).containsExactlyEntriesOf(typedSettlement);
        assertThat(extraSettlement).containsExactlyEntriesOf(Map.of("other_option", "extra"));
        assertThat(request.extra()).containsExactlyEntriesOf(extra);
    }

    private static ProfitSharingRequest split(String id, List<Map<String, Object>> receivers) {
        return new ProfitSharingRequest("ORDER-1001", "DY1001", id, receivers, null, null, List.of("douyin-test"), Map.of());
    }

    private static ProfitSharingQueryRequest query(String id) {
        return new ProfitSharingQueryRequest("ORDER-1001", "DY1001", id, null, List.of("douyin-test"), Map.of());
    }

    private static Map<String, Object> receiver(String amount) {
        return Map.of("trans_in_type", "PERSONAL_OPENID", "trans_in", "receiver-openid", "amount", amount, "desc", "合作方分账");
    }

    private static DouyinGatewayResponse response(Map<String, Object> body) {
        return new DouyinGatewayResponse(200, body, "{}", Map.of());
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> bodyCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> receivers(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("receivers");
    }

    private static PayCreateRequest pay(PaymentProduct product, Map<String, Object> settleInfo) {
        return pay(product, settleInfo, Map.of());
    }

    private static PayCreateRequest pay(PaymentProduct product, Map<String, Object> settleInfo, Map<String, Object> extra) {
        return new PayCreateRequest(product, "ORDER-1001", "测试商品", new BigDecimal("2.00"),
                null, null, null, null, "10m", null, "https://merchant.example.com/return", null,
                null, List.of("douyin-test"), extra, settleInfo, null);
    }

    private static PaymentGatewayProperties.Channel channel() {
        PaymentGatewayProperties.Channel channel = new PaymentGatewayProperties.Channel();
        channel.setId("douyin-test");
        channel.setProvider("DOUYIN");
        channel.getDouyin().setAppId("dy-app-1");
        channel.getDouyin().setMchId("dy-mch-1");
        channel.getDouyin().setNotifyUrl("https://merchant.example.com/api/v1/douyin/notify/douyin-test");
        return channel;
    }
}
