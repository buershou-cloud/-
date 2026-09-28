-- Outgoing split audit records. Deliberately no pay_order FK: external transactions are valid.
CREATE TABLE IF NOT EXISTS profit_sharing_record (
  channel_id VARCHAR(64) NOT NULL,
  out_request_no VARCHAR(96) NOT NULL,
  provider VARCHAR(32) NOT NULL,
  out_trade_no VARCHAR(96) NULL,
  trade_no VARCHAR(128) NULL,
  merchant_id VARCHAR(64) NULL,
  merchant_name VARCHAR(255) NULL,
  subject VARCHAR(255) NULL,
  share_amount DECIMAL(18,2) NULL,
  recipient VARCHAR(1024) NULL,
  status VARCHAR(32) NOT NULL,
  code VARCHAR(128) NULL,
  message VARCHAR(2048) NULL,
  raw_request LONGTEXT NULL,
  raw_response LONGTEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (channel_id, out_request_no),
  KEY idx_profit_sharing_record_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
