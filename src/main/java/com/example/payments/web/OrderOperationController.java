package com.example.payments.web;

import com.example.payments.order.OrderOperationView;
import com.example.payments.payout.MerchantPayoutService;
import com.example.payments.payout.MerchantPayoutView;
import com.example.payments.sharing.ProfitSharingRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read-only order-management view. Loading this list never contacts a payment provider. */
@RestController
@RequestMapping("/api/v1/orders/operations")
public class OrderOperationController {
    private static final Logger log = LoggerFactory.getLogger(OrderOperationController.class);
    private final ProfitSharingRecordService sharingRecords;
    private final MerchantPayoutService payouts;

    public OrderOperationController(ProfitSharingRecordService sharingRecords, MerchantPayoutService payouts) {
        this.sharingRecords = sharingRecords;
        this.payouts = payouts;
    }

    @GetMapping
    public OperationsResponse list(
            @RequestParam(required = false) String beginTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String outTradeNo,
            @RequestParam(required = false) String tradeNo,
            @RequestParam(required = false) String channelId
    ) {
        List<OrderOperationView> result = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try {
            result.addAll(sharingRecords.search(beginTime, endTime, outTradeNo, tradeNo, channelId));
        } catch (RuntimeException ex) {
            log.warn("Order-management profit sharing history unavailable: {}", ex.getClass().getSimpleName());
            warnings.add("分账记录读取失败，请检查数据库配置和服务日志；当前列表不完整。");
        }
        try {
            payouts.search(beginTime, endTime, outTradeNo, tradeNo, channelId).stream()
                    .map(OrderOperationController::fromPayout).forEach(result::add);
        } catch (RuntimeException ex) {
            log.warn("Order-management payout history unavailable: {}", ex.getClass().getSimpleName());
            warnings.add("代付记录读取失败，请检查数据库配置和代付数据表；当前列表不完整。");
        }
        result.sort(Comparator.comparing(OrderOperationView::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(OrderOperationView::recordType)
                .thenComparing(OrderOperationView::orderNo, Comparator.nullsLast(Comparator.reverseOrder())));
        return new OperationsResponse(List.copyOf(result), List.copyOf(warnings));
    }

    private static OrderOperationView fromPayout(MerchantPayoutView payout) {
        return new OrderOperationView("PAYOUT", payout.outBizNo(),
                firstText(payout.platformOrderNo(), payout.platformFundOrderNo()), null,
                payout.channelId(), payout.provider(), null, null,
                payout.orderTitle(), payout.amount(), payout.status(), payout.createdAt(),
                payout.recipientMasked(), firstText(payout.failReason(), payout.message()));
    }

    private static String firstText(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    public record OperationsResponse(List<OrderOperationView> records, List<String> warnings) {
    }
}
