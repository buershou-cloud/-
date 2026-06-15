CREATE TABLE IF NOT EXISTS preauth_unfreeze_order (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  out_request_no VARCHAR(96) NOT NULL COMMENT '预授权解冻请求号',
  out_trade_no VARCHAR(96) NOT NULL,
  auth_no VARCHAR(128) NOT NULL COMMENT '支付宝预授权号',
  channel_id VARCHAR(64) NULL,
  amount DECIMAL(18,2) NOT NULL COMMENT '解冻金额',
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING、SUCCESS、FAILED',
  remark VARCHAR(255) NULL,
  code VARCHAR(64) NULL,
  message VARCHAR(512) NULL,
  raw_response JSON NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at DATETIME NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_preauth_unfreeze_request (out_request_no),
  KEY idx_preauth_unfreeze_order (out_trade_no),
  KEY idx_preauth_unfreeze_channel_time (channel_id, created_at),
  CONSTRAINT fk_preauth_unfreeze_order
    FOREIGN KEY (out_trade_no) REFERENCES pay_order(out_trade_no)
    ON DELETE CASCADE,
  CONSTRAINT fk_preauth_unfreeze_channel
    FOREIGN KEY (channel_id) REFERENCES pay_channel(id)
    ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预授权解冻记录';
