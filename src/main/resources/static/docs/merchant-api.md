# 商户网站接入规范

这份文档适用于自己开发的网站。网站后端使用平台商户号与商户密钥调用签名 JSON API；用户在网站中付款，平台处理支付宝或抖音通道，再把支付结果通知网站。管理后台登录接口、收银台公开接口与本接口用途不同。

## 1. 接入清单

从商户中心复制完整 API 地址，例如 `https://pay.example.com/api/v1/merchant-api`。如果宝塔站点使用子路径，必须保留该子路径。所有调用为服务器到服务器的 `POST`，请求头 `Content-Type: application/json; charset=utf-8`。SDK还发送 `X-Merchant-Response-Signature: canonical-v1` 选择可跨语言验签的响应格式。

向网站开发者提供：

- 完整 API 地址、商户号、已开通的支付产品。
- 选择 MD5 时，单独安全传递商户 MD5 密钥。
- 选择 RSA2 时，网站保存商户私钥，平台保存商户公钥；网站另保存平台公钥，用于验平台响应和通知。商户公钥与平台公钥不能混用。
- 本文及同目录下的 `merchant-client.mjs`、`merchant-example.mjs`。

MD5 和 RSA2 是同一接口的两种签名方式，不是两套 API 版本。密钥只保存在网站后端；不要放进网页 JavaScript、地址栏、代码仓库或公开接入清单。后台平台私钥不需要交给商户。

## 2. 先运行不扣款的检查

下载 `/sdk/merchant-client.mjs` 和 `/sdk/merchant-example.mjs`，放在同一目录。使用 Node.js 20 或更高版本，无需安装其他依赖。

Linux / 宝塔终端示例（将占位值替换为本商户资料）：

```sh
export MERCHANT_API_BASE='https://pay.example.com/api/v1/merchant-api'
export MERCHANT_ID='你的商户号'
export MERCHANT_MD5_KEY='你的商户MD5密钥'
export SIGN_TYPE='MD5'
node merchant-example.mjs check
```

PowerShell 中改用 `$env:MERCHANT_ID = '你的商户号'` 等环境变量赋值。不要把带真实密钥的终端截图公开分享。

RSA2 配置改为：

```sh
export SIGN_TYPE='RSA2'
export MERCHANT_PRIVATE_KEY_FILE='/secure/merchant-private.pem'
export PLATFORM_PUBLIC_KEY_FILE='/secure/platform-public.pem'
node merchant-example.mjs check
```

`check` 验证网络、签名、时间、随机串和商户状态，并返回通道就绪信息；不会调用支付机构或扣款。先处理 `readiness.configurationWarnings` 中的配置提示。`readiness.availableChannelCount` 大于零只表示平台配置有可用通道，不表示支付宝/抖音已批准该产品权限。正式接入仍需以小额真实支付验证业务链路。

## 3. 接口和字段

| 路径 | 用途 | 业务必填字段 |
| --- | --- | --- |
| `/check` | 不扣款联通检查 | 无 |
| `/pay` | 创建支付订单 | `product, outTradeNo, subject, totalAmount` |
| `/query` | 查询订单 | `outTradeNo` 或 `tradeNo` 至少一个 |
| `/cancel` | 取消订单 | `outTradeNo` 或 `tradeNo` 至少一个 |
| `/refund` | 申请退款 | 订单号二选一，以及 `refundAmount, outRequestNo` |

所有路径都追加在完整 API 地址后。`outTradeNo` 与 `tradeNo` 同时提供时必须属于同一订单。订单查询、取消、退款均固定使用原支付通道，不能切换到另一通道操作。

所有接口还需要共同字段（SDK自动添加）：

| 字段 | 格式 |
| --- | --- |
| `merchantId` | 商户中心显示的商户号 |
| `signType` | `MD5` 或 `RSA2`，须已启用 |
| `timestamp` | 当前 UTC ISO 时间、10位秒时间戳或13位毫秒时间戳；服务端容许前后15分钟 |
| `nonce` | 每次请求新生成的随机串，SDK使用16字节随机数的32位十六进制文本 |
| `sign` | 签名结果 |

金额使用人民币元的字符串，保留两位小数，例如 `"1.00"`；不能用浮点数累计金额。订单号建议使用短商户前缀加唯一业务编号，例如 `M1001_202609280001`，同时满足选用通道的长度限制。订单号和退款请求号在平台现有数据库中是全局唯一，不能与其他商户共用 `ORDER001` / `REFUND001` 一类简单编号。

`channelIds` 可省略，由平台在商户已绑定的可用通道中选择；不能使用未绑定通道。`product` 以商户中心/检查结果中实际显示的产品枚举为准，例如 `ALIPAY_ORDER_CODE`、`DOUYIN_NATIVE`、`DOUYIN_H5`。付款码、JSAPI、直付通等产品有各自额外字段与授权要求，不是所有商户默认可用。

