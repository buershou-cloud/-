-- Enable the Alipay payment-code product for existing standard Alipay channels.
-- It is only added to channels that already enabled ALIPAY_F2F because the
-- product uses the Alipay face-to-face barcode API.

USE payment_gateway;

INSERT IGNORE INTO pay_channel_product (channel_id, product)
SELECT channel_id, 'ALIPAY_PAYMENT_CODE'
FROM pay_channel_product
WHERE product = 'ALIPAY_F2F';
