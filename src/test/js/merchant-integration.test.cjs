const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const integration = require('../../main/resources/static/merchant-integration.js');
const page = fs.readFileSync(path.join(__dirname, '../../main/resources/static/merchant.html'), 'utf8');
const script = page.match(/<script>([\s\S]*?)<\/script>/)[1];
const merchant = { merchantId: 'M1001', name: 'Test merchant', feeRateText: '0.60%', signMode: 'MD5_RSA2',
  channelIds: ['ali', 'dy'], md5Key: 'SECRET_MD5_DO_NOT_SHARE', rsa2PrivateKey: 'SECRET_PRIVATE_DO_NOT_SHARE', platformPublicKey: 'PUBLIC' };

test('API base retains deployment context and honors the backend public URL', () => {
  assert.equal(integration.apiRoot('', 'https://pay.example/merchant.html'), 'https://pay.example/api/v1');
  assert.equal(integration.apiRoot('', 'https://pay.example/gateway/merchant.html'), 'https://pay.example/gateway/api/v1');
  assert.equal(integration.apiRoot('https://pay.example/api/v1', 'https://pay.example/gateway/merchant.html'), 'https://pay.example/gateway/api/v1');
  assert.equal(integration.apiRoot('/gateway/api/v1/', 'https://pay.example/gateway/merchant.html'), 'https://pay.example/gateway/api/v1');
  assert.equal(integration.apiRoot('https://public.example/prefix/api/v1', 'https://internal.example/merchant.html'), 'https://public.example/prefix/api/v1');
  assert.equal(integration.apiRoot('https://pay.example/gateway/api/v1/merchant-api/', 'https://pay.example/gateway/merchant.html'), 'https://pay.example/gateway/api/v1');
  assert.equal(integration.apiRoot('javascript:alert(1)', 'https://pay.example/merchant.html'), 'https://pay.example/api/v1');
});

test('all endpoint and download URLs use the same deployment base', () => {
  const base = 'https://pay.example/gateway/api/v1';
  assert.deepEqual(integration.endpoints(base).map(item => item.operation), ['check', 'pay', 'query', 'cancel', 'refund']);
  assert.ok(integration.endpoints(base).every(item => item.url.startsWith(`${base}/merchant-api/`) && item.method === 'POST'));
  assert.equal(integration.assetUrl(base, '/sdk/merchant-client.mjs'), 'https://pay.example/gateway/sdk/merchant-client.mjs');
  assert.equal(integration.assetUrl(base, 'docs/merchant-api.md'), 'https://pay.example/gateway/docs/merchant-api.md');
});

test('merchant product choices exclude unbound, disabled, and daily-disabled channels', () => {
  const channels = [
    { id: 'ali', provider: 'ALIPAY', notifyUrl: 'https://gateway.example/notify', enabled: true, dailyEnabled: true, products: ['ALIPAY_PAGE', 'ALIPAY_F2F'] },
    { id: 'dy', provider: 'DOUYIN', douyinNotifyUrl: 'https://gateway.example/notify', enabled: true, dailyEnabled: true, products: ['DOUYIN_H5'] },
    { id: 'other', provider: 'DOUYIN', douyinNotifyUrl: 'https://gateway.example/notify', enabled: true, dailyEnabled: true, products: ['DOUYIN_NATIVE'] },
    { id: 'ali', provider: 'ALIPAY', notifyUrl: 'https://gateway.example/notify', enabled: false, dailyEnabled: true, products: ['ALIPAY_APP'] },
    { id: 'dy', provider: 'DOUYIN', douyinNotifyUrl: 'https://gateway.example/notify', enabled: true, dailyEnabled: false, products: ['DOUYIN_NATIVE'] }
  ];
  assert.deepEqual(integration.availableProducts(merchant, channels), ['ALIPAY_F2F', 'ALIPAY_PAGE', 'DOUYIN_H5']);
  assert.deepEqual(integration.availableProducts({ ...merchant, channelIds: [] }, channels), []);
  assert.deepEqual(integration.availableProducts(merchant, null), []);
});

test('empty channel product lists use provider defaults without offering other providers', () => {
  assert.deepEqual(integration.availableProducts(merchant, [{ id: 'dy', provider: 'DOUYIN', douyinNotifyUrl: 'https://gateway.example/notify', enabled: true, dailyEnabled: true, products: [] }]), ['DOUYIN_H5', 'DOUYIN_NATIVE']);
  assert.deepEqual(integration.availableProducts(merchant, [{ id: 'dy', provider: 'DOUYIN', douyinNotifyUrl: 'https://gateway.example/notify', enabled: true, dailyEnabled: true, products: ['DOUYIN_H5', 'ALIPAY_PAGE'] }]), ['DOUYIN_H5']);
});

