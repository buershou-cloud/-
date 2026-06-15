package com.example.payments.gateway;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.ComplaintQueryRequest;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.OnboardingRequest;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentCancelRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.PaymentQueryRequest;
import com.example.payments.domain.PreauthCaptureRequest;
import com.example.payments.domain.PreauthUnfreezeRequest;
import com.example.payments.domain.ProfitSharingRelationBindRequest;
import com.example.payments.domain.ProfitSharingRelationQueryRequest;
import com.example.payments.domain.ProfitSharingRequest;
import com.example.payments.domain.RefundCreateRequest;

public interface PaymentProvider {

    String providerCode();

    boolean supports(PaymentGatewayProperties.Channel channel, PaymentProduct product);

    GatewayResponse pay(PaymentGatewayProperties.Channel channel, PayCreateRequest request);

    GatewayResponse query(PaymentGatewayProperties.Channel channel, PaymentQueryRequest request);

    GatewayResponse cancel(PaymentGatewayProperties.Channel channel, PaymentCancelRequest request);

    GatewayResponse refund(PaymentGatewayProperties.Channel channel, RefundCreateRequest request);

    GatewayResponse preauthQuery(PaymentGatewayProperties.Channel channel, PaymentQueryRequest request);

    GatewayResponse preauthCapture(PaymentGatewayProperties.Channel channel, PreauthCaptureRequest request);

    GatewayResponse preauthUnfreeze(PaymentGatewayProperties.Channel channel, PreauthUnfreezeRequest request);

    GatewayResponse profitSharing(PaymentGatewayProperties.Channel channel, ProfitSharingRequest request);

    GatewayResponse bindProfitSharingRelation(PaymentGatewayProperties.Channel channel, ProfitSharingRelationBindRequest request);

    GatewayResponse queryProfitSharingRelations(PaymentGatewayProperties.Channel channel, ProfitSharingRelationQueryRequest request);

    GatewayResponse queryComplaints(PaymentGatewayProperties.Channel channel, ComplaintQueryRequest request);

    GatewayResponse onboard(PaymentGatewayProperties.Channel channel, OnboardingRequest request);
}
