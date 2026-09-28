# 商户网站 API

完整接入说明与可运行 SDK 随服务一起发布，商户从接入中心可以直接下载：

- [商户网站接入规范](../src/main/resources/static/docs/merchant-api.md)
- [Node.js 客户端](../src/main/resources/static/sdk/merchant-client.mjs)
- [命令行示例](../src/main/resources/static/sdk/merchant-example.mjs)
- [部署与升级](merchant-api-deployment.md)

协议根路径为 `/api/v1/merchant-api`，提供 check、pay、query、cancel、refund。MD5/RSA2 是签名方式，使用同一组接口。新客户端发送 `X-Merchant-Response-Signature: canonical-v1` 以选择可跨语言验签的响应表示；旧客户端无该头时保留原响应协议。