`notifyUrl` 为**你的网站后端**接收平台支付结果的公网 HTTP(S) 地址，建议 HTTPS。`returnUrl` 是浏览器回跳地址；浏览器回跳不能作为支付成功依据。`extra` 等扩展字段仅用于已经确认支持的产品参数，不能覆盖订单号、金额、商户、回调或内部路由字段。商户 API 不接受自带 `appAuthToken`，授权使用后台已绑定通道配置，避免操作到另一卖家。

### 下单

新建 `pay.json`：

```json
{
  "product": "ALIPAY_ORDER_CODE",
  "outTradeNo": "M1001_202609280001",
  "subject": "网站订单",
  "totalAmount": "1.00",
  "notifyUrl": "https://shop.example.com/payment/notify"
}
```

```sh
node merchant-example.mjs pay ./pay.json
```

成功创建后，把 `data.qrCode` 生成二维码，或按该产品返回的 `data.redirectUrl` / `data.redirectHtml` 完成跳转。扫码链接生成成功不表示用户已经付款。

### 查询和取消

`query.json` 内容为 `{"outTradeNo":"M1001_202609280001"}`。

```sh
node merchant-example.mjs query ./query.json
node merchant-example.mjs cancel ./query.json
```

取消接口是否支持撤销/关闭以及可操作的订单状态由通道决定；不要把取消当作通用退款接口。

### 退款

`refund.json`：

```json
{
  "outTradeNo": "M1001_202609280001",
  "outRequestNo": "M1001_RF202609280001",
  "refundAmount": "1.00",
  "refundReason": "用户申请退款"
}
```

```sh
node merchant-example.mjs refund ./refund.json
```

同一业务退款保持同一个请求号。平台会拒绝已经使用的退款请求号，避免重发上游；遇到超时/冲突不能换新编号直接重退。上游传输错误也可能显示 `FAILED`，因此没有确认结果时会保留退款金额占位，不能仅凭该状态重退。当前商户 API 没有独立退款结果查询接口，支付查询也不能代替退款查询：不明退款需在平台订单/退款管理中核对原请求后处理。

## 4. 响应与业务状态

SDK会先验平台响应签名，再返回对象。例如：

```json
{
  "code": "SUCCESS",
  "message": "OK",
  "data": {
    "channelId": "ali-main",
    "status": "CREATED",
    "outTradeNo": "M1001_202609280001",
    "qrCode": "https://example.invalid/pay/placeholder"
  },
  "timestamp": "2026-09-28T00:00:00Z",
  "signType": "MD5",
  "sign": "这里是实际签名"
}
```

外层 `code=SUCCESS` 表示平台接口正常返回；业务状态在 `data.status`。`CREATED/PAYING/PENDING/UNKNOWN` 都不能当作已付款；**普通支付**查询返回 `SUCCESS` 或经验证的支付完成通知，且商户号、订单号和金额核验一致后才可推进发货。预授权产品的 `SUCCESS` 可能只是冻结成功，不能当作已收款；须按预授权转支付结果处理。退款响应的 `SUCCESS` 表示退款操作成功，不能与支付完成混用。上游拒绝可能表现为外层 `SUCCESS`、内层 `data.status=FAILED`，需阅读 `data.code/message`。

输入/鉴权/权限错误用 HTTP 4xx 加 `{ "code": "...", "message": "..." }` 返回；此类错误可能未签名，只用于诊断，不能据此入账。HTTP超时、断网、502以及验签失败均不能推断支付或退款已失败。

为保留原接入方式，无请求头时旧业务接口保持旧响应格式及签名行为。新接入应使用 SDK 的 `X-Merchant-Response-Signature: canonical-v1`：响应 `data` 递归排序、移除 null/空白字符串/名为 sign 的内部字段，所有数值转换为十进制文本，避免跨语言大整数/小数丢失。布尔值仍为布尔值，列表顺序保留。`check` 默认使用此格式。

## 5. 签名规则

自行用其他语言实现时，按以下规则与随仓库提供的 `src/test/resources/merchant-signature-vectors.json` 对照。请求必须只传接口定义的字段；不要附加未定义字段再参与签名。

1. 对对象键按 UTF-16 码元字典序升序排列；递归忽略名为 `sign` 的字段、null、空字符串及 Java `String.isBlank()` 定义的全空白字符串。
2. 列表保留原顺序，移除 null 元素；列表中的空字符串保留。嵌套对象按同样规则排序。
3. 顶层按 `key=value` 拼接，用 `&` 连接，不做 URL 编码。字符串不加引号；布尔值用 `true/false`；嵌套对象和列表使用紧凑 JSON（无多余空格，中文不转义）。
4. 金额传字符串以保留小数位。SDK限制扩展 JSON 数值为安全整数；非整数/超大数请传十进制字符串，避免服务端 JSON 数值类型改变签名原文。
5. `signType`、`timestamp`、`nonce` 都参与签名。
6. MD5：对 `签名原文 + 商户MD5密钥` 的 UTF-8 字节计算 MD5，得到32位十六进制（SDK输出大写）。不加 `&key=`。
7. RSA2：对签名原文的 UTF-8 字节使用 SHA256withRSA / PKCS#1 v1.5 签名，Base64 编码。私钥为 PKCS#8、公钥为 X.509/SPKI，可使用 PEM。

