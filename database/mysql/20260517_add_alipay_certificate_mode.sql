-- Add Alipay public-key certificate mode fields to an existing payment_gateway database.
-- Safe to run more than once.

USE payment_gateway;

DROP PROCEDURE IF EXISTS add_pay_channel_column_if_missing;

DELIMITER $$

CREATE PROCEDURE add_pay_channel_column_if_missing(
  IN column_name_value VARCHAR(64),
  IN alter_sql TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'pay_channel'
      AND column_name = column_name_value
  ) THEN
    SET @ddl = alter_sql;
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$

DELIMITER ;

CALL add_pay_channel_column_if_missing(
  'credential_mode',
  'ALTER TABLE pay_channel ADD COLUMN credential_mode VARCHAR(32) NOT NULL DEFAULT ''PUBLIC_KEY'' COMMENT ''支付宝验签模式：PUBLIC_KEY 或 CERTIFICATE'' AFTER merchant_private_key'
);
CALL add_pay_channel_column_if_missing(
  'app_cert_sn',
  'ALTER TABLE pay_channel ADD COLUMN app_cert_sn VARCHAR(128) NULL COMMENT ''应用公钥证书 SN'' AFTER credential_mode'
);
CALL add_pay_channel_column_if_missing(
  'alipay_cert_sn',
  'ALTER TABLE pay_channel ADD COLUMN alipay_cert_sn VARCHAR(128) NULL COMMENT ''支付宝公钥证书 SN'' AFTER app_cert_sn'
);
CALL add_pay_channel_column_if_missing(
  'alipay_root_cert_sn',
  'ALTER TABLE pay_channel ADD COLUMN alipay_root_cert_sn VARCHAR(512) NULL COMMENT ''支付宝根证书 SN'' AFTER alipay_cert_sn'
);
CALL add_pay_channel_column_if_missing(
  'app_cert_content',
  'ALTER TABLE pay_channel ADD COLUMN app_cert_content MEDIUMTEXT NULL COMMENT ''应用公钥证书内容'' AFTER alipay_root_cert_sn'
);
CALL add_pay_channel_column_if_missing(
  'alipay_cert_content',
  'ALTER TABLE pay_channel ADD COLUMN alipay_cert_content MEDIUMTEXT NULL COMMENT ''支付宝公钥证书内容'' AFTER app_cert_content'
);
CALL add_pay_channel_column_if_missing(
  'alipay_root_cert_content',
  'ALTER TABLE pay_channel ADD COLUMN alipay_root_cert_content MEDIUMTEXT NULL COMMENT ''支付宝根证书内容'' AFTER alipay_cert_content'
);

DROP PROCEDURE add_pay_channel_column_if_missing;
