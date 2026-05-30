# Alipay Mini Program Cashier

JSAPI payment must be started from an Alipay Mini Program page. A normal H5 cashier
page cannot reliably call the Mini Program payment APIs.

Use this sample page as the mini-program cashier entry configured by the backend QR:

```text
pages/cashier/index
```

The QR query contains:

```text
baseUrl=https://your-domain.example
channelId=ali-main
product=ALIPAY_JSAPI
miniAppId=202100...
```

Flow:

1. The mini program calls `my.getAuthCode` with `auth_base`.
2. It posts the order to `${baseUrl}/api/v1/payments/pay`.
3. The server calls `alipay.system.oauth.token`, then `alipay.trade.create`.
4. The mini program calls `my.tradePay` with the returned `tradeNo`.

Before publishing, set the Mini Program request domain to your deployed backend
domain in the Alipay Open Platform console.

For JSAPI QR generation, configure the channel `miniAppId` with the Alipay Mini
Program AppID. If it is left empty, the backend falls back to the normal payment
AppID, which only works when that same AppID is also the published mini program.
