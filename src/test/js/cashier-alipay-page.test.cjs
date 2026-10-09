const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const page = fs.readFileSync(path.join(__dirname, '../../main/resources/static/cashier.html'), 'utf8');
const script = page.match(/<script>([\s\S]*?)<\/script>/)[1];
const officialQr = 'https://qr.alipay.com/upx0123456789abcdef';

async function openCashier({ product = 'ALIPAY_PAGE', userAgent = 'iPhone Mobile Safari', merchant = false,
  storage = new Map(), storageApi, response, payError, pendingPay, clock, url: pageUrl, fill = true } = {}) {
  const location = new URL(pageUrl || `https://pay.example/cashier.html?${merchant ? 'merchantId=M1' : 'channelId=ali-page'}&product=${product}`);
  const calls = [], timers = [], bridgeCalls = [], formSubmissions = [], clearedTimers = [];
  const classes = {};
  const elements = Object.fromEntries([...page.matchAll(/\bid="([^"]+)"/g)].map(match => {
    const id = match[1];
    classes[id] = new Set();
    return [id, {
      value: id === 'subject' ? '扫码收银台支付' : '', checked: id === 'profitSharingEnabled',
      disabled: false, hidden: id === 'newMobilePagePaymentButton', style: {}, listeners: {}, src: '', textContent: '', innerHTML: '',
      classList: { add(value) { classes[id].add(value); }, remove(value) { classes[id].delete(value); } },
      removeAttribute(name) { if (name === 'src') this.src = ''; },
      addEventListener(event, callback) { (this.listeners[event] ||= []).push(callback); }
    }];
  }));
  let originalSignedParams, originalHtml, form;
  elements.paymentFormHolder.querySelector = selector => selector === 'form' && elements.paymentFormHolder.innerHTML ? form : null;
  const window = { location, generatedOrderNos: 0, sessionStorage: storageApi || {
    getItem(key) { return storage.get(key) ?? null; }, setItem(key, value) { storage.set(key, value); },
    removeItem(key) { storage.delete(key); }
  }, AlipayJSBridge: {
    call(...args) { bridgeCalls.push(args); }
  } };
  const context = vm.createContext({ URL, URLSearchParams, AbortController,
    Date: clock ? class extends Date { static now() { return clock.now; } } : Date,
    navigator: { userAgent }, location, window,
    document: { getElementById: id => elements[id], addEventListener() {} },
    setTimeout(callback, delay) { timers.push({ callback, delay }); return timers.length; },
    clearTimeout(id) { clearedTimers.push(id); },
    fetch: async (url, options = {}) => {
      const body = options.body ? JSON.parse(options.body) : undefined;
      calls.push({ url, method: options.method || 'GET', body });
      let data;
      if (url === '/api/v1/channels') data = [
        { id: 'ali-page', provider: product === 'ALIPAY_DIRECT_PAGE' ? 'ALIPAY_DIRECT' : 'ALIPAY',
          enabled: true, dailyEnabled: true, products: [product, 'ALIPAY_WAP'], appId: 'APP_ID' },
        { id: 'other-channel', provider: 'DOUYIN', enabled: true, products: ['DOUYIN_H5'] }
      ];
      else if (url === '/api/v1/merchants/M1/cashier-info') {
        data = { merchantId: 'M1', name: '测试商户', channelIds: ['ali-page'] };
      } else if (url === '/api/v1/payments/pay') {
        if (payError) throw payError;
        if (pendingPay) return pendingPay({ url, options, body });
        if (response) return { ok: response.httpStatus ? response.httpStatus < 400 : true,
          text: async () => JSON.stringify({ outTradeNo: body.outTradeNo, channelId: 'ali-page', ...response }) };
        if (product === 'ALIPAY_PAGE' && /AlipayClient|Android|iPhone|iPad|iPod|Mobile/i.test(userAgent)) {
          data = { status: 'CREATED', code: 'PAGE_QR_CREATED', channelId: 'ali-page', outTradeNo: body.outTradeNo, qrCode: officialQr };
          return { ok: true, text: async () => JSON.stringify(data) };
        }
        originalSignedParams = {
          method: 'alipay.trade.page.pay', app_id: 'APP_ID', sign_type: 'RSA2',
          charset: 'UTF-8', sign: 'original+signature/value=',
          biz_content: JSON.stringify({
            out_trade_no: body.outTradeNo, subject: body.subject, total_amount: body.totalAmount.toFixed(2),
            product_code: 'FAST_INSTANT_TRADE_PAY',
            ...(body.extra.integration_type ? { integration_type: body.extra.integration_type,
              request_from_url: body.extra.request_from_url } : {})
          })
        };
        const inputs = Object.entries(originalSignedParams).map(([name, value]) => ({ name, value }));
        const attributes = { action: 'https://openapi.alipay.com/gateway.do?charset=UTF-8', method: 'POST' };
        form = {
          getAttribute(name) { return attributes[name]; },
          setAttribute(name, value) { attributes[name] = value; },
          querySelectorAll(selector) { assert.equal(selector, 'input[name]'); return inputs; },
          submit() {
            formSubmissions.push({ attributes: { ...attributes }, params: Object.fromEntries(inputs.map(input => [input.name, input.value])) });
          }
        };
        originalHtml = '<form name="alipay_submit" action="https://openapi.alipay.com/gateway.do?charset=UTF-8" method="POST"><!-- original signed inputs --></form>';
        data = { status: 'CREATED', channelId: 'ali-page', outTradeNo: body.outTradeNo, redirectHtml: originalHtml };
      } else throw Error(`Unexpected request ${url}`);
      return { ok: true, text: async () => JSON.stringify(data) };
    }
  });
  vm.runInContext(script, context);
  vm.runInContext('orderNo = () => { window.generatedOrderNos++; return "ORIGINAL_PC_ORDER_" + String(window.generatedOrderNos).padStart(3, "0"); };', context);
  await new Promise(resolve => setImmediate(resolve));
  if (fill && !elements.amount.disabled) {
    elements.amount.value = '0.10';
    elements.subject.value = '手机扫码原产品 & 订单';
  }
  const emit = async (id, event) => {
    for (const callback of elements[id].listeners[event] || []) await callback({ preventDefault() {} });
  };
  return { calls, timers, clearedTimers, bridgeCalls, formSubmissions, elements, window, classes, storage, context,
    submit: () => emit('cashierForm', 'submit'), continuePay: () => emit('continuePayButton', 'click'),
    newPayment: () => emit('newMobilePagePaymentButton', 'click'),
    runSubmitTimer() {
      const timer = timers.find(item => item.delay === 60);
      assert.ok(timer, 'the original POST form is scheduled for submission');
      timer.callback();
    },
    signedParams: () => originalSignedParams, originalHtml: () => originalHtml,
    payRequest: () => calls.find(call => call.url === '/api/v1/payments/pay')?.body
  };
}

