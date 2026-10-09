const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const page = fs.readFileSync(path.join(__dirname, '../../main/resources/static/cashier.html'), 'utf8');
const script = page.match(/<script>([\s\S]*?)<\/script>/)[1];

async function openCashier({ product, userAgent, merchant = false }) {
  const location = new URL(`https://pay.example/cashier.html?${merchant ? 'merchantId=M1' : 'channelId=ali-page'}&product=${product}`);
  const calls = [], timers = [], bridgeCalls = [], formSubmissions = [];
  const classes = {};
  const elements = Object.fromEntries([...page.matchAll(/\bid="([^"]+)"/g)].map(match => {
    const id = match[1];
    classes[id] = new Set();
    return [id, {
      value: id === 'subject' ? '扫码收银台支付' : '', checked: id === 'profitSharingEnabled',
      disabled: false, hidden: false, style: {}, listeners: {}, src: '', textContent: '', innerHTML: '',
      classList: { add(value) { classes[id].add(value); }, remove(value) { classes[id].delete(value); } },
      removeAttribute(name) { if (name === 'src') this.src = ''; },
      addEventListener(event, callback) { (this.listeners[event] ||= []).push(callback); }
    }];
  }));
  let originalSignedParams, originalHtml, form;
  elements.paymentFormHolder.querySelector = selector => selector === 'form' && elements.paymentFormHolder.innerHTML ? form : null;
  const window = { location, AlipayJSBridge: {
    call(...args) { bridgeCalls.push(args); }
  } };
  const context = vm.createContext({ URL, URLSearchParams, navigator: { userAgent }, location, window,
    document: { getElementById: id => elements[id], addEventListener() {} },
    setTimeout(callback, delay) { timers.push({ callback, delay }); return timers.length; }, clearTimeout() {},
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
  vm.runInContext('orderNo = () => "ORIGINAL_PC_ORDER_001";', context);
  await new Promise(resolve => setImmediate(resolve));
  elements.amount.value = '0.10';
  elements.subject.value = '手机扫码原产品 & 订单';
  const emit = async (id, event) => {
    for (const callback of elements[id].listeners[event] || []) await callback({ preventDefault() {} });
  };
  return { calls, timers, bridgeCalls, formSubmissions, elements, window, classes,
    submit: () => emit('cashierForm', 'submit'), continuePay: () => emit('continuePayButton', 'click'),
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

for (const product of ['ALIPAY_PAGE', 'ALIPAY_DIRECT_PAGE']) {
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

test('the merchant QR entry preserves the requested PC product and merchant binding inside the wallet', async () => {
  const t = await openCashier({ product: 'ALIPAY_PAGE', userAgent: 'Android Mobile AlipayClient/10.7', merchant: true });
  await t.submit();
  assertOriginalProductRequest(t, 'ALIPAY_PAGE', 'ALIAPP', true);
  assert.equal(t.bridgeCalls.length, 0);
  t.runSubmitTimer();
  assert.deepEqual(t.formSubmissions[0].params, t.signedParams());
  assert.equal(t.formSubmissions[0].attributes.method, 'POST');
});
