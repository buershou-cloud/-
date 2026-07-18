USE payment_gateway;

ALTER TABLE pay_channel
  ADD COLUMN douyin_gateway_url VARCHAR(255) NULL DEFAULT 'https://api.douyinpay.com' COMMENT '抖音支付网关' AFTER sign_type,
  ADD COLUMN douyin_app_id VARCHAR(64) NULL COMMENT '抖音支付网站应用 AppID' AFTER douyin_gateway_url,
  ADD COLUMN douyin_mch_id VARCHAR(64) NULL COMMENT '抖音支付直连商户号' AFTER douyin_app_id,
  ADD COLUMN douyin_merchant_serial_no VARCHAR(128) NULL COMMENT '商户 API 证书序列号' AFTER douyin_mch_id,
  ADD COLUMN douyin_merchant_private_key MEDIUMTEXT NULL COMMENT '抖音支付商户私钥' AFTER douyin_merchant_serial_no,
  ADD COLUMN douyin_platform_certificate MEDIUMTEXT NULL COMMENT '抖音支付平台证书' AFTER douyin_merchant_private_key,
  ADD COLUMN douyin_encrypt_key VARCHAR(128) NULL COMMENT '抖音支付接口加密密钥' AFTER douyin_platform_certificate,
  ADD COLUMN douyin_notify_url VARCHAR(512) NULL COMMENT '抖音支付异步通知地址' AFTER douyin_encrypt_key,
  ADD COLUMN douyin_return_url VARCHAR(512) NULL COMMENT '抖音 H5 支付完成返回地址' AFTER douyin_notify_url,
  ADD COLUMN douyin_h5_app_name VARCHAR(127) NULL DEFAULT '支付平台' COMMENT 'H5 场景应用名称' AFTER douyin_return_url;