function assertOriginalProductRequest(t, product, integrationType, merchant = false) {
  const request = t.payRequest();
  assert.equal(t.calls.filter(call => call.url === '/api/v1/payments/pay').length, 1);
  assert.equal(request.product, product);
  assert.equal(request.outTradeNo, 'ORIGINAL_PC_ORDER_001');
  assert.equal(request.totalAmount, 0.10);
  assert.equal(request.extra.integration_type, integrationType);
  assert.deepEqual(request.channelIds, merchant ? undefined : ['ali-page']);
  assert.equal(request.extra.merchantId, merchant ? 'M1' : undefined);
  assert.equal(t.elements.paymentFormHolder.innerHTML, t.originalHtml());
  const signedBiz = JSON.parse(t.signedParams().biz_content);
  assert.equal(t.signedParams().method, 'alipay.trade.page.pay');
  assert.equal(signedBiz.product_code, 'FAST_INSTANT_TRADE_PAY');
  assert.equal(signedBiz.out_trade_no, request.outTradeNo);
  assert.equal(signedBiz.total_amount, '0.10');
}

for (const product of ['ALIPAY_DIRECT_PAGE']) {
  test(`${product} in the Alipay wallet posts the unchanged signed form without launching a second app`, async () => {
    const t = await openCashier({ product, userAgent: 'iPhone Mobile AlipayClient/10.7' });
    const originalUrl = t.window.location.href;
    await t.submit();
    assertOriginalProductRequest(t, product, 'ALIAPP');
    assert.equal(t.bridgeCalls.length, 0);
    assert.equal(t.window.location.href, originalUrl);
    assert.equal(t.formSubmissions.length, 0);
    assert.equal(t.timers.length, 1);
    t.runSubmitTimer();
    assert.equal(t.formSubmissions.length, 1);
    assert.deepEqual(t.formSubmissions[0].params, t.signedParams());
    assert.equal(t.formSubmissions[0].attributes.method, 'POST');
    assert.equal(t.formSubmissions[0].attributes.action, 'https://openapi.alipay.com/gateway.do?charset=UTF-8');
    assert.equal(t.formSubmissions[0].attributes.target, '_self');
    assert.equal(t.bridgeCalls.length, 0);
  });

  test(`${product} on an external phone preserves the original wallet launch URL and signed parameters`, async () => {
    const t = await openCashier({ product, userAgent: 'iPhone Mobile Safari' });
    await t.submit();
    assertOriginalProductRequest(t, product, 'ALIAPP');
    assert.equal(t.formSubmissions.length, 0);
    assert.equal(t.timers.length, 0);
    assert.equal(t.bridgeCalls.length, 0);
    const launchUrl = new URL(t.window.location.href);
    assert.equal(launchUrl.protocol, 'alipays:');
    assert.equal(launchUrl.searchParams.get('appId'), '20000067');
    const paymentUrl = new URL(launchUrl.searchParams.get('url'));
    assert.equal(paymentUrl.origin, 'https://openapi.alipay.com');
    for (const [key, value] of Object.entries(t.signedParams())) assert.equal(paymentUrl.searchParams.get(key), value);
  });

  test(`${product} on desktop keeps the PC form submission and manual continue button`, async () => {
    const t = await openCashier({ product, userAgent: 'Mozilla/5.0 Windows NT 10.0' });
    const originalUrl = t.window.location.href;
    await t.submit();
    assertOriginalProductRequest(t, product, undefined);
    assert.equal(t.classes.continuePanel.has('active'), true);
    assert.equal(t.window.location.href, originalUrl);
    assert.equal(t.bridgeCalls.length, 0);
    t.runSubmitTimer();
    assert.equal(t.formSubmissions.length, 1);
    assert.deepEqual(t.formSubmissions[0].params, t.signedParams());
    assert.equal(t.formSubmissions[0].attributes.method, 'POST');
    await t.continuePay();
    assert.equal(t.formSubmissions.length, 2);
    assert.deepEqual(t.formSubmissions[1].params, t.signedParams());
    assert.equal(t.calls.filter(call => call.url === '/api/v1/payments/pay').length, 1);
  });
}