不要对商户请求使用支付宝官方 `RSA2` 拼串规则，或使用抖音上游证书规则；网站对接的是平台商户 API。

## 6. 接收平台通知

平台向 `notifyUrl` 发送 `POST application/x-www-form-urlencoded`，字段为：

`merchantId, outTradeNo, tradeNo, channelId, productName, totalAmount, tradeStatus, status, notifyTime, signType, sign`

这是平台签名的 camelCase 表单，不是支付宝原始通知。`status=COMPLETED` 表示普通支付完成；其他状态不得无条件发货。`tradeStatus` 是上游原始状态，仅作补充。`totalAmount` 为两位小数字符串。通知没有请求用的 nonce/timestamp，使用 `notifyTime`；可能因重试延迟，不能直接套用请求15分钟窗口拒绝重试。

签名方式采用当前商户默认模式：包含 MD5 时默认 MD5，否则 RSA2。RSA2 通知用平台公钥验证。若要求通知必须 RSA2，将商户签名模式设为仅 RSA2，并在验签处限制 `expectedSignType: 'RSA2'`。

```js
import { verifyNotification } from './merchant-client.mjs';

// formBody 是服务端读取的原始表单文本；SDK仅解析和验签，不替你更新订单。
const notice = verifyNotification(formBody, {
  md5Key: process.env.MERCHANT_MD5_KEY,
  platformPublicKey,
  expectedSignType: 'MD5'
}, process.env.MERCHANT_ID);

// 在你的网站数据库事务中：
// 1. 按 outTradeNo 锁定订单，确认商户号、金额和交易号与本站订单一致。
// 2. 仅在 status === 'COMPLETED' 时推进“已付款”，已处理则直接视为重复通知。
// 3. 事务提交成功后返回 HTTP 200，纯文本 success（小写）。
// 不匹配、验签失败或数据库提交失败时，不返回 success。
```

重复通知是正常情况。网站应以自己的订单状态做幂等更新，不要每收到一次通知就增加一次余额或发一次货。通知未到时，可从网站后端主动查原支付订单作为补偿。

## 7. 超时、重复请求与常见错误

支付请求会在调用通道前占用订单号并固定通道；重复订单号返回 `ORDER_CONFLICT`，不会再次创建支付。平台进程在上游调用中断后，原单可能仍处于不明状态；必须查原单或人工核对。SDK不会自动重发支付/退款请求。此设计是“拒绝重复提交”，不是重复请求自动返回原结果。

| 现象/错误 | 处理 |
| --- | --- |
| 404、HTML登录页、`UNAUTHENTICATED` | 核对完整 `/api/v1/merchant-api/...` 地址和宝塔反向代理；不要调用后台 `/payments` 路径 |
| `MERCHANT_NOT_FOUND` / `MERCHANT_DISABLED` | 核对商户号及商户启用状态 |
| `INVALID_SIGNATURE` | 核对密钥、完整字段、金额格式和MD5追加规则，使用SDK固定向量排查 |
| `SIGN_TYPE_DISABLED` | 商户中心启用对应签名方式，或修改客户端 SIGN_TYPE |
| `TIMESTAMP_EXPIRED` / `INVALID_TIMESTAMP` | 同步服务器时间，每次请求现生成 timestamp |
| `NONCE_REUSED` | 新请求重新生成 nonce；原业务订单号保持不变 |
| `CHANNEL_FORBIDDEN` / 无可用产品 | 后台绑定并启用该商户的支付通道和产品，再运行 check |
| `CHANNEL_NOT_READY` | 补齐该通道的公网网关通知地址，然后再创建订单 |
| `ORDER_CONFLICT` / `REFUND_CONFLICT` | 不要换编号重复扣款/退款，核对原请求结果 |
| `ORDER_NOT_FOUND` / 订单号不一致 | 使用当前商户自己创建的订单与对应交易号 |
| `INVALID_RESPONSE_SIGNATURE` | 核对平台公钥、网关版本和canonical-v1头；升级前旧公钥需重新同步 |
| 502、SDK `NETWORK_ERROR` | 支付结果可能不明；查原订单，退款在后台核对 |
| 查单已付款但网站未更新 | 核对商户 notifyUrl、验签公钥、金额核验以及HTTP200纯文本success应答 |

## 8. 运营与部署约束

生产需要启用数据库存储商户、订单及退款，保留应用 `data/` 目录。数据库关闭模式只用于开发；订单/商户重启丢失时不能靠签名SDK恢复业务记录。平台签名私钥、通知队列文件的具体部署配置见仓库 `docs/merchant-api-deployment.md`。

商户 API 与支付宝/抖音上游接口是两层协议。商户回调地址由平台保存；支付机构回调必须到达平台对应通道通知地址，再由平台通知网站。宝塔需保持外部 HTTPS 地址、正确代理头、通知路径可达和运行进程的持久数据目录。

初次验收顺序：check → 一笔小额付款 → 验签/金额核验/订单仅更新一次 → 主动查单 → 小额退款 → 核对退款结果 → 重复通知与失败重试。联通检查成功不能替代真实商户产品权限及支付验收。
