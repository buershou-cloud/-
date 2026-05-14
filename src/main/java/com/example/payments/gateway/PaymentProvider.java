package com.example.payments.gateway;

import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.ComplaintQueryRequest;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.OnboardingRequest;
import com.example.payments.domain.PayCreateRequest;
import com.example.payments.domain.PaymentProduct;
import com.example.payments.domain.PaymentQueryRequest;
import com.example.payments.domain.ProfitSharingRequest;
import com.example.payments.domain.RefundCreateRequest;

public interface PaymentProvider {

    String providerCode();

    boolean supports(PaymentGatewayProperties.Channel channel, PaymentProduct product);

    GatewayResponse pay(PaymentGatewayProperties.Channel channel, PayCreateRequest request);

    GatewayResponse query(PaymentGatewayProperties.Channel channel, PaymentQueryRequest request);

    GatewayResponse refund(PaymentGatewayProperties.Channel channel, RefundCreateRequest request);

    GatewayResponse profitSharing(PaymentGatewayProperties.Channel channel, ProfitSharingRequest request);

    GatewayResponse queryComplaints(PaymentGatewayProperties.Channel channel, ComplaintQueryRequest request);

    GatewayResponse onboard(PaymentGatewayProperties.Channel channel, OnboardingRequest request);
}
