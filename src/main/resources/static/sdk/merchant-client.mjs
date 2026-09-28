// Server-side Node.js 20+ client. Never put merchant credentials in browser code.
import { createHash, createSign, createVerify, randomBytes, timingSafeEqual } from 'node:crypto';

const blank = /^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]*$/u;
const own = (object, key) => Object.prototype.hasOwnProperty.call(object, key);

function normalized(value) {
  if (value == null) return null;
  if (Array.isArray(value)) return value.map(normalized).filter(item => item !== null);
  if (typeof value === 'object') {
    const result = Object.create(null);
    for (const key of Object.keys(value).sort()) {
      const item = normalized(value[key]);
      if (key !== 'sign' && item !== null && !(typeof item === 'string' && blank.test(item))) result[key] = item;
    }
    return result;
  }
  if (typeof value === 'number' && !Number.isSafeInteger(value)) {
    throw new TypeError('Use decimal strings for non-integer/unsafe numbers, including nested amounts');
  }
  if (!['string', 'number', 'boolean'].includes(typeof value)) throw new TypeError('Only JSON values are supported');
  return value;
}

// Jackson-compatible escaping and UTF-16 key order, including numeric object keys.
function quote(text) {
  let result = '"';
  const escapes = { '"': '\\"', '\\': '\\\\', '\b': '\\b', '\f': '\\f', '\n': '\\n', '\r': '\\r', '\t': '\\t' };
  for (const char of text) {
    const code = char.charCodeAt(0);
    if (own(escapes, char)) result += escapes[char];
    else if (code < 32) result += '\\u' + code.toString(16).toUpperCase().padStart(4, '0');
    else if (char.length === 1 && code >= 0xD800 && code <= 0xDFFF) throw new TypeError('Unpaired Unicode surrogate');
    else result += char;
  }
  return result + '"';
}
function compact(value) {
  if (typeof value === 'string') return quote(value);
  if (Array.isArray(value)) return '[' + value.map(compact).join(',') + ']';
  if (value && typeof value === 'object') return '{' + Object.keys(value).sort().map(key => quote(key) + ':' + compact(value[key])).join(',') + '}';
  return JSON.stringify(value);
}

export function canonicalize(payload) {
  const values = normalized(payload);
  return Object.keys(values).sort().map(key => `${key}=${typeof values[key] === 'string' ? values[key] : compact(values[key])}`).join('&');
}

function pem(value, kind) {
  if (!value) throw new Error(`${kind} key is required`);
  if (value.includes('-----BEGIN')) return value;
  return `-----BEGIN ${kind} KEY-----\n${value.replace(/\s/g, '')}\n-----END ${kind} KEY-----`;
}

export function signPayload(payload, { md5Key, privateKey } = {}) {
  const content = canonicalize(payload);
  if (payload.signType === 'MD5') {
    if (!md5Key) throw new Error('MD5 key is required');
    return createHash('md5').update(content + md5Key, 'utf8').digest('hex').toUpperCase();
  }
  if (payload.signType === 'RSA2') return createSign('RSA-SHA256').update(content, 'utf8').end().sign(pem(privateKey, 'PRIVATE'), 'base64');
  throw new Error('signType must be MD5 or RSA2');
}

export function verifyPayload(payload, { md5Key, platformPublicKey, expectedSignType } = {}) {
  if (!payload || typeof payload.sign !== 'string' || (expectedSignType && payload.signType !== expectedSignType)) return false;
  try {
    if (payload.signType === 'MD5') {
      const expected = signPayload(payload, { md5Key });
      const actual = payload.sign.toUpperCase();
      return /^[0-9A-F]{32}$/.test(actual) && timingSafeEqual(Buffer.from(expected), Buffer.from(actual));
    }
    if (payload.signType === 'RSA2') return createVerify('RSA-SHA256').update(canonicalize(payload), 'utf8').end().verify(pem(platformPublicKey, 'PUBLIC'), payload.sign, 'base64');
  } catch { return false; }
  return false;
}

export function parseNotification(formBody) {
  const result = Object.create(null);
  for (const [key, value] of new URLSearchParams(formBody)) {
    if (own(result, key)) throw new Error(`Duplicate notification field: ${key}`);
    result[key] = value;
  }
  return result;
}

export function verifyNotification(formBody, credentials, expectedMerchantId) {
  const payload = parseNotification(formBody);
  if (!expectedMerchantId || payload.merchantId !== expectedMerchantId || !verifyPayload(payload, credentials)) {
    throw new Error('Notification identity/signature verification failed');
  }
  return payload; // Caller must atomically check its own order and amount before returning "success".
}

