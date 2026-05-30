-- Payment Gateway MySQL schema
-- MySQL 8.x recommended. MySQL 5.7 can also run most of this script.

CREATE DATABASE IF NOT EXISTS payment_gateway
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE payment_gateway;

CREATE TABLE IF NOT EXISTS pay_channel (
  id VARCHAR(64) NOT NULL COMMENT '通道编号，例如 ali-main',
  provider VARCHAR(32) NOT NULL COMMENT '通道类型：ALIPAY、ALIPAY_DIRECT',
  enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '总开关',
  daily_enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '今日是否启用',
  priority INT NOT NULL DEFAULT 100 COMMENT '优先级，数字越小越优先',
  weight INT NOT NULL DEFAULT 1 COMMENT '权重',
  pay_min DECIMAL(18,2) NOT NULL DEFAULT 0.01 COMMENT '最小支付金额',
  pay_max DECIMAL(18,2) NOT NULL DEFAULT 50000.00 COMMENT '最大支付金额',
  gateway_url VARCHAR(255) NOT NULL DEFAULT 'https://openapi.alipay.com/gateway.do' COMMENT '支付宝网关',
  app_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '支付宝 AppID',
  mini_app_id VARCHAR(64) NULL COMMENT '支付宝小程序 AppID，用于 JSAPI 收银台',
  alipay_public_key TEXT NULL COMMENT '支付宝公钥',
  merchant_private_key MEDIUMTEXT NULL COMMENT '应用私钥',
  credential_mode VARCHAR(32) NOT NULL DEFAULT 'PUBLIC_KEY' COMMENT '支付宝验签模式：PUBLIC_KEY 或 CERTIFICATE',
  app_cert_sn VARCHAR(128) NULL COMMENT '应用公钥证书 SN',
  alipay_cert_sn VARCHAR(128) NULL COMMENT '支付宝公钥证书 SN',
  alipay_root_cert_sn VARCHAR(512) NULL COMMENT '支付宝根证书 SN',
  app_cert_content MEDIUMTEXT NULL COMMENT '应用公钥证书内容',
  alipay_cert_content MEDIUMTEXT NULL COMMENT '支付宝公钥证书内容',
  alipay_root_cert_content MEDIUMTEXT NULL COMMENT '支付宝根证书内容',
  app_auth_token TEXT NULL COMMENT '授权 token，直付通/服务商场景可用',
  sub_merchant_id VARCHAR(64) NULL COMMENT '直付通二级商户 SMID',
  notify_url VARCHAR(512) NULL COMMENT '默认异步通知地址',
  return_url VARCHAR(512) NULL COMMENT '默认同步返回地址',
  charset_name VARCHAR(32) NOT NULL DEFAULT 'UTF-8' COMMENT '字符集',
  sign_type VARCHAR(16) NOT NULL DEFAULT 'RSA2' COMMENT '签名方式',
  remark VARCHAR(255) NULL COMMENT '备注',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_pay_channel_provider (provider),
  KEY idx_pay_channel_enabled (enabled, daily_enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付通道';

CREATE TABLE IF NOT EXISTS pay_channel_product (
  channel_id VARCHAR(64) NOT NULL,
  product VARCHAR(64) NOT NULL COMMENT '支付产品枚举值',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (channel_id, product),
  CONSTRAINT fk_channel_product_channel
    FOREIGN KEY (channel_id) REFERENCES pay_channel(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通道支持的支付产品';

CREATE TABLE IF NOT EXISTS merchant (
  merchant_id VARCHAR(64) NOT NULL COMMENT '商户号',
  name VARCHAR(128) NOT NULL COMMENT '商户名称',
  fee_rate DECIMAL(8,4) NOT NULL DEFAULT 0.6000 COMMENT '费率百分比，例如 0.6000 表示 0.6%',
  status VARCHAR(32) NOT NULL DEFAULT '正常' COMMENT '正常、待审核、停用',
  settlement_status VARCHAR(32) NOT NULL DEFAULT '待结算' COMMENT '待结算、已结算',
  md5_key VARCHAR(128) NOT NULL COMMENT '商户 MD5 签名密钥',
  platform_public_key MEDIUMTEXT NULL COMMENT '平台 RSA2 公钥',
  rsa2_public_key MEDIUMTEXT NULL COMMENT '商户 RSA2 公钥',
  rsa2_private_key MEDIUMTEXT NULL COMMENT '商户 RSA2 私钥',
  sign_mode VARCHAR(32) NOT NULL DEFAULT 'MD5_RSA2' COMMENT 'MD5、RSA2、MD5_RSA2',
  routing_mode VARCHAR(32) NOT NULL DEFAULT 'ROUND_ROBIN' COMMENT 'ROUND_ROBIN、RANDOM、PRIORITY、WEIGHTED_RANDOM',
  login_password_hash VARCHAR(255) NULL COMMENT '商户后台密码哈希，可为空',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (merchant_id),
  KEY idx_merchant_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商户资料';

CREATE TABLE IF NOT EXISTS merchant_channel (
  merchant_id VARCHAR(64) NOT NULL,
  channel_id VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (merchant_id, channel_id),
  CONSTRAINT fk_merchant_channel_merchant
    FOREIGN KEY (merchant_id) REFERENCES merchant(merchant_id)
    ON DELETE CASCADE,
  CONSTRAINT fk_merchant_channel_channel
    FOREIGN KEY (channel_id) REFERENCES pay_channel(id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商户可用支付通道';

CREATE TABLE IF NOT EXISTS pay_order (
  out_trade_no VARCHAR(96) NOT NULL COMMENT '商户订单号',
  trade_no VARCHAR(96) NULL COMMENT '支付宝交易号',
  merchant_id VARCHAR(64) NOT NULL,
  channel_id VARCHAR(64) NULL,
  product VARCHAR(64) NOT NULL,
  subject VARCHAR(255) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'UNPAID' COMMENT 'UNPAID、COMPLETED、REFUNDED、FROZEN、CLOSED',
  pre_authorization TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否预授权订单',
  supplemented TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否手动补单',
  profit_shared TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已分账',
  buyer_id VARCHAR(96) NULL,
  buyer_open_id VARCHAR(128) NULL,
  auth_code VARCHAR(128) NULL,
  notify_url VARCHAR(512) NULL,
  return_url VARCHAR(512) NULL,
  app_auth_token TEXT NULL,
  settle_info JSON NULL,
  royalty_info JSON NULL,
  extra JSON NULL,
  raw_request JSON NULL,
  raw_response JSON NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  paid_at DATETIME NULL,
  frozen_at DATETIME NULL,
  refunded_at DATETIME NULL,
  closed_at DATETIME NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (out_trade_no),
  UNIQUE KEY uk_pay_order_trade_no (trade_no),
  KEY idx_pay_order_merchant_time (merchant_id, created_at),
  KEY idx_pay_order_channel_time (channel_id, created_at),
  KEY idx_pay_order_status (status),
  CONSTRAINT fk_pay_order_merchant
    FOREIGN KEY (merchant_id) REFERENCES merchant(merchant_id),
  CONSTRAINT fk_pay_order_channel
    FOREIGN KEY (channel_id) REFERENCES pay_channel(id)
    ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付订单';

CREATE TABLE IF NOT EXISTS payment_attempt (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  out_trade_no VARCHAR(96) NOT NULL,
  channel_id VARCHAR(64) NULL,
  success TINYINT(1) NOT NULL DEFAULT 0,
  code VARCHAR(64) NULL,
  message VARCHAR(512) NULL,
  raw_response JSON NULL,
  attempted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_payment_attempt_order (out_trade_no),
  KEY idx_payment_attempt_channel (channel_id),
  CONSTRAINT fk_payment_attempt_order
    FOREIGN KEY (out_trade_no) REFERENCES pay_order(out_trade_no)
    ON DELETE CASCADE,
  CONSTRAINT fk_payment_attempt_channel
    FOREIGN KEY (channel_id) REFERENCES pay_channel(id)
    ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付通道路由尝试记录';

CREATE TABLE IF NOT EXISTS refund_order (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  out_request_no VARCHAR(96) NOT NULL COMMENT '退款请求号',
  out_trade_no VARCHAR(96) NOT NULL,
  trade_no VARCHAR(96) NULL,
  merchant_id VARCHAR(64) NOT NULL,
  channel_id VARCHAR(64) NULL,
  refund_amount DECIMAL(18,2) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING、SUCCESS、FAILED',
  refund_reason VARCHAR(255) NULL,
  code VARCHAR(64) NULL,
  message VARCHAR(512) NULL,
  raw_response JSON NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at DATETIME NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_refund_order_request (out_request_no),
  KEY idx_refund_order_order (out_trade_no),
  KEY idx_refund_order_merchant_time (merchant_id, created_at),
  CONSTRAINT fk_refund_order_order
    FOREIGN KEY (out_trade_no) REFERENCES pay_order(out_trade_no),
  CONSTRAINT fk_refund_order_merchant
    FOREIGN KEY (merchant_id) REFERENCES merchant(merchant_id),
  CONSTRAINT fk_refund_order_channel
    FOREIGN KEY (channel_id) REFERENCES pay_channel(id)
    ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退款记录';

CREATE TABLE IF NOT EXISTS profit_sharing_order (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  out_request_no VARCHAR(96) NOT NULL COMMENT '分账请求号',
  out_trade_no VARCHAR(96) NOT NULL,
  trade_no VARCHAR(96) NULL,
  merchant_id VARCHAR(64) NOT NULL,
  channel_id VARCHAR(64) NULL,
  trans_in_type VARCHAR(32) NOT NULL DEFAULT 'loginName',
  trans_in VARCHAR(128) NOT NULL COMMENT '分账收入方账号',
  share_amount DECIMAL(18,2) NOT NULL,
  description VARCHAR(255) NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING、SUCCESS、FAILED',
  code VARCHAR(64) NULL,
  message VARCHAR(512) NULL,
  raw_request JSON NULL,
  raw_response JSON NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at DATETIME NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_profit_sharing_request (out_request_no),
  KEY idx_profit_sharing_order (out_trade_no),
  KEY idx_profit_sharing_channel (channel_id),
  CONSTRAINT fk_profit_sharing_order_order
    FOREIGN KEY (out_trade_no) REFERENCES pay_order(out_trade_no),
  CONSTRAINT fk_profit_sharing_order_merchant
    FOREIGN KEY (merchant_id) REFERENCES merchant(merchant_id),
  CONSTRAINT fk_profit_sharing_order_channel
    FOREIGN KEY (channel_id) REFERENCES pay_channel(id)
    ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='分账记录';

CREATE TABLE IF NOT EXISTS settlement_record (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  batch_no VARCHAR(96) NOT NULL COMMENT '结算批次号',
  merchant_id VARCHAR(64) NOT NULL,
  settlement_date DATE NOT NULL,
  trade_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00,
  refund_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00,
  fee_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00,
  settlement_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00,
  status VARCHAR(32) NOT NULL DEFAULT '待结算' COMMENT '待结算、已结算、结算失败',
  remark VARCHAR(255) NULL,
  settled_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_settlement_batch (batch_no),
  KEY idx_settlement_merchant_date (merchant_id, settlement_date),
  CONSTRAINT fk_settlement_merchant
    FOREIGN KEY (merchant_id) REFERENCES merchant(merchant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='商户结算记录';

CREATE TABLE IF NOT EXISTS onboarding_record (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  out_biz_no VARCHAR(96) NOT NULL COMMENT '外部业务号或 external_id',
  merchant_id VARCHAR(64) NULL COMMENT '本地商户号，可为空',
  channel_id VARCHAR(64) NULL,
  method_name VARCHAR(128) NOT NULL DEFAULT 'ant.merchant.expand.indirect.zft.simplecreate',
  app_auth_token TEXT NULL,
  request_body JSON NULL,
  response_body JSON NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING、SUCCESS、FAILED、AUDITING',
  code VARCHAR(64) NULL,
  message VARCHAR(512) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_onboarding_out_biz_no (out_biz_no),
  KEY idx_onboarding_channel (channel_id),
  KEY idx_onboarding_merchant (merchant_id),
  CONSTRAINT fk_onboarding_merchant
    FOREIGN KEY (merchant_id) REFERENCES merchant(merchant_id)
    ON DELETE SET NULL,
  CONSTRAINT fk_onboarding_channel
    FOREIGN KEY (channel_id) REFERENCES pay_channel(id)
    ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='直付通子商户进件记录';

CREATE TABLE IF NOT EXISTS complaint_record (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  complaint_id VARCHAR(128) NULL COMMENT '支付宝投诉单号',
  out_trade_no VARCHAR(96) NULL,
  trade_no VARCHAR(96) NULL,
  merchant_id VARCHAR(64) NULL,
  channel_id VARCHAR(64) NULL,
  status VARCHAR(64) NULL COMMENT '投诉状态',
  title VARCHAR(255) NULL,
  content TEXT NULL,
  raw_response JSON NULL,
  queried_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_complaint_id (complaint_id),
  KEY idx_complaint_order (out_trade_no),
  KEY idx_complaint_channel_time (channel_id, queried_at),
  KEY idx_complaint_merchant_time (merchant_id, queried_at),
  CONSTRAINT fk_complaint_order
    FOREIGN KEY (out_trade_no) REFERENCES pay_order(out_trade_no)
    ON DELETE SET NULL,
  CONSTRAINT fk_complaint_merchant
    FOREIGN KEY (merchant_id) REFERENCES merchant(merchant_id)
    ON DELETE SET NULL,
  CONSTRAINT fk_complaint_channel
    FOREIGN KEY (channel_id) REFERENCES pay_channel(id)
    ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='投诉查询记录';

CREATE TABLE IF NOT EXISTS complaint_auto_config (
  id TINYINT UNSIGNED NOT NULL DEFAULT 1,
  enabled TINYINT(1) NOT NULL DEFAULT 0,
  method_name VARCHAR(128) NULL,
  page_size INT NOT NULL DEFAULT 20,
  lookback_minutes INT NOT NULL DEFAULT 1440,
  app_auth_token TEXT NULL,
  extra JSON NULL,
  run_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
  last_message VARCHAR(512) NULL,
  last_started_at DATETIME NULL,
  last_finished_at DATETIME NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='投诉自动查询配置';

CREATE OR REPLACE VIEW v_merchant_order_summary AS
SELECT
  m.merchant_id,
  m.name,
  COUNT(o.out_trade_no) AS order_count,
  COALESCE(SUM(CASE WHEN o.status = 'COMPLETED' THEN o.amount ELSE 0 END), 0) AS completed_amount,
  COALESCE(SUM(CASE WHEN o.status = 'REFUNDED' THEN o.amount ELSE 0 END), 0) AS refunded_amount,
  COALESCE(SUM(CASE WHEN o.status = 'FROZEN' THEN o.amount ELSE 0 END), 0) AS frozen_amount,
  COALESCE(SUM(CASE WHEN o.status = 'UNPAID' THEN o.amount ELSE 0 END), 0) AS unpaid_amount
FROM merchant m
LEFT JOIN pay_order o ON o.merchant_id = m.merchant_id
GROUP BY m.merchant_id, m.name;

INSERT IGNORE INTO complaint_auto_config (id, enabled, page_size, lookback_minutes)
VALUES (1, 0, 20, 1440);