test('channels without a valid gateway callback are not advertised as ready to receive payments', () => {
  for (const callback of [undefined, '', 'ftp://gateway.example/notify', 'https://user:pass@gateway.example/notify', 'https://gateway.example/notify#fragment']) {
    assert.deepEqual(integration.availableProducts(merchant, [{ id: 'dy', provider: 'DOUYIN', enabled: true, dailyEnabled: true, products: ['DOUYIN_H5'], douyinNotifyUrl: callback }]), []);
  }
});

test('shareable checklist excludes all signing credentials and includes concrete operation URLs', () => {
  const text = integration.checklist(merchant, 'https://pay.example/api/v1', ['DOUYIN_H5']);
  assert.doesNotMatch(text, /SECRET_|PRIVATE KEY/);
  assert.match(text, /商户号：M1001/);
  assert.match(text, /merchant-api\/check/);
  assert.match(text, /merchant-api\/refund/);
  assert.match(text, /data.status/);
  assert.match(text, /DOUYIN_H5/);
});

test('examples use current timestamps, merchant sign mode, string amounts and merchant callback addresses', () => {
  const examples = integration.examples({ ...merchant, signMode: 'RSA2' }, 'DOUYIN_H5', new Date('2026-10-01T01:02:03Z'));
  assert.equal(examples.pay.signType, 'RSA2');
  assert.equal(examples.pay.timestamp, '2026-10-01T01:02:03.000Z');
  assert.equal(examples.pay.totalAmount, '1.00');
  assert.equal(examples.refund.refundAmount, '1.00');
  assert.equal(examples.pay.product, 'DOUYIN_H5');
  assert.equal(examples.pay.notifyUrl, 'https://merchant.example/payments/notify');
  assert.equal(examples.pay.extra, undefined);
  assert.doesNotMatch(JSON.stringify(examples), /SECRET_/);
});

function setupPage() {
  const ids = [...page.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
  assert.equal(new Set(ids).size, ids.length, 'Duplicate page element IDs');
  const elements = Object.fromEntries(ids.map(id => [id, { value: '', textContent: '', innerHTML: '',
    listeners: {}, classList: { add() {}, remove() {}, toggle() {} },
    addEventListener(name, listener) { this.listeners[name] = listener; }, setAttribute() {} }]));
  const fetches = [];
  const ctx = vm.createContext({ MerchantIntegration: integration, URLSearchParams, URL, Date,
    document: { getElementById: id => elements[id], querySelectorAll: () => [] },
    location: { search: '' }, window: { location: { href: 'https://pay.example/tenant/merchant.html' } },
    navigator: { clipboard: { writeText: async () => {} } },
    fetch: async url => { fetches.push(url); return { ok: true, text: async () => '[]' }; }
  });
  vm.runInContext(script, ctx);
  return { ctx, elements, fetches };
}

test('portal renders endpoints and samples without copying real keys into integration content', () => {
  const { ctx, elements } = setupPage();
  elements.integrationProduct.value = 'DOUYIN_H5';
  ctx.updateMerchantFields(merchant, 'https://pay.example/tenant/api/v1');
  assert.equal(elements.apiBase.value, 'https://pay.example/tenant/api/v1/merchant-api');
  assert.equal(elements.md5Key.value, merchant.md5Key);
  assert.equal(elements.rsaPrivateKey.value, merchant.rsa2PrivateKey);
  assert.equal(elements.sdkClientDownload.href, 'https://pay.example/tenant/sdk/merchant-client.mjs');
  for (const [id, element] of Object.entries(elements)) {
    if (/^(integration|doc)/.test(id)) assert.doesNotMatch(element.innerHTML + element.textContent + element.value, /SECRET_/);
  }
  assert.match(elements.integrationEndpoints.innerHTML, /merchant-api\/cancel/);
  assert.equal(JSON.parse(elements.docResponseExample.textContent).data.status, 'PENDING');
  assert.equal(JSON.parse(elements.docCallbackExample.textContent).status, 'COMPLETED');
  assert.equal(JSON.parse(elements.docPayBusinessExample.textContent).sign, undefined);
});

test('merchant page login and follow-up API requests retain the deployment prefix', async () => {
  const { ctx, fetches } = setupPage();
  await ctx.request('/api/v1/channels');
  assert.equal(fetches[0], 'https://pay.example/tenant/api/v1/channels');
});

test('docs describe actual protocol and no stale direct-payment route or V1/V2 signature branding remains', () => {
  new vm.Script(script);
  assert.doesNotMatch(page, /V1接口|V2接口|V1 使用|V2 使用|\/api\/v1\/payments\/(pay|query|refund)|2026-06-01T12:00:00Z/);
  assert.match(page, /application\/x-www-form-urlencoded/);
  assert.match(page, /canonical-v1/);
  assert.match(page, /HTTP 2xx/);
  assert.match(page, /id="resetMd5Btn"/);
  assert.match(page, /id="generateRsaBtn"/);
});
