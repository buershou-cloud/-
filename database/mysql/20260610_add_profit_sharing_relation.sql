USE payment_gateway;

CREATE TABLE IF NOT EXISTS profit_sharing_relation (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  channel_id VARCHAR(64) NOT NULL,
  receiver_type VARCHAR(32) NOT NULL DEFAULT 'loginName' COMMENT '收入方账号类型',
  receiver_account VARCHAR(128) NOT NULL COMMENT '收入方账号',
  receiver_name VARCHAR(128) NULL COMMENT '收入方名称',
  memo VARCHAR(255) NULL COMMENT '关系说明',
  out_request_no VARCHAR(96) NULL COMMENT '添加关系请求号',
  status VARCHAR(32) NOT NULL DEFAULT 'BOUND' COMMENT 'BOUND、DISABLED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_profit_relation_channel_account (channel_id, receiver_type, receiver_account),
  KEY idx_profit_relation_channel (channel_id),
  CONSTRAINT fk_profit_relation_channel
    FOREIGN KEY (channel_id) REFERENCES pay_channel(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分账关系';
