package com.example.payments.gateway.douyin;

import com.example.payments.channel.ChannelRegistry;
import com.example.payments.config.PaymentGatewayProperties;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.order.DemoOrderService;
import com.example.payments.order.DouyinPendingRefund;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class DouyinRefundReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(DouyinRefundReconciliationService.class);
    private static final int PENDING_SCAN_LIMIT = 100;
    private static final int QUERY_LIMIT_PER_RUN = 10;

    private final DemoOrderService orderService;
    private final ChannelRegistry channelRegistry;
    private final DouyinPaymentProvider paymentProvider;
    private final AtomicBoolean running = new AtomicBoolean();

    public DouyinRefundReconciliationService(
            DemoOrderService orderService,
            ChannelRegistry channelRegistry,
            DouyinPaymentProvider paymentProvider
    ) {
        this.orderService = orderService;
        this.channelRegistry = channelRegistry;
        this.paymentProvider = paymentProvider;
    }

    @Scheduled(
            initialDelayString = "${payment.douyin.refund-reconciliation-initial-delay-ms:12000}",
            fixedDelayString = "${payment.douyin.refund-reconciliation-delay-ms:5000}"
    )
    public void reconcilePendingRefunds() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            reconcile();
        } catch (RuntimeException ex) {
            log.warn("Failed to scan pending Douyin refund orders", ex);
        } finally {
            running.set(false);
        }
    }

    private void reconcile() {
        List<DouyinPendingRefund> pending = orderService.pendingDouyinRefunds(PENDING_SCAN_LIMIT);
        int queried = 0;
        for (DouyinPendingRefund refund : pending) {
            if (queried >= QUERY_LIMIT_PER_RUN) {
                return;
            }
            queried++;
            queryRefund(refund);
        }
    }

    private void queryRefund(DouyinPendingRefund refund) {
        try {
            PaymentGatewayProperties.Channel channel = channelRegistry.find(refund.channelId())
                    .orElseThrow(() -> new IllegalStateException("Unknown Douyin channel: " + refund.channelId()));
            GatewayResponse response = paymentProvider.queryRefund(channel, refund.outRequestNo());
            if (response.status() == PaymentStatus.SUCCESS) {
                orderService.recordDouyinRefundNotify(
                        refund.outRequestNo(),
                        "SUCCESS",
                        response.tradeNo()
                );
                log.info(
                        "Reconciled successful Douyin refund outRefundNo={} outTradeNo={} channel={}",
                        refund.outRequestNo(),
                        refund.outTradeNo(),
                        refund.channelId()
                );
            } else if (response.status() == PaymentStatus.FAILED
                    || response.status() == PaymentStatus.CLOSED) {
                orderService.recordDouyinRefundNotify(
                        refund.outRequestNo(),
                        "FAILED",
                        response.tradeNo()
                );
            }
        } catch (RuntimeException ex) {
            log.debug(
                    "Douyin refund reconciliation query failed outRefundNo={} channel={}",
                    refund.outRequestNo(),
                    refund.channelId(),
                    ex
            );
        }
    }
}
