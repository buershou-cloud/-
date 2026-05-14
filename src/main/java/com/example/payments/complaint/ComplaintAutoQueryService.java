package com.example.payments.complaint;

import com.example.payments.domain.ComplaintQueryRequest;
import com.example.payments.domain.GatewayResponse;
import com.example.payments.gateway.PaymentGatewayService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class ComplaintAutoQueryService {

    private static final int INTERVAL_SECONDS = 60;
    private static final DateTimeFormatter ALIPAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PaymentGatewayService paymentGatewayService;

    private boolean enabled;
    private boolean running;
    private int lookbackMinutes = 24 * 60;
    private int pageSize = 50;
    private String method;
    private String appAuthToken;
    private Map<String, Object> extra = Map.of();
    private String lastStartedAt;
    private String lastFinishedAt;
    private String lastMessage = "自动查询未开启";
    private int runCount;
    private GatewayResponse lastResult;

    public ComplaintAutoQueryService(PaymentGatewayService paymentGatewayService) {
        this.paymentGatewayService = paymentGatewayService;
    }

    @Scheduled(fixedRate = INTERVAL_SECONDS * 1000L)
    public void scheduledQuery() {
        if (isEnabled()) {
            runNow();
        }
    }

    public synchronized ComplaintAutoQueryStatus configure(ComplaintAutoQueryRequest request) {
        if (request == null) {
            return status();
        }
        if (request.enabled() != null) {
            enabled = request.enabled();
        }
        if (request.lookbackMinutes() != null) {
            lookbackMinutes = Math.max(1, request.lookbackMinutes());
        }
        if (request.pageSize() != null) {
            pageSize = Math.max(1, request.pageSize());
        }
        if (request.method() != null) {
            method = request.method().trim();
        }
        if (request.appAuthToken() != null) {
            appAuthToken = request.appAuthToken().trim();
        }
        if (request.extra() != null) {
            extra = Map.copyOf(request.extra());
        }
        lastMessage = enabled ? "已开启每分钟自动查询全部通道投诉" : "自动查询已停止";
        return status();
    }

    public synchronized ComplaintAutoQueryStatus runNow() {
        if (running) {
            lastMessage = "上一次自动查询还在执行中";
            return status();
        }
        running = true;
        lastStartedAt = nowText();
        try {
            ComplaintQueryRequest request = requestForCurrentWindow();
            lastResult = paymentGatewayService.queryComplaintsAllChannels(request);
            runCount++;
            lastMessage = lastResult.message();
            return status();
        } catch (RuntimeException ex) {
            lastMessage = ex.getMessage();
            throw ex;
        } finally {
            lastFinishedAt = nowText();
            running = false;
        }
    }

    public synchronized ComplaintAutoQueryStatus status() {
        return new ComplaintAutoQueryStatus(
                enabled,
                running,
                INTERVAL_SECONDS,
                lookbackMinutes,
                pageSize,
                lastStartedAt,
                lastFinishedAt,
                lastMessage,
                runCount,
                lastResult
        );
    }

    private synchronized boolean isEnabled() {
        return enabled;
    }

    private ComplaintQueryRequest requestForCurrentWindow() {
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime begin = end.minusMinutes(lookbackMinutes);
        return new ComplaintQueryRequest(
                null,
                ALIPAY_TIME.format(begin),
                ALIPAY_TIME.format(end),
                1,
                pageSize,
                method,
                appAuthToken,
                List.of(),
                extra
        );
    }

    private static String nowText() {
        return ALIPAY_TIME.format(LocalDateTime.now());
    }
}
