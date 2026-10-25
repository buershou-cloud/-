const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const page = fs.readFileSync(path.join(__dirname, '../../main/resources/static/index.html'), 'utf8');
const script = page.match(/<script>([\s\S]*?)<\/script>/)[1];
function pageFunction(name) {
  const start = script.search(new RegExp(`^  (?:async )?function ${name}\\(`, 'm'));
  assert.notEqual(start, -1, `Missing ${name}`);
  return script.slice(start, script.indexOf('\n  }', start) + 4);
}

function setup(options = {}) {
  const channel = { id: 'C1', provider: 'ALIPAY', appId: 'APP1', products: ['ALIPAY_PAGE', 'ALIPAY_F2F'],
    notifyUrl: 'https://original.example/api/v1/alipay/notify/C1', returnUrl: 'https://original.example/return' };
  const state = { channels: [channel], editingChannelId: 'C1' };
  const elements = new Proxy({}, { get(target, key) {
    if (!target[key]) {
      const classes = new Set();
      target[key] = { value: '', innerHTML: '', textContent: '', disabled: false,
        classList: { add: x => classes.add(x), remove: x => classes.delete(x), contains: x => classes.has(x) },
        removeAttribute(name) { delete this[name]; }, querySelector: () => ({ focus() {} }), scrollIntoView() {} };
    }
    return target[key];
  }});
  const preference = new Map([['C1', 'ALIPAY_PAGE']]);
  const stored = new Map();
  const requests = [];
  const errors = [];
  const context = vm.createContext({ state, URLSearchParams, Date, $: id => elements[id],
    cashierProductByChannel: preference, cashierProductStorageKey: 'cashier-products',
    localStorage: { setItem: (key, value) => stored.set(key, value) },
    standardProductIds: ['ALIPAY_PAGE', 'ALIPAY_F2F', 'ALIPAY_JSAPI'], directProductIds: [], douyinProductIds: [],
    productLabels: { ALIPAY_PAGE: '电脑支付', ALIPAY_F2F: '扫码支付', ALIPAY_JSAPI: 'JSAPI' },
    location: { origin: 'https://preview.example' }, renderChannels() {},
    setChannelFormResult: error => errors.push(error), setConsole() {},
    channelFormPayload: async () => ({ id: 'requested-name', products: [...channel.products] }),
    closeChannelModal: () => { state.editingChannelId = null; }, loadChannels: async () => {},
    request: async (url, config) => { requests.push({ url, config }); return options.request?.(url, config); },
    alert: message => { throw Error(message); }
  });
  const functions = ['html', 'channelProductIds', 'enabledChannelProducts', 'cashierEligibleProducts',
    'cashierProductSelectId', 'cashierProductPickerHtml', 'rememberCashierProduct', 'selectedProductForChannel',
    'cashierUrl', 'isJsapiCashierProduct', 'showCashierQr', 'selectCashierQrProduct',
    'migrateCashierProductPreference', 'submitChannelForm'];
  vm.runInContext(functions.map(pageFunction).join('\n'), context);
  return { context, channel, state, elements, preference, stored, requests, errors };
}

test('changing the QR product only updates local preference and the QR preview, never channel configuration', () => {
  const { context, channel, elements, preference, stored, requests } = setup();
  const original = structuredClone(channel);
  context.showCashierQr('C1');
  assert.match(elements.qrProductPicker.innerHTML, /data-channel-cashier-product="C1"/);
  assert.equal(context.selectCashierQrProduct('C1', 'ALIPAY_F2F'), true);
  assert.equal(preference.get('C1'), 'ALIPAY_F2F');
  assert.deepEqual(JSON.parse(stored.get('cashier-products')), [['C1', 'ALIPAY_F2F']]);
  assert.match(elements.qrLink.textContent, /channelId=C1&product=ALIPAY_F2F/);
  assert.match(elements.qrImage.src, /^\/api\/v1\/qrcode\?/);
  assert.deepEqual(channel, original, 'callback addresses and enabled products must not be changed');
  assert.deepEqual(requests, [], 'a local QR preference must not issue PATCH, payment, or configuration requests');
  assert.doesNotMatch(page, /id="channelEditorCashierProduct"/);
});

test('QR product choice rejects disabled products and unknown channels without changing preference', () => {
  const { context, preference, requests } = setup();
  assert.equal(context.selectCashierQrProduct('C1', 'ALIPAY_JSAPI'), false);
  assert.equal(context.selectCashierQrProduct('missing', 'ALIPAY_PAGE'), false);
  assert.equal(preference.get('C1'), 'ALIPAY_PAGE');
  assert.deepEqual(requests, []);
});

test('a JSAPI configuration warning leaves product selection available without displaying a stale QR', () => {
  const { context, channel, elements, preference, requests } = setup();
  channel.products.push('ALIPAY_JSAPI');
  channel.appId = '';
  context.showCashierQr('C1');
  assert.equal(context.selectCashierQrProduct('C1', 'ALIPAY_JSAPI'), true);
  assert.match(elements.qrProductPicker.innerHTML, /ALIPAY_F2F/);
  assert.equal(elements.qrImage.src, undefined);
  assert.equal(elements.qrImage.classList.contains('hidden'), true);
  assert.match(elements.qrLink.textContent, /配置支付宝 appId/);
  assert.equal(context.selectCashierQrProduct('C1', 'ALIPAY_F2F'), true);
  assert.equal(elements.qrImage.classList.contains('hidden'), false);
  assert.equal(preference.get('C1'), 'ALIPAY_F2F');
  assert.deepEqual(requests, []);
});

test('successful rename migrates an existing preference using the actual response ID', async () => {
  const renamed = { id: 'server-confirmed-name', provider: 'ALIPAY', products: ['ALIPAY_PAGE', 'ALIPAY_F2F'] };
  const { context, preference, stored, requests } = setup({ request: () => renamed });
  preference.set('C1', 'ALIPAY_F2F');
  await context.submitChannelForm();
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, '/api/v1/channels/C1');
  assert.equal(requests[0].config.method, 'PATCH');
  assert.equal(preference.has('C1'), false);
  assert.equal(preference.has('requested-name'), false);
  assert.equal(preference.get('server-confirmed-name'), 'ALIPAY_F2F');
  assert.deepEqual(JSON.parse(stored.get('cashier-products')), [['server-confirmed-name', 'ALIPAY_F2F']]);
});

test('rename migration cannot retain a product disabled by the successful update', () => {
  const { context, preference } = setup();
  context.migrateCashierProductPreference('C1', { id: 'C2', provider: 'ALIPAY', products: ['ALIPAY_F2F'] });
  assert.equal(preference.has('C1'), false);
  assert.equal(preference.get('C2'), 'ALIPAY_F2F');
});

test('failed rename keeps the original local preference', async () => {
  const { context, preference, stored, errors } = setup({ request: () => { throw Error('rename rejected'); } });
  await context.submitChannelForm();
  assert.equal(preference.get('C1'), 'ALIPAY_PAGE');
  assert.equal(preference.size, 1);
  assert.equal(stored.size, 0);
  assert.equal(errors.at(-1).message, 'rename rejected');
});
