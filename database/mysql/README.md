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

## Spring Boot Startup

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
- `profit_sharing_order`: profit sharing records
- `settlement_record`: settlement batches
- `onboarding_record`: Alipay direct onboarding records
- `complaint_record`: complaint query records
- `complaint_auto_config`: automatic complaint query settings
