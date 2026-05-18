# MySQL Database

This folder contains the MySQL schema for the payment gateway.

## Import

```bash
mysql -uroot -p < database/mysql/payment_gateway_schema.sql
```

The script creates database `payment_gateway`, all core tables, a merchant order summary view, and the complaint auto query config row. It does not insert demo channels, merchants, or orders.

After import, add real channels and merchants from the admin pages.

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