test('desktop ALIPAY_PAGE keeps its signed POST form and manual continue path for the same cashier URL', async () => {
  const t = await openCashier({ userAgent: 'Mozilla/5.0 Windows NT 10.0' });
  await t.submit();
  assertOriginalProductRequest(t, 'ALIPAY_PAGE', undefined);
  assert.equal(t.payRequest().extra.cashierMobilePageQr, undefined);
  assert.equal(t.storage.size, 0);
  assert.equal(t.bridgeCalls.length, 0);
  t.runSubmitTimer();
  assert.deepEqual(t.formSubmissions[0].params, t.signedParams());
  assert.equal(t.formSubmissions[0].attributes.method, 'POST');
  await t.continuePay();
  assert.equal(t.formSubmissions.length, 2);
});

test('phone ALIPAY_PAGE opens only the official QR route with the original amount and cashier binding', async () => {
  for (const wallet of [false, true]) for (const merchant of [false, true]) {
    const t = await openCashier({ userAgent: wallet ? 'Android Mobile AlipayClient/10.7' : 'iPhone Mobile Safari', merchant });
    await t.submit();
    const request = t.payRequest();
    assert.equal(request.product, 'ALIPAY_PAGE');
    assert.equal(request.outTradeNo, 'ORIGINAL_PC_ORDER_001');
    assert.equal(request.totalAmount, 0.10);
    assert.equal(request.subject, '手机扫码原产品 & 订单');
    assert.equal(request.extra.cashier, true);
    assert.equal(request.extra.cashierMobilePageQr, true);
    assert.equal(request.extra.integration_type, undefined);
    assert.equal(request.extra.qr_pay_mode, undefined);
    assert.equal(request.extra.merchantId, merchant ? 'M1' : undefined);
    assert.deepEqual(request.channelIds, merchant ? undefined : ['ali-page']);
    assert.equal(t.formSubmissions.length, 0);
    assert.equal(t.elements.paymentFormHolder.innerHTML, '');
    assert.equal(t.bridgeCalls.length, 0);
    assert.equal(t.elements.amount.disabled, true);
    assert.equal(t.elements.subject.disabled, true);
    assert.equal(t.elements.payButton.textContent, '继续付款');
    assert.ok(t.timers.every(timer => timer.delay === 30000 || timer.delay > 500000));
    if (wallet) assert.equal(t.window.location.href, officialQr);
    else {
      const launched = new URL(t.window.location.href);
      assert.equal(launched.protocol, 'alipays:');
      assert.equal(launched.searchParams.get('appId'), '10000007');
      assert.equal(launched.searchParams.get('actionType'), 'route');
      assert.equal(launched.searchParams.get('qrcode'), officialQr);
      assert.equal(launched.searchParams.has('url'), false);
    }
    const saved = JSON.parse([...t.storage.values()][0]);
    assert.equal(saved.state, 'READY');
    assert.equal(saved.qrCode, officialQr);
    assert.equal(saved.expiresAt - saved.createdAt, 600000);
  }
});

