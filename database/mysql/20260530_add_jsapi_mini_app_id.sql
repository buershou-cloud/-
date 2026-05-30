USE payment_gateway;

DROP PROCEDURE IF EXISTS add_pay_channel_mini_app_id_if_missing;

DELIMITER //
CREATE PROCEDURE add_pay_channel_mini_app_id_if_missing()
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'pay_channel'
      AND column_name = 'mini_app_id'
  ) THEN
    ALTER TABLE pay_channel
      ADD COLUMN mini_app_id VARCHAR(64) NULL COMMENT 'Alipay mini program AppID for JSAPI cashier' AFTER app_id;
  END IF;
END//
DELIMITER ;

CALL add_pay_channel_mini_app_id_if_missing();

DROP PROCEDURE add_pay_channel_mini_app_id_if_missing;
