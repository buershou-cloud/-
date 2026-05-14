# Payment Gateway

Spring Boot payment gateway skeleton for these Alipay flows:

- 手机网站支付: `alipay.trade.wap.pay`
- 当面付: `alipay.trade.pay` for barcode payment, `alipay.trade.precreate` for QR code payment
- 预授权: default `alipay.fund.auth.order.app.freeze`
- 电脑网站支付: `alipay.trade.page.pay`
- 订单码: `alipay.trade.precreate`
- JSAPI 支付: `alipay.trade.create`
- 直付通支付: standard trade API plus `app_auth_token`, `settle_info`, `sub_merchant`, or extra contract fields
- 直付通进件: configurable method, default `alipay.open.agent.create`
- 查询投诉: configurable list/detail methods
- 退款: `alipay.trade.refund`
- 分账: `alipay.trade.order.settle`
- 多支付通道轮询: configured in `payment.channels`

The channel router now borrows the common easy-pay design:

- `ROUND_ROBIN`: rotate through eligible channels.
- `WEIGHTED_RANDOM`: choose channels by configured weight.
- `PRIORITY`: try lower `priority` values first.
- `RANDOM`: shuffle eligible channels.
- `pay-min` / `pay-max`: skip channels outside the order amount.
- `daily-enabled`: temporarily exclude a channel without deleting it.

## Run

Install JDK 21 and Maven, then:

```bash
mvn spring-boot:run
```

Use environment variables for keys, for example:

```bash
ALI_MAIN_APP_ID=2021000000000000
ALI_MAIN_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----..."
ALI_MAIN_PUBLIC_KEY="-----BEGIN PUBLIC KEY-----..."
```

Open `http://localhost:8080/` for the built-in demo console. If you use another port, replace `8080`.

## Main Endpoints

- `POST /api/v1/payments/pay`
- `POST /api/v1/payments/query`
- `POST /api/v1/payments/refund`
- `POST /api/v1/payments/profit-sharing`
- `POST /api/v1/payments/complaints/query`
- `POST /api/v1/payments/onboarding`
- `GET /api/v1/channels`
- `PATCH /api/v1/channels/{channelId}`
- `POST /api/v1/alipay/notify/{channelId}`

## Example Pay Request

```json
{
  "product": "ALIPAY_ORDER_CODE",
  "outTradeNo": "ORDER202605120001",
  "subject": "测试订单",
  "totalAmount": 0.01,
  "routingMode": "ROUND_ROBIN",
  "channelIds": ["ali-main", "ali-backup"],
  "extra": {
    "store_id": "STORE_001"
  }
}
```

For WAP/page payments, the response contains `redirectHtml`. Return it directly to the browser.
For QR/order-code payments, the response contains `qrCode`.

## Production Notes

This project intentionally keeps persistence out of the first skeleton. Before production, add:

- Payment order table and immutable attempt table.
- Idempotency on `outTradeNo` and `outRequestNo`.
- Gateway response signature verification through certificate mode or the official Alipay SDK.
- Notify processing with transaction locks and reconciliation jobs.
- Real direct-pay onboarding payloads based on the signed Alipay contract.