test('same pending attempt is persisted before POST and double clicks cannot create another order', async () => {
  let resolvePay, sentBody;
  const storage = new Map();
  const t = await openCashier({ storage, pendingPay: ({ body }) => {
    sentBody = body;
    assert.equal(JSON.parse([...storage.values()][0]).state, 'PENDING');
    assert.equal(JSON.parse([...storage.values()][0]).outTradeNo, body.outTradeNo);
    return new Promise(resolve => { resolvePay = resolve; });
  }});
  const first = t.submit();
  await t.submit();
  assert.equal(t.calls.filter(call => call.method === 'POST').length, 1);
  assert.equal(t.window.generatedOrderNos, 1);
  assert.equal(t.elements.payButton.disabled, true);
  resolvePay({ ok: true, text: async () => JSON.stringify({ status: 'CREATED', code: 'PAGE_QR_CREATED',
    outTradeNo: sentBody.outTradeNo, channelId: 'ali-page', qrCode: officialQr }) });
  await first;
  await t.submit();
  assert.equal(t.calls.filter(call => call.method === 'POST').length, 1);
  assert.equal(t.window.generatedOrderNos, 1);
});

test('pending, HTTP 409, network failure and HTML replies stay locked across edits and reload', async () => {
  const scenarios = [
    { response: { status: 'PENDING', code: 'PAGE_QR_UNCONFIRMED', redirectHtml: '<form>unsafe fallback</form>' } },
    { response: { httpStatus: 409, code: 'ORDER_CONFLICT', message: 'already exists' } },
    { payError: Error('network failed') },
    { response: { status: 'CREATED', code: 'PAGE_QR_CREATED', redirectHtml: '<form>old contract</form>' } }
  ];
  for (const scenario of scenarios) {
    const t = await openCashier(scenario);
    const before = t.window.location.href;
    await t.submit();
    assert.equal(t.elements.payButton.disabled, true);
    assert.match(t.elements.message.textContent, /付款码未取得，请联系商家查询原单/);
    assert.equal(t.window.location.href, before);
    assert.equal(t.elements.paymentFormHolder.innerHTML, '');
    assert.equal(t.formSubmissions.length, 0);
    t.elements.amount.value = '999'; t.elements.subject.value = 'changed';
    await t.submit();
    assert.equal(t.calls.filter(call => call.method === 'POST').length, 1);
    assert.equal(t.elements.amount.value, '0.10');
    assert.equal(t.elements.subject.value, '手机扫码原产品 & 订单');
    const reloaded = await openCashier({ storage: t.storage });
    assert.equal(reloaded.window.generatedOrderNos, 0);
    assert.equal(reloaded.elements.amount.value, '0.10');
    assert.equal(reloaded.elements.amount.disabled, true);
    await reloaded.submit();
    assert.equal(reloaded.calls.filter(call => call.method === 'POST').length, 0);
    assert.equal(reloaded.elements.payButton.disabled, true);
  }
});

