package com.example.payments.gateway.douyin;

import com.example.payments.domain.GatewayResponse;
import com.example.payments.domain.PaymentQueryRequest;
import com.example.payments.domain.PaymentStatus;
import com.example.payments.gateway.PaymentGatewayService;
import com.example.payments.order.DemoOrderService;
import com.example.payments.order.DemoOrderView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class DouyinPaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(DouyinPaymentReconciliationService.class);
    private static final int PENDING_SCAN_LIMIT = 100;
    private static final int QUERY_LIMIT_PER_RUN = 10;

    private final DemoOrderService orderService;
    private final PaymentGatewayService gatewayService;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<String, RetryState> retries = new HashMap<>();

    public DouyinPaymentReconciliationService(
            DemoOrderService orderService,
            PaymentGatewayService gatewayService
    ) {
        this.orderService = orderService;
        this.gatewayService = gatewayService;
    }

    @Scheduled(
            initialDelayString = "${payment.douyin.reconciliation-initial-delay-ms:10000}",
            fixedDelayString = "${payment.douyin.reconciliation-delay-ms:5000}"
    )
    public void reconcilePendingOrders() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            reconcile();
        } catch (RuntimeException ex) {
            log.warn("Failed to scan pending Douyin payment orders", ex);
        } finally {
            running.set(false);
        }
    }

    private void reconcile() {
        List<DemoOrderView> pending = orderService.pendingDouyinOrders(PENDING_SCAN_LIMIT);
        Set<String> pendingIds = new HashSet<>();
        Instant now = Instant.now();
        int queried = 0;

        for (DemoOrderView order : pending) {
            pendingIds.add(order.outTradeNo());
            RetryState retry = retries.get(order.outTradeNo());
            if (retry != null && retry.nextAttemptAt().isAfter(now)) {
                continue;
            }
            if (queried >= QUERY_LIMIT_PER_RUN) {
                continue;
            }
            queried++;
            reconcile(order, now, retry == null ? 0 : retry.attempts());
        }
        retries.keySet().retainAll(pendingIds);
    }

    private void reconcile(DemoOrderView order, Instant now, int attempts) {
        try {
            GatewayResponse response = gatewayService.query(new PaymentQueryRequest(
                    order.outTradeNo(),
                    order.tradeNo(),
                    null,
                    List.of(order.channelId()),
                    Map.of()
            ));
            if (terminal(response.status())) {
                retries.remove(order.outTradeNo());
                log.info(
                        "Reconciled Douyin payment order outTradeNo={} channel={} status={}",
                        order.outTradeNo(),
                        order.channelId(),
                        response.status()
                );
                return;
            }
        } catch (RuntimeException ex) {
            log.debug(
                    "Douyin payment reconciliation query failed outTradeNo={} channel={}",
                    order.outTradeNo(),
                    order.channelId(),
                    ex
            );
        }

        int nextAttempts = attempts + 1;
        retries.put(order.outTradeNo(), new RetryState(nextAttempts, now.plus(backoff(nextAttempts))));
    }

    private static boolean terminal(PaymentStatus status) {
        return status == PaymentStatus.SUCCESS
                || status == PaymentStatus.CLOSED;
    }

    private static Duration backoff(int attempts) {
        long seconds = Math.min(120, 5L << Math.min(Math.max(attempts - 1, 0), 5));
        return Duration.ofSeconds(seconds);
    }

    private record RetryState(int attempts, Instant nextAttemptAt) {
    }
}
