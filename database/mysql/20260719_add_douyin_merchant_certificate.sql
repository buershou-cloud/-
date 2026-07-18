USE payment_gateway;

ALTER TABLE pay_channel
  ADD COLUMN douyin_merchant_certificate MEDIUMTEXT NULL
    COMMENT 'Douyin Pay merchant API certificate'
    AFTER douyin_merchant_serial_no;
