package com.example.payments.domain;

public enum PaymentProduct {
    ALIPAY_WAP("手机网站支付"),
    ALIPAY_APP("APP支付"),
    ALIPAY_F2F("当面付"),
    ALIPAY_PAYMENT_CODE("付款码"),
    ALIPAY_PREAUTH("线下预授权"),
    ALIPAY_PREAUTH_H5("H5预授权"),
    ALIPAY_PAGE("电脑网站支付"),
    ALIPAY_ORDER_CODE("订单码"),
    ALIPAY_JSAPI("JSAPI支付"),
    ALIPAY_DIRECT("直付通基础能力"),
    ALIPAY_DIRECT_WAP("直付通手机网站支付"),
    ALIPAY_DIRECT_APP("直付通APP支付"),
    ALIPAY_DIRECT_F2F("直付通当面付"),
    ALIPAY_DIRECT_PAGE("直付通电脑网站支付"),
    ALIPAY_DIRECT_ORDER_CODE("直付通订单码"),
    ALIPAY_DIRECT_JSAPI("直付通JSAPI支付"),
    DOUYIN_H5("抖音H5支付"),
    DOUYIN_NATIVE("抖音Native支付");

    private final String label;

    PaymentProduct(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean directPaymentProduct() {
        return switch (this) {
            case ALIPAY_DIRECT_WAP,
                 ALIPAY_DIRECT_APP,
                 ALIPAY_DIRECT_F2F,
                 ALIPAY_DIRECT_PAGE,
                 ALIPAY_DIRECT_ORDER_CODE,
                 ALIPAY_DIRECT_JSAPI -> true;
            default -> false;
        };
    }

    public boolean standardPaymentProduct() {
        return switch (this) {
            case ALIPAY_WAP,
                 ALIPAY_APP,
                 ALIPAY_F2F,
                 ALIPAY_PAYMENT_CODE,
                 ALIPAY_PREAUTH,
                 ALIPAY_PREAUTH_H5,
                 ALIPAY_PAGE,
                 ALIPAY_ORDER_CODE,
                 ALIPAY_JSAPI -> true;
            default -> false;
        };
    }

    public boolean douyinPaymentProduct() {
        return this == DOUYIN_H5 || this == DOUYIN_NATIVE;
    }
}