const fields = {
  check: [],
  pay: ['product', 'outTradeNo', 'subject', 'totalAmount', 'authCode', 'buyerId', 'buyerOpenId', 'quitUrl', 'timeoutExpress', 'notifyUrl', 'returnUrl', 'routingMode', 'channelIds', 'extra', 'settleInfo', 'royaltyInfo'],
  query: ['outTradeNo', 'tradeNo', 'channelIds'],
  cancel: ['outTradeNo', 'tradeNo', 'channelIds'],
  refund: ['outTradeNo', 'tradeNo', 'refundAmount', 'outRequestNo', 'refundReason', 'channelIds']
};

export class MerchantApiError extends Error {
  constructor(message, code, httpStatus) { super(message); this.name = 'MerchantApiError'; this.code = code; this.httpStatus = httpStatus; }
}

export class MerchantClient {
  constructor({ apiBase, merchantId, signType = 'MD5', md5Key, privateKey, platformPublicKey, timeoutMs = 15000, fetchImpl = globalThis.fetch }) {
    const base = new URL(apiBase);
    if (!['https:', 'http:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) throw new Error('Invalid merchant API base URL');
    if (!/\/api\/v1\/merchant-api\/?$/.test(base.pathname)) throw new Error('apiBase must end with /api/v1/merchant-api');
    if (base.protocol !== 'https:' && !['localhost', '127.0.0.1', '[::1]'].includes(base.hostname)) throw new Error('Use HTTPS outside localhost');
    if (!merchantId || !['MD5', 'RSA2'].includes(signType)) throw new Error('merchantId and a valid signType are required');
    if (signType === 'RSA2' && (!privateKey || !platformPublicKey)) throw new Error('RSA2 needs merchant private key and platform public key');
    if (signType === 'MD5' && !md5Key) throw new Error('MD5 key is required');
    this.apiBase = base.href.replace(/\/$/, '');
    this.merchantId = merchantId;
    this.signType = signType;
    this.credentials = { md5Key, privateKey, platformPublicKey };
    this.timeoutMs = timeoutMs;
    this.fetchImpl = fetchImpl;
  }

  buildRequest(operation, values = {}) {
    if (!own(fields, operation)) throw new Error('Unknown operation');
    for (const key of Object.keys(values)) if (!fields[operation].includes(key)) throw new Error(`Unsupported ${operation} field: ${key}`);
    for (const key of ['totalAmount', 'refundAmount']) {
      if (own(values, key) && (typeof values[key] !== 'string' || !/^(0|[1-9]\d*)\.\d{2}$/.test(values[key]) || /^0\.00$/.test(values[key]))) {
        throw new Error(`${key} must be a positive decimal string with two places, e.g. "1.00"`);
      }
    }
    const request = { ...values, merchantId: this.merchantId, signType: this.signType, timestamp: new Date().toISOString(), nonce: randomBytes(16).toString('hex') };
    request.sign = signPayload(request, this.credentials);
    return request;
  }

  async request(operation, values = {}) {
    const payload = this.buildRequest(operation, values);
    let response;
    try {
      response = await this.fetchImpl(`${this.apiBase}/${operation}`, {
        method: 'POST', redirect: 'error', signal: AbortSignal.timeout(this.timeoutMs),
        headers: { 'Content-Type': 'application/json; charset=utf-8', 'X-Merchant-Response-Signature': 'canonical-v1' },
        body: JSON.stringify(payload)
      });
    } catch (cause) {
      throw new MerchantApiError(`Network failure; payment/refund outcome may be unknown. Query the original order before any retry. ${cause.message}`, 'NETWORK_ERROR', 0);
    }
    let result;
    try { result = await response.json(); }
    catch { throw new MerchantApiError('Expected JSON; check the API base URL and reverse proxy', 'INVALID_RESPONSE', response.status); }
    if (!response.ok || result.code !== 'SUCCESS') throw new MerchantApiError(result.message || 'API request rejected', result.code || 'HTTP_ERROR', response.status);
    if (!verifyPayload(result, { ...this.credentials, expectedSignType: this.signType })) throw new MerchantApiError('Platform response signature verification failed; check platform key and server version', 'INVALID_RESPONSE_SIGNATURE', response.status);
    const responseTime = Date.parse(result.timestamp);
    if (!Number.isFinite(responseTime) || Math.abs(Date.now() - responseTime) > 15 * 60 * 1000) throw new MerchantApiError('Platform response timestamp is stale', 'STALE_RESPONSE', response.status);
    if (operation === 'check' && result.data?.merchantId !== this.merchantId) throw new MerchantApiError('Unexpected merchant in check response', 'RESPONSE_MISMATCH', response.status);
    if (values.outTradeNo && result.data?.outTradeNo !== values.outTradeNo) throw new MerchantApiError('Unexpected order in response', 'RESPONSE_MISMATCH', response.status);
    if (values.tradeNo && result.data?.tradeNo !== values.tradeNo) throw new MerchantApiError('Unexpected transaction in response', 'RESPONSE_MISMATCH', response.status);
    return result; // HTTP/code SUCCESS only means the API call succeeded; inspect data.status.
  }
}