test('request timeout aborts and keeps its saved original order instead of allowing a fresh submit', async () => {
  const t = await openCashier({ pendingPay: ({ options }) => new Promise((resolve, reject) => {
    options.signal.addEventListener('abort', () => reject(Object.assign(Error('timeout'), { name: 'AbortError' })));
  }) });
  const running = t.submit();
  const timeout = t.timers.find(timer => timer.delay === 30000);
  assert.ok(timeout);
  timeout.callback();
  await running;
  await t.submit();
  assert.equal(t.calls.filter(call => call.method === 'POST').length, 1);
  assert.equal(JSON.parse([...t.storage.values()][0]).state, 'PENDING');
  assert.equal(t.elements.payButton.disabled, true);
});

test('ready cache reopens only the original QR without a POST, while expired cache never creates a new order', async () => {
  const clock = { now: 1800000000000 };
  const t = await openCashier({ clock });
  await t.submit();
  const restored = await openCashier({ clock, storage: t.storage });
  assert.match(restored.window.location.href, /^https:\/\/pay\.example/);
  assert.equal(restored.elements.payButton.textContent, '继续付款');
  restored.elements.amount.value = '4.00'; restored.elements.subject.value = 'new order';
  await restored.submit();
  assert.equal(new URL(restored.window.location.href).searchParams.get('qrcode'), officialQr);
  assert.equal(restored.calls.filter(call => call.method === 'POST').length, 0);
  assert.equal(restored.window.generatedOrderNos, 0);
  assert.equal(restored.elements.amount.value, '0.10');
  clock.now += 600001;
  const expired = await openCashier({ clock, storage: t.storage });
  await expired.submit();
  assert.equal(expired.elements.payButton.disabled, true);
  assert.match(expired.elements.message.textContent, /当前付款入口已结束，请核对原单/);
  assert.equal(expired.calls.filter(call => call.method === 'POST').length, 0);
  assert.equal(expired.window.generatedOrderNos, 0);
});

