-- Explicit authorization-to-capture associations. Existing order names are never used to infer a relationship.
CREATE TABLE IF NOT EXISTS preauth_capture_order (
  capture_out_trade_no VARCHAR(96) NOT NULL,
  parent_out_trade_no VARCHAR(96) NOT NULL,
  channel_id VARCHAR(64) NOT NULL,
  auth_no VARCHAR(128) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  confirm_mode VARCHAR(32) NOT NULL COMMENT 'COMPLETE or NOT_COMPLETE',
  status VARCHAR(32) NOT NULL COMMENT 'PENDING, SUCCESS, FAILED',
  trade_no VARCHAR(128) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMP NULL,
  PRIMARY KEY (capture_out_trade_no),
  KEY idx_preauth_capture_parent_status (parent_out_trade_no, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='预授权实扣预留及明确关联';
