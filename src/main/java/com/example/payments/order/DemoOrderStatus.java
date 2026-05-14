package com.example.payments.order;

public enum DemoOrderStatus {
    UNPAID("未支付"),
    FROZEN("冻结中"),
    COMPLETED("已完成"),
    REFUNDED("已退款");

    private final String label;

    DemoOrderStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
