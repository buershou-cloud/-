# Payment Gateway

Spring Boot payment gateway for Alipay and Douyin Pay flows.

Douyin Pay:

- 抖音 H5 支付: `POST /v1/trade/transactions/h5`, with official RSA request signing, order query/close, refund, signed encrypted notifications, and H5 cashier redirection.
- 商家代付到抖音零钱: official merchant-transfer create/query APIs, RSA-encrypted sensitive fields, signed encrypted result notifications, and original-order reconciliation.

Alipay:

- 手机网站支付: `alipay.trade.wap.pay` with `QUICK_WAP_WAY`
- APP支付: `alipay.trade.app.pay` with `QUICK_MSECURITY_PAY`; returns the signed order string for the Alipay App SDK
- 当面付: `alipay.trade.precreate` with `FACE_TO_FACE_PAYMENT`, no buyer `auth_code` required
- 线下预授权扫码: `alipay.fund.auth.order.voucher.create` with `PRE_AUTH`; transfer/capture uses `alipay.trade.pay` with `product_code=PRE_AUTH`, `auth_no`, `buyer_id`, and `seller_id`
- H5 预授权: `alipay.fund.auth.order.app.freeze` with `PRE_AUTH_ONLINE`; returns a signed order string for Alipay H5 `tradePay`
- 电脑网站支付: `alipay.trade.page.pay` with `FAST_INSTANT_TRADE_PAY`
- 订单码: `alipay.trade.precreate` with `QR_CODE_OFFLINE`, then poll `alipay.trade.query`; unpaid timeout can call `alipay.trade.cancel`
- JSAPI 支付: `alipay.trade.create` with `JSAPI_PAY`, requires `buyerId` or `buyerOpenId`
- 直付通支付: standard trade API plus `app_auth_token`, `settle_info`, `sub_merchant`, or extra contract fields
- 直付通进件: `ant.merchant.expand.indirect.zft.simplecreate`
- 查询投诉: configurable list/detail methods
- 退款: `alipay.trade.refund`
- 分账: `alipay.trade.order.settle`
- 多支付通道轮询: configured in `payment.channels`
- 支付宝商家转账: `alipay.fund.trans.uni.transfer`, with original-order query and application-gateway result notifications

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

Default admin login:

- Username: `admin`
- Password: `admin123`

After first startup the credentials are stored in `data/admin-auth.properties`. The `admin` account is migrated as the super administrator when it exists. Only the super administrator can add/delete ordinary administrators or maintain the global merchant-payout payment password; every administrator can change their own login password. Existing single-account files are migrated automatically when saved.

## MySQL Schema

The MySQL database script is in `database/mysql/payment_gateway_schema.sql`.

```bash
mysql -uroot -p < database/mysql/payment_gateway_schema.sql
```

This creates the `payment_gateway` database with tables for channels, merchants, orders, refunds, profit sharing, settlements, onboarding, complaints, and merchant payouts.

For an existing database, import the merchant-payout migration once:

```bash
mysql -u payment_gateway -p payment_gateway < database/mysql/20260719_add_merchant_payout.sql
```

Enable MySQL persistence at startup with JVM arguments or environment variables:

```bash
java -jar target/payment-gateway-0.1.0-SNAPSHOT.jar \
  --server.port=18081 \
  --payment.database.enabled=true \
  --payment.database.url="jdbc:mysql://127.0.0.1:3306/payment_gateway?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" \
  --payment.database.username=payment_gateway \
  --payment.database.password="YOUR_DATABASE_PASSWORD"
```

When `payment.database.enabled` is false, the console still uses the built-in demo data.

## Main Endpoints

- `POST /api/v1/payments/pay`
- `POST /api/v1/payments/query`
- `POST /api/v1/payments/cancel`
- `POST /api/v1/payments/refund`
- `POST /api/v1/payments/preauth-capture`
- `POST /api/v1/payments/profit-sharing`
- `POST /api/v1/payments/profit-sharing/channel`
- `POST /api/v1/payments/complaints/query`
- `POST /api/v1/payments/onboarding`
- `GET /api/v1/payouts`
- `POST /api/v1/payouts`
- `POST /api/v1/payouts/batch` (up to 20 items per request, processed with 4 workers, no automatic retry)
- `POST /api/v1/payouts/{outBizNo}/query`
- `POST /api/v1/payouts/notify/alipay/{channelId}`
- `POST /api/v1/payouts/notify/douyin/{channelId}`
- `GET /api/v1/admin-auth/security`
- `POST /api/v1/admin-auth/accounts`
- `DELETE /api/v1/admin-auth/accounts/{username}`
- `POST /api/v1/admin-auth/payment-password`
- `GET /api/v1/channels`
- `PATCH /api/v1/channels/{channelId}`
- `GET /api/v1/merchants/{merchantId}/cashier-qr`
- `POST /api/v1/alipay/notify/{channelId}`
- `POST /api/v1/douyin/notify/{channelId}`

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
For face-to-face QR and order-code payments, the response contains `qrCode`.
For Douyin H5 payments, the response contains `redirectUrl`; mobile clients open the official H5 cashier directly and desktop clients display it as a QR code.
For payment-code collection, use product `ALIPAY_PAYMENT_CODE` and pass the
customer Alipay barcode digits in `authCode`; the gateway calls
`alipay.trade.pay` with `product_code=FACE_TO_FACE_PAYMENT` and
`scene=bar_code`.

## Direct Pay Onboarding

The default onboarding operation follows the Alipay official direct-pay standard onboarding API:

- Method: `ant.merchant.expand.indirect.zft.simplecreate`
- Official external merchant field: `external_id`
- Required business fields include `alias_name`, `contact_infos`, `default_settle_rule`, `service`, and `mcc`.

The API still accepts `outBizNo` for the local request shape and maps it into `biz_content.external_id` for the direct-pay standard onboarding method.

## Production Notes

Before production, still harden these areas:

- Idempotency on `outTradeNo` and `outRequestNo`.
- Gateway response signature verification through certificate mode or the official Alipay SDK.
- Notify processing with transaction locks and reconciliation jobs.
- Complete direct-pay onboarding payloads based on the signed Alipay contract and uploaded image OSS keys.
