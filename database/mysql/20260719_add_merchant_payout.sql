USE payment_gateway;

CREATE TABLE IF NOT EXISTS merchant_payout (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  out_biz_no VARCHAR(32) NOT NULL COMMENT '平台代付单号，支付宝 out_biz_no / 抖音 out_bill_no',
  provider VARCHAR(32) NOT NULL COMMENT 'ALIPAY、DOUYIN',
  channel_id VARCHAR(64) NULL,
  recipient_type VARCHAR(32) NOT NULL COMMENT '收款标识类型',
  recipient_masked VARCHAR(255) NOT NULL COMMENT '脱敏后的收款标识',
  recipient_name_masked VARCHAR(128) NULL COMMENT '脱敏后的收款人姓名',
  amount DECIMAL(18,2) NOT NULL,
  order_title VARCHAR(64) NOT NULL,
  remark VARCHAR(200) NULL,
  transfer_scene_id VARCHAR(64) NULL COMMENT '抖音转账场景 ID',
  platform_order_no VARCHAR(128) NULL COMMENT '支付宝 order_id / 抖音 transfer_bill_no',
  platform_fund_order_no VARCHAR(128) NULL COMMENT '支付宝资金单号',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING、PROCESSING、UNKNOWN、SUCCESS、FAILED',
  code VARCHAR(64) NULL,
  message VARCHAR(512) NULL,
  fail_reason VARCHAR(512) NULL,
  raw_request JSON NULL COMMENT '不包含收款明文和支付密码的审计参数',
  raw_response JSON NULL,
  completed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_merchant_payout_out_biz_no (out_biz_no),
  KEY idx_merchant_payout_status_time (status, created_at),
  KEY idx_merchant_payout_channel_time (channel_id, created_at),
  CONSTRAINT fk_merchant_payout_channel
    FOREIGN KEY (channel_id) REFERENCES pay_channel(id)
    ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付宝及抖音商家代付记录';