test('wrong or bypassed QR links and mismatched response identity never reach generic QR or form handling', async () => {
  const invalid = [
    'http://qr.alipay.com/upx0123456789', 'https://qr.alipay.com.evil.test/upx0123456789',
    'https://user@qr.alipay.com/upx0123456789', 'https://qr.alipay.com:444/upx0123456789',
    'https://qr.alipay.com/other', 'https://qr.alipay.com/upxshort', 'https://qr.alipay.com/upx' + 'x'.repeat(129),
    'https://qr.alipay.com/upx0123456789?next=evil', 'https://qr.alipay.com/upx0123456789#fragment',
    'https://qr.alipay.com\\@evil.test/upx0123456789', ' https://qr.alipay.com/upx0123456789',
    'https://qr%2ealipay.com/upx0123456789', 'https://qr.alipay.com/a/../upx0123456789',
    'https://qr.alipay.com/upxABCDEFGH/../upx0123456789', 'https://qr.alipay.com/upxABCDEFGH/%2e%2e/upx0123456789',
    'alipays://platformapi/startapp?appId=20000067&url=evil', 'javascript:alert(1)'
  ];
  for (const response of [...invalid.map(qrCode => ({ qrCode })),
    { qrCode: officialQr, outTradeNo: 'OTHER_ORDER' }, { qrCode: officialQr, channelId: 'other-channel' },
    { qrCode: officialQr, status: 'FAILED' }, { qrCode: officialQr, code: 'OTHER_CODE' }]) {
    const t = await openCashier({ response: { status: 'CREATED', code: 'PAGE_QR_CREATED', redirectHtml: '<form>bad</form>', ...response } });
    const before = t.window.location.href;
    await t.submit();
    assert.equal(t.window.location.href, before);
    assert.equal(t.elements.paymentFormHolder.innerHTML, '');
    assert.equal(t.elements.payQrImage.src, '');
    assert.equal(t.elements.payButton.disabled, true);
    assert.equal(t.bridgeCalls.length, 0);
  }
});

test('missing, broken or corrupted session storage prevents a new mobile PAGE order', async () => {
  for (const storageApi of [
    {}, { getItem() { throw Error('blocked'); } },
    { getItem() { return null; }, setItem() { throw Error('quota'); } },
    { getItem() { return 'not json'; }, setItem() {} },
    { getItem() { return null; }, setItem() {} }
  ]) {
    const t = await openCashier({ storageApi });
    await t.submit();
    assert.equal(t.calls.filter(call => call.method === 'POST').length, 0);
    assert.equal(t.elements.payButton.disabled, true);
    assert.match(t.elements.message.textContent, /请联系商家/);
  }
});

test('cashier session identity excludes secrets and isolates deployment paths, channel and merchant entries', async () => {
  const storage = new Map();
  const base = 'https://pay.example/site/cashier.html?channelId=ali-page&product=ALIPAY_PAGE&token=secret&auth_code=hidden#private-key';
  const first = await openCashier({ storage, url: base });
  await first.submit();
  const restored = await openCashier({ storage, url: 'https://pay.example/site/cashier.html?channelId=ali-page&product=ALIPAY_PAGE' });
  assert.equal(restored.elements.payButton.textContent, '继续付款');
  assert.equal(first.payRequest().returnUrl, 'https://pay.example/site/cashier.html?channelId=ali-page&product=ALIPAY_PAGE');
  const elsewhere = await openCashier({ storage, url: 'https://pay.example/other/cashier.html?channelId=ali-page&product=ALIPAY_PAGE' });
  const merchant = await openCashier({ storage, merchant: true });
  assert.equal(elsewhere.elements.amount.disabled, false);
  assert.equal(merchant.elements.amount.disabled, false);
  assert.doesNotMatch(JSON.stringify([...storage]), /secret|hidden|private-key|auth_code|token/);
});

test('invalid phone PAGE amounts do not create attempts or payment requests', async () => {
  for (const amount of ['', '0', '0.001', '-1', 'Infinity', '1e3']) {
    const t = await openCashier(); t.elements.amount.value = amount;
    await t.submit();
    assert.equal(t.calls.filter(call => call.method === 'POST').length, 0);
    assert.equal(t.storage.size, 0);
    assert.equal(t.window.generatedOrderNos, 0);
  }
});

