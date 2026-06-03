package com.example.payments.order;

public enum DemoOrderStatus {
    UNPAID("未支付"),
    FROZEN("冻结中"),
    COMPLETED("已完成"),
    PARTIALLY_REFUNDED("部分退款"),
    REFUNDED("已退款"),
    CLOSED("已关闭");

    private final String label;

    DemoOrderStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
