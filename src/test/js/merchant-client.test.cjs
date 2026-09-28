const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createHash, generateKeyPairSync } = require('node:crypto');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const sdk = import('../../main/resources/static/sdk/merchant-client.mjs');
const vectors = JSON.parse(readFileSync(path.join(__dirname, '../resources/merchant-signature-vectors.json'), 'utf8'));

for (const vector of vectors) test(`independent canonical vector: ${vector.name}`, async () => {
  const { canonicalize, signPayload, verifyPayload } = await sdk;
  assert.equal(canonicalize(vector.payload), vector.canonical);
  const sign = createHash('md5').update(vector.canonical + vector.key, 'utf8').digest('hex').toUpperCase();
  assert.equal(signPayload(vector.payload, { md5Key: vector.key }), sign);
  assert.ok(verifyPayload({ ...vector.payload, sign }, { md5Key: vector.key }));
});

test('RSA request and platform signatures use their separate keypairs', async () => {
  const { signPayload, verifyPayload } = await sdk;
  const options = { modulusLength: 2048, publicKeyEncoding: { type: 'spki', format: 'pem' }, privateKeyEncoding: { type: 'pkcs8', format: 'pem' } };
  const merchant = generateKeyPairSync('rsa', options);
  const platform = generateKeyPairSync('rsa', options);
  const payload = { signType: 'RSA2', data: { status: 'SUCCESS', amount: '1.00' } };
  const sign = signPayload(payload, { privateKey: platform.privateKey });
  assert.ok(verifyPayload({ ...payload, sign }, { platformPublicKey: platform.publicKey }));
  assert.equal(verifyPayload({ ...payload, sign }, { platformPublicKey: merchant.publicKey }), false);
  assert.equal(verifyPayload({ ...payload, sign, data: { amount: '9.00' } }, { platformPublicKey: platform.publicKey }), false);
});

test('notification parsing decodes form plus correctly and rejects duplicates', async () => {
  const { signPayload, verifyNotification } = await sdk;
  const payload = { ...vectors[2].payload, productName: 'A+B 商品' };
  payload.sign = signPayload(payload, { md5Key: vectors[2].key });
  const body = new URLSearchParams(payload).toString();
  assert.equal(verifyNotification(body, { md5Key: vectors[2].key }, 'M1001').productName, 'A+B 商品');
  assert.throws(() => verifyNotification(body, { md5Key: vectors[2].key }, 'OTHER'));
  assert.throws(() => verifyNotification(body + '&status=FAILED', { md5Key: vectors[2].key }, 'M1001'), /Duplicate/);
});

test('client sends signed check, validates response and never retries payment', async () => {
  const { MerchantClient, signPayload } = await sdk;
  const options = { apiBase: 'https://pay.example/gateway/api/v1/merchant-api', merchantId: 'M1001', md5Key: 'test-key' };
  let calls = 0;
  const client = new MerchantClient({ ...options, fetchImpl: async (url, request) => {
    calls++;
    assert.equal(url, options.apiBase + '/check');
    assert.equal(request.headers['X-Merchant-Response-Signature'], 'canonical-v1');
    const data = JSON.parse(request.body);
    assert.equal(data.sign, signPayload(data, options));
    const response = { code: 'SUCCESS', message: 'OK', data: { merchantId: 'M1001' }, timestamp: new Date().toISOString(), signType: 'MD5' };
    response.sign = signPayload(response, options);
    return { ok: true, status: 200, json: async () => response };
  }});
  assert.equal((await client.request('check')).data.merchantId, 'M1001');
  assert.equal(calls, 1);
  assert.notEqual(client.buildRequest('check').nonce, client.buildRequest('check').nonce);
  assert.throws(() => client.buildRequest('pay', { totalAmount: 1 }), /decimal string/);
  assert.throws(() => client.buildRequest('pay', { totalAmount: '1.00', extra: { amount: 1.5 } }), /decimal strings/);
  assert.throws(() => client.buildRequest('pay', { unknown: 'x' }), /Unsupported/);
  let attempts = 0;
  const broken = new MerchantClient({ ...options, fetchImpl: async () => { attempts++; throw new Error('timeout'); } });
  await assert.rejects(() => broken.request('pay', { outTradeNo: 'M1001_001', totalAmount: '1.00' }), { code: 'NETWORK_ERROR' });
  assert.equal(attempts, 1);
});

test('client rejects unsigned success and stale or mismatched responses', async () => {
  const { MerchantClient, signPayload } = await sdk;
  const options = { apiBase: 'https://pay.example/api/v1/merchant-api', merchantId: 'M1001', md5Key: 'test-key' };
  let data = { code: 'SUCCESS', data: {}, timestamp: new Date().toISOString(), signType: 'MD5' };
  const client = new MerchantClient({ ...options, fetchImpl: async () => ({ ok: true, status: 200, json: async () => data }) });
  await assert.rejects(() => client.request('check'), { code: 'INVALID_RESPONSE_SIGNATURE' });
  data = { ...data, data: { merchantId: 'OTHER' } };
  data.sign = signPayload(data, options);
  await assert.rejects(() => client.request('check'), { code: 'RESPONSE_MISMATCH' });
  data = { ...data, data: { tradeNo: 'OTHER_TRADE' } };
  data.sign = signPayload(data, options);
  await assert.rejects(() => client.request('query', { tradeNo: 'MY_TRADE' }), { code: 'RESPONSE_MISMATCH' });
  data = { ...data, timestamp: '2020-01-01T00:00:00Z' };
  data.sign = signPayload(data, options);
  await assert.rejects(() => client.request('check'), { code: 'STALE_RESPONSE' });
});
