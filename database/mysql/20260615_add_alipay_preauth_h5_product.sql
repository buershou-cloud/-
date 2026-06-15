USE payment_gateway;

INSERT IGNORE INTO pay_channel_product (channel_id, product)
SELECT channel_id, 'ALIPAY_PREAUTH_H5'
FROM pay_channel_product
WHERE product = 'ALIPAY_PREAUTH';