test('mobile WAP retains its prior form path without the mobile PAGE QR flag or session lock', async () => {
  const t = await openCashier({ product: 'ALIPAY_WAP' });
  await t.submit();
  assert.equal(t.payRequest().product, 'ALIPAY_WAP');
  assert.equal(t.payRequest().extra.cashierMobilePageQr, undefined);
  assert.equal(t.storage.size, 0);
  assert.equal(t.elements.newMobilePagePaymentButton.hidden, true);
  t.runSubmitTimer();
  assert.equal(t.formSubmissions.length, 1);
});

test('ready attempt can start an explicit new payment only after returning to an empty amount form', async () => {
  const t = await openCashier();
  assert.equal(t.elements.newMobilePagePaymentButton.hidden, true);
  await t.submit();
  assert.equal(t.elements.newMobilePagePaymentButton.hidden, false);
  assert.equal(t.elements.newMobilePagePaymentButton.disabled, false);
  await t.newPayment();
  assert.equal(t.storage.size, 0);
  assert.equal(t.elements.amount.value, '');
  assert.equal(t.elements.amount.disabled, false);
  assert.equal(t.elements.subject.disabled, false);
  assert.equal(t.elements.newMobilePagePaymentButton.hidden, true);
  assert.equal(t.calls.filter(call => call.method === 'POST').length, 1);
  await t.submit();
  assert.equal(t.calls.filter(call => call.method === 'POST').length, 1);
  t.elements.amount.value = '2.50';
  t.elements.subject.value = '第二笔独立消费';
  await t.submit();
  const requests = t.calls.filter(call => call.method === 'POST');
  assert.equal(requests.length, 2);
  assert.notEqual(requests[0].body.outTradeNo, requests[1].body.outTradeNo);
  assert.equal(requests[1].body.totalAmount, 2.50);
  assert.equal(requests[1].body.subject, '第二笔独立消费');
  assert.equal(requests[1].body.returnUrl, 'https://pay.example/cashier.html?channelId=ali-page&product=ALIPAY_PAGE');
});

test('pending attempt cannot be cleared early, and expiry only enables a manual new payment without sending', async () => {
  const clock = { now: 1800000000000 };
  const t = await openCashier({ clock, response: { status: 'PENDING', code: 'PAGE_QR_UNCONFIRMED' } });
  await t.submit();
  const saved = [...t.storage.values()][0];
  assert.equal(t.elements.newMobilePagePaymentButton.disabled, true);
  await t.newPayment();
  assert.equal([...t.storage.values()][0], saved);
  assert.equal(t.elements.amount.disabled, true);
  clock.now += 600001;
  t.timers.filter(timer => timer.delay === 600000).at(-1).callback();
  assert.equal(t.elements.newMobilePagePaymentButton.disabled, false);
  assert.equal([...t.storage.values()][0], saved);
  assert.match(t.elements.message.textContent, /当前付款入口已结束，请核对原单/);
  assert.doesNotMatch(t.elements.message.textContent, /未付款|已关闭|支付宝.*过期/);
  assert.equal(t.calls.filter(call => call.method === 'POST').length, 1);
  assert.equal(t.window.generatedOrderNos, 1);
  await t.newPayment();
  assert.equal(t.storage.size, 0);
  assert.equal(t.elements.amount.value, '');
  assert.equal(t.calls.filter(call => call.method === 'POST').length, 1);
});

test('failed session deletion keeps the existing attempt locked and cannot create another payment', async () => {
  for (const noOp of [false, true]) {
    const storage = new Map();
    const t = await openCashier({ storage, storageApi: {
      getItem(key) { return storage.get(key) ?? null; }, setItem(key, value) { storage.set(key, value); },
      removeItem() { if (!noOp) throw Error('storage blocked'); }
    }});
    await t.submit();
    const saved = [...storage.values()][0];
    await t.newPayment();
    await t.submit();
    assert.equal([...storage.values()][0], saved);
    assert.equal(t.elements.amount.disabled, true);
    assert.equal(t.elements.payButton.disabled, true);
    assert.equal(t.calls.filter(call => call.method === 'POST').length, 1);
  }
});
