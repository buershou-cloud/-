-- Remove the old demo rows that were used before the system switched to real MySQL data.
-- Run this after backing up the database if those demo rows already exist on the server.

USE payment_gateway;

DELETE FROM payment_attempt
WHERE out_trade_no LIKE 'P202605%' OR out_trade_no LIKE 'AUTH202605%';

DELETE FROM refund_order
WHERE out_trade_no LIKE 'P202605%' OR out_trade_no LIKE 'AUTH202605%';

DELETE FROM profit_sharing_order
WHERE out_trade_no LIKE 'P202605%' OR out_trade_no LIKE 'AUTH202605%';

DELETE FROM onboarding_record
WHERE out_biz_no LIKE 'ONBOARD202605%'
   OR out_biz_no = '105290059990194';

DELETE FROM pay_order
WHERE out_trade_no LIKE 'P202605%' OR out_trade_no LIKE 'AUTH202605%';

DELETE FROM settlement_record
WHERE merchant_id IN ('M10001', 'M10002', 'M10003')
   OR batch_no LIKE 'SETTLE-M1000%';

DELETE FROM merchant_channel
WHERE merchant_id IN ('M10001', 'M10002', 'M10003')
   OR channel_id IN ('ali-main', 'ali-backup', 'ali-direct');

DELETE FROM pay_channel_product
WHERE channel_id IN ('ali-main', 'ali-backup', 'ali-direct');

DELETE FROM merchant
WHERE merchant_id IN ('M10001', 'M10002', 'M10003')
   OR md5_key LIKE 'MD5_DEMO_%';

DELETE FROM pay_channel
WHERE id IN ('ali-main', 'ali-backup', 'ali-direct');
