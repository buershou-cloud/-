# 抖音支付分账

按[抖音支付分账开发指南](https://pay.douyinpay.com/wiki/63984677e9a722021c2c882e/69492c421fb1180636728e5b)接入直连商户分账。沿用已有抖音通道的 AppID、商户号、私钥、商户证书和平台证书，不使用支付宝账号、证书或分账关系，也不使用小程序担保支付结算接口。

## 控制台操作

1. 在“分账关系”中选择抖音通道，添加接收方。商户使用 `MERCHANT_ID` 并填写商户全称；个人使用 `PERSONAL_OPENID`。姓名使用平台证书加密，本地关系保留原始姓名。
2. 打开该抖音通道或商户二维码的收银台，抖音 H5 / Native 新订单默认勾选“本订单需要分账”；商户多通道路由同样生效。不需分账时可在下单前取消勾选。通道须已开通分账权限，收款资金将保留待分账，完成分账后可解冻剩余资金。
3. 支付成功后，在“分账处理”选择原抖音通道和已绑定接收方。输入以元为单位的金额，支持单笔或勾选订单批量提交，每个接收方至少 0.01 元，最多两位小数。抖音默认在本次分账完成后解冻剩余资金；同一原订单还要继续分给其他人时，应先选择保留冻结，直到最后一次分账才解冻。确认窗口会显示本次选择。
4. 受理不等于到账。使用提交时保留的请求号查询，或等待已验签解密的分账完成通知。只有各接收方均成功才标记本地订单“已分账”。部分失败时查看 `raw.receivers`（平台包装响应时为 `raw.data.receivers`）中的 `result` / `fail_reason`，成功部分可能已到账。
5. 旧订单已分账但仍冻结，且确认没有后续分账时，使用“结束分账并解冻剩余”。此操作不再向收入方划拨；解冻后该原订单不能继续分账。剩余额度可在第一次分账前查询，只需交易单号。回退仅支持商户接收方，使用独立回退请求号，且需符合平台权限与时限。

抖音分账和支付宝分账分别加载各自通道的绑定关系。切换通道保留支付宝原有比例/金额选择，且会校验订单、收入方与当前通道一致。

## API 示例

以下省略现有接口认证步骤。所有分账相关操作建议显式传入原支付通道 `channelIds`；抖音资金操作限定单一通道，不跨通道重试。示例中的账号与请求号需替换为实际值。

支付下单请求 `/api/v1/payments/pay` 和商户签名接口 `/api/v1/merchant-api/pay` 对抖音 H5 / Native 新订单默认开启分账，无需额外传参。也可在原有字段中明确指定：

```json
{
  "product": "DOUYIN_H5",
  "channelIds": ["douyin-main"],
  "settleInfo": { "profit_sharing": true }
}
```

这是字段片段，需与原有必填订单号、金额、主题等字段一起提交；`DOUYIN_NATIVE` 同样支持。商户 API 修改请求字段后需重新生成签名。需要普通结算时明确传 `"settleInfo": { "profit_sharing": false }`。如果使用 `extra.settle_info`，它会按原有规则覆盖顶层 `settleInfo`，请勿重复传入冲突值；最终结算信息缺少 `profit_sharing` 时默认为 `true`，其他结算字段保留。

默认开启仅适用于升级后新建的抖音订单，不影响支付宝。开启分账的订单收款资金会先进入不可用余额，完成分账后需解冻剩余资金。升级前已创建且未开启分账的订单不能通过重试或修改本地数据补开分账，也不要对同一笔已支付业务重新收费。

`POST /api/v1/payments/profit-sharing/relations/bind`：

```json
{
  "receiverAccount": "接收方抖音商户号",
  "receiverType": "MERCHANT_ID",
  "receiverName": "接收方商户全称",
  "outRequestNo": "RELATION_1001",
  "channelIds": ["douyin-main"],
  "extra": { "relation_type": "PARTNER" }
}
```

`POST /api/v1/payments/profit-sharing`：

```json
{
  "outTradeNo": "ORDER_1001",
  "tradeNo": "抖音支付交易单号",
  "outRequestNo": "SPLIT_1001",
  "channelIds": ["douyin-main"],
  "royaltyParameters": [{
    "trans_in_type": "MERCHANT_ID",
    "trans_in": "接收方抖音商户号",
    "receiver_name": "接收方商户全称",
    "amount": "1.23",
    "desc": "订单分账"
  }],
  "extra": { "unfreeze_unsplit": true }
}
```

`extra.unfreeze_unsplit` 为 `true` 时，在本次分账后将剩余金额解冻结算给商户；为 `false` 时继续冻结，允许后续分账。本系统对新抖音分账请求在缺省或为 `null` 时默认使用 `true`，明确指定 `false` 会被保留。需要多次分账的直接 API 调用方，应在非最后一次请求中明确传 `false`。这只是本系统默认值，不代表抖音平台省略参数时的默认规则。支付宝请求不受此选项影响。

控制台以表单中的剩余资金选择为准，并会将其写入请求；不要同时在扩展 JSON 中填入相反值。批量操作按原订单逐笔执行，每单都使用确认时的选择；恢复旧批次和重试原请求时保留原参数，不套用新默认值。更改默认值不会回溯解冻已有订单。

通用网关接收元金额并精确转换成抖音整数分；不向抖音传入支付宝比例参数。每次最多 50 个接收方。`outRequestNo` 为 6–32 位字母、数字、`_`、`-`、`*`；`outReturnNo` 为 1–32 位非空字符串。超时、处理中、结果不明确时保留原请求号查询，不换号重复划拨。后台已记录的分账请求不会再次发送资金划拨。

## 已分账但剩余资金仍冻结

1. 在分账页面选择原抖音通道、原订单或交易单号，先查询原分账结果。若仍处理中或有未核对明细，先核对，不要重新分账。
2. 确认所有接收方已分配完毕，再点击“结束分账并解冻剩余”，核对确认窗口中的原交易和通道。
3. 保存独立的解冻请求号，用“查询解冻结果”查看原完结请求。完结使用独立请求号，不覆盖原分账请求号，也不再提交接收方或分账金额。
4. “已提交”或“处理中”不表示解冻成功；页面仅在平台确认对应完结请求成功时显示已确认。失败或网络异常后应查原号，不换号重复操作。

当前标签页会保存解冻请求号，已有记录再次操作时只查询原请求；关闭标签页或更换浏览器前请另行保存该号码。升级和页面刷新不会自动完结任何旧订单。

| 本系统接口（POST） | 用途与主要字段 |
| --- | --- |
| `/api/v1/payments/profit-sharing/query` | `tradeNo`、`outRequestNo`、`channelIds` 查询各接收方结果；传 `outTradeNo` 可同步本地订单。查独立解冻请求时另传 `extra.operation: "FINISH"` |
| `/api/v1/payments/profit-sharing/remaining` | `tradeNo`、`channelIds`；无需已有分账请求号，平台 `unsplit_amount` 单位为分 |
| `/api/v1/payments/profit-sharing/finish` | `tradeNo`、新的 `outRequestNo`、`channelIds`、可选 `description` |
| `/api/v1/payments/profit-sharing/return` | 原 `outRequestNo`、`outReturnNo`、`receiverAccount` 商户号、`amount` 元、`channelIds` |
| `/api/v1/payments/profit-sharing/return/query` | 原 `outRequestNo`、`outReturnNo`、`channelIds` |
| `/api/v1/payments/profit-sharing/relations/unbind` | `receiverAccount`、`receiverType`、`channelIds` |

抖音未提供接收方列表查询接口，控制台读取本系统成功绑定的记录。已有数据库的 `profit_sharing_relation` 表按通道隔离复用；生产环境应启用数据库持久化。此次解冻调整没有新增数据表，若尚未部署订单流水版本，请先执行 `database/mysql/20260928_add_profit_sharing_record.sql`。

## 结果与通知

- `PROCESSING` 或接收方 `PENDING`：返回 `PENDING`，不能判为已分账。
- `FINISHED` 且非空接收方明细全部为 `SUCCESS`：返回 `SUCCESS`。
- 接收方 `CLOSED` 或其他已知失败：返回 `FAILED`，保留完整接收方明细。`FINISHED` 且无明细的分账查询按未确认结果处理。
- 普通分账响应即使同时带有解冻字段，也按接收方明细确认分账结果；不会把分账并解冻误当成仅解冻。
- 完结分账可没有接收方明细；查询到无接收方且有 `finish_amount` 或 `finish_description` 的完结结果不会误标记为已经向接收方分账，也不会新增分账支出流水。解冻查单的 `extra.operation: "FINISH"` 仅声明查询类别；没有平台完结证据时不能据此判成功，处理中结果也不补造分账支出。回退使用平台 `PROCESSING/SUCCESS/FAILED` 状态。
- 通道默认通知地址 `/api/v1/douyin/notify/{channelId}` 验签并解密。只有完整分账完成事件且全部接收方成功、交易号与原通道匹配才更新已有本地订单。接收方单笔到账事件不当作整个分账完成。

上线联调需要实际商户开通分账能力并配置公网 HTTPS 回调。自动测试使用模拟网关响应，不会发起真实支付、分账或回退。

## 官方参考

- [官方 SDK 分账参数与完结接口](https://pkg.go.dev/github.com/douyinpay/douyinpay-go/services/profitsharing)
- [请求分账](https://pay.douyinpay.com/wiki/639fd48f17c2f3021d237f61/694937c648fd720521ddf214)
- [查询分账](https://pay.douyinpay.com/wiki/639fd48f17c2f3021d237f61/6949383d7f605b05358e7cc7)
- [添加接收方](https://pay.douyinpay.com/wiki/639fd48f17c2f3021d237f61/6949394b7f605b05358e83b4)
- [分账结果通知](https://pay.douyinpay.com/wiki/639fd48f17c2f3021d237f61/69ba1e907b1a8a04d884c583)
- [完结分账](https://pay.douyinpay.com/wiki/639fd48f17c2f3021d237f61/69c125ae5e28e105291b6c3d)
- [分账回退](https://pay.douyinpay.com/wiki/639fd48f17c2f3021d237f61/694938cbb9dd320544606cf7)
