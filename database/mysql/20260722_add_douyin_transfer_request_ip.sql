USE payment_gateway;

ALTER TABLE pay_channel
  ADD COLUMN douyin_transfer_request_ip VARCHAR(45) NULL
  COMMENT '抖音商家转账请求出口 IP'
  AFTER douyin_h5_app_name;
