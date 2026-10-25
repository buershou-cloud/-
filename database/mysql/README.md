# MySQL Database

This folder contains the MySQL schema for the payment gateway.

## Import

```bash
mysql -uroot -p < database/mysql/payment_gateway_schema.sql
```

The script creates database `payment_gateway`, all core tables, a merchant order summary view, and the complaint auto query config row. It does not insert demo channels, merchants, or orders.

After import, add real channels and merchants from the admin pages.

For an existing database, run incremental SQL files in this folder after pulling
new code. For example, `20260529_add_alipay_payment_code_product.sql` enables
the new `ALIPAY_PAYMENT_CODE` product on channels that already support
`ALIPAY_F2F`.

To enable the Douyin H5 payment channel on an existing database, run:

```bash
mysql -u payment_gateway -p payment_gateway < database/mysql/20260718_add_douyin_h5_payment.sql
```

To complete Douyin Pay certificate mode on an existing database, run:

```bash
mysql -u payment_gateway -p payment_gateway < database/mysql/20260719_add_douyin_merchant_certificate.sql
```

To add Alipay and Douyin merchant payouts on an existing database, run:

```bash
mysql -u payment_gateway -p payment_gateway < database/mysql/20260719_add_merchant_payout.sql
```

## Spring Boot Startup

### 2026-10-25 预授权扣款升级

升级已有实例时，先备份数据库并导入明确扣款关联表：

```bash
mysql -u payment_gateway -p payment_gateway < database/mysql/20261025_add_preauth_capture_order.sql
```

新版本在请求支付平台前，在同一事务中锁定原冻结订单、写入 `preauth_capture_order` 和独立的 `pay_order` 实扣子单。子单金额是实际请求扣款金额；支付处理中仍为未付款，只有身份与金额匹配的成功回调或原交易查询才能完成。退款、收款统计和商户到账通知使用子单。父单保留冻结本金及授权号，`NOT_COMPLETE` 扣除已成功实扣后保留冻结余额，`COMPLETE` 成功后表示剩余冻结资金已释放，剩余额为零。

网络超时、平台系统异常等结果未知时保留 `PENDING`，不得更换订单号重复扣款。应查询原实扣子单或等待原回调确认；已明确拒绝的操作可以使用新请求号重新申请。解冻与扣款在同一父订单锁下预留，存在待确认操作时拒绝并发支出。重复提交相同解冻请求会查询原解冻操作，不会再次解冻。

**不会依据 `_PAY_数字` 后缀自动迁移历史关联。** 升级前产生的历史扣款必须先核对支付平台真实交易号、授权号、所属商户、通道、金额及状态，再由维护人员登记明确关联和独立收款记录；不得把冻结本金直接当作实收金额。有关联的父、子订单不能通过订单删除接口删除，以免重用同名订单造成串单。

新建数据库的完整脚本已包含此表。应用也会尝试创建缺失的关联表；生产数据库账号没有建表权限时，请在启动前由迁移账号执行上述 SQL。

历史 `paid_at` 为空的已完成订单保持未知时间，不会因退款、分账或回调重放而填入当前日期。新订单记录首次确认收款时间；退款保留首次成功退款时间。

After importing the SQL, start the app with database persistence enabled:

```bash
java -jar target/payment-gateway-0.1.0-SNAPSHOT.jar \
  --server.port=18081 \
  --payment.database.enabled=true \
  --payment.database.url="jdbc:mysql://127.0.0.1:3306/payment_gateway?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" \
  --payment.database.username=payment_gateway \
  --payment.database.password="YOUR_DATABASE_PASSWORD"
```

Do not commit the real database password into GitHub.

## Core Tables

- `pay_channel`: payment channel configuration
- `pay_channel_product`: enabled products for each channel
- `merchant`: merchant profile and signing keys
- `merchant_channel`: merchant allowed channels
- `pay_order`: payment orders
- `payment_attempt`: channel routing attempts
- `refund_order`: refund records
- `preauth_capture_order`: durable capture reservations and explicit parent/collection-order associations
- `preauth_unfreeze_order`: unfreeze reservations and confirmed released amounts
- `profit_sharing_order`: profit sharing records
- `settlement_record`: settlement batches
- `onboarding_record`: Alipay direct onboarding records
- `complaint_record`: complaint query records
- `complaint_auto_config`: automatic complaint query settings
- `merchant_payout`: Alipay and Douyin merchant payout records
