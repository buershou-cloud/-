const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const page = fs.readFileSync(path.join(__dirname, '../../main/resources/static/cashier.html'), 'utf8');
const script = page.match(/<script>([\s\S]*?)<\/script>/)[1];
const defaultChannels = [{ id: 'dy', provider: 'DOUYIN', enabled: true, dailyEnabled: true,
  products: ['DOUYIN_H5', 'DOUYIN_NATIVE'] }];
const h5Redirect = 'https://pay.douyin.example/h5?token=original%2Bvalue&sign=a%2Fb%3D&return_url=https%3A%2F%2Fshop.example%2Fdone';

async function openPage(options = {}) {
  const channels = options.channels || defaultChannels;
  const location = new URL(options.url || 'https://pay.example/gateway/cashier.html?channelId=dy&product=DOUYIN_H5');
  const elements = Object.fromEntries([...page.matchAll(/\bid="([^"]+)"/g)].map(match => {
    const classes = new Set();
    return [match[1], { value: match[1] === 'subject' ? '扫码收银台支付' : '', checked: match[1] === 'profitSharingEnabled',
      disabled: match[1] === 'payButton', hidden: false, style: {}, listeners: {}, src: '',
      textContent: match[1] === 'payButton' ? '确认支付' : '',
      classList: { add(value) { classes.add(value); }, remove(value) { classes.delete(value); }, contains(value) { return classes.has(value); } },
      removeAttribute(name) { if (name === 'src') this.src = ''; },
      addEventListener(event, callback) { (this.listeners[event] ||= []).push(callback); }
    }];
  }));
  const calls = [], timers = [];
  const window = { location, generatedOrderNos: 0 };
  const context = vm.createContext({ URL, URLSearchParams, navigator: { userAgent: options.mobile ? 'iPhone Mobile' : 'Desktop' },
    location, window, document: { getElementById: id => elements[id] },
    setTimeout(callback, delay) { timers.push({ callback, delay }); return timers.length; }, clearTimeout() {},
    fetch: async (url, config = {}) => {
      calls.push({ url, method: config.method || 'GET', body: config.body ? JSON.parse(config.body) : undefined });
      let data;
      if (url === '/api/v1/channels') data = channels;
      else if (/^\/api\/v1\/merchants\/[^/]+\/cashier-info$/.test(url)) {
        const merchantId = decodeURIComponent(url.split('/')[4]);
        data = { merchantId, name: '演示商户', channelIds: options.merchantChannelIds || channels.map(channel => channel.id) };
      } else if (url === '/api/v1/payments/pay') {
        data = options.response || { status: 'PENDING', channelId: 'dy', redirectUrl: h5Redirect };
      } else throw Error(`Unexpected fetch ${url}`);
      return { ok: true, text: async () => JSON.stringify(data) };
    }
  });
  vm.runInContext(script, context);
  vm.runInContext('orderNo = () => { window.generatedOrderNos++; return "CASHIER_TEST_" + window.generatedOrderNos; };', context);
  await new Promise(resolve => setImmediate(resolve));
  const emit = async (id, type) => {
    for (const callback of elements[id].listeners[type] || []) await callback({ preventDefault() {} });
  };
  return { elements, calls, timers, window, context, emit, submit: () => emit('cashierForm', 'submit'),
    posts: () => calls.filter(call => call.method === 'POST'),
    qrText: () => new URL(elements.payQrImage.src, location.origin).searchParams.get('text') };
}

test('desktop H5 generates a same-path mobile entry without creating an order or polling', async () => {
  const t = await openPage({ url: 'https://pay.example/gateway/v2/cashier.html?channelId=dy&product=DOUYIN_H5&auth_code=secret&state=private&token=hidden&h5_url=upstream&outTradeNo=OLD#private-key' });
  t.elements.amount.value = '12.30';
  t.elements.subject.value = '咖啡 & 茶 + %26 / "订单"';
  t.elements.profitSharingEnabled.checked = false;
  await t.emit('profitSharingEnabled', 'change');
  await t.submit();
  assert.equal(t.posts().length, 0);
  assert.equal(t.window.generatedOrderNos, 0);
  assert.equal(t.timers.length, 0);
  const url = new URL(t.qrText());
  assert.equal(url.origin, 'https://pay.example');
  assert.equal(url.pathname, '/gateway/v2/cashier.html');
  assert.deepEqual([...url.searchParams.keys()].sort(), ['amount', 'channelId', 'handoff', 'product', 'profitSharing', 'subject']);
  assert.equal(url.searchParams.get('amount'), '12.30');
  assert.equal(url.searchParams.get('subject'), t.elements.subject.value);
  assert.equal(url.searchParams.get('profitSharing'), 'false');
  assert.equal(url.searchParams.get('channelId'), 'dy');
  assert.equal(url.searchParams.get('product'), 'DOUYIN_H5');
  assert.equal(url.searchParams.get('handoff'), 'douyin-h5');
  assert.equal(url.hash, '');
  assert.doesNotMatch(t.qrText(), /secret|private|hidden|upstream|outTradeNo|auth_code|token|h5_url/);
  assert.equal(t.elements.payPanelTitle.textContent, '手机确认付款');
  assert.equal(t.elements.profitSharingPanel.hidden, true);
  assert.equal(t.elements.payQrImage.alt, '手机确认付款');
  assert.match(t.elements.message.textContent, /电脑尚未创建支付订单/);
  assert.match(t.elements.payButton.textContent, /生成手机付款入口/);
});

test('channel and merchant handoffs prefill editable mobile forms; only one explicit click pays with the original redirect URL', async () => {
  for (const identity of ['channelId=dy', `merchantId=${encodeURIComponent('商户 & M%26')}`]) {
    const desktop = await openPage({ url: `https://pay.example/site/cashier.html?${identity}&product=DOUYIN_H5` });
    desktop.elements.amount.value = '8.90';
    desktop.elements.subject.value = '订单 & 茶 + %26';
    desktop.elements.profitSharingEnabled.checked = false;
    await desktop.submit();
    const entry = new URL(desktop.qrText());
    assert.equal(entry.searchParams.has('channelId'), identity.startsWith('channelId'));
    assert.equal(entry.searchParams.has('merchantId'), identity.startsWith('merchantId'));
    const mobile = await openPage({ url: entry.href, mobile: true });
    assert.equal(mobile.posts().length, 0);
    assert.equal(mobile.window.generatedOrderNos, 0);
    assert.equal(mobile.timers.length, 0);
    assert.equal(mobile.elements.amount.value, '8.90');
    assert.equal(mobile.elements.subject.value, '订单 & 茶 + %26');
    assert.equal(mobile.elements.profitSharingEnabled.checked, false);
    assert.equal(mobile.elements.profitSharingPanel.hidden, true);
    assert.equal(mobile.elements.payButton.textContent, '确认支付');
    mobile.elements.amount.value = '9.25';
    mobile.elements.subject.value = '手机确认后的订单';
    await mobile.submit();
    assert.equal(mobile.posts().length, 1);
    assert.equal(mobile.window.generatedOrderNos, 1);
    const request = mobile.posts()[0].body;
    assert.equal(request.product, 'DOUYIN_H5');
    assert.equal(request.totalAmount, 9.25);
    assert.equal(request.subject, '手机确认后的订单');
    assert.equal(request.extra.settle_info.profit_sharing, false);
    assert.equal(request.extra.merchantId, identity.startsWith('merchantId') ? entry.searchParams.get('merchantId') : undefined);
    assert.deepEqual(request.channelIds, identity.startsWith('channelId') ? ['dy'] : undefined);
    assert.equal(mobile.window.location.href, h5Redirect);
    assert.equal(mobile.elements.payQrImage.src, '');
  }
});

test('invalid desktop H5 amounts never create an entry or send payment', async () => {
  for (const value of ['', '0', '-1', '0.001', 'Infinity', 'NaN', '1e3', '9007199254740992']) {
    const t = await openPage();
    t.elements.amount.value = value;
    await t.submit();
    assert.equal(t.posts().length, 0);
    assert.equal(t.window.generatedOrderNos, 0);
    assert.equal(t.elements.payQrImage.src, '');
    assert.match(t.elements.message.textContent, /正确金额/);
  }
});

test('H5 handoffs require an enabled matching product on both desktop and mobile', async () => {
  for (const channel of [
    { ...defaultChannels[0], products: ['DOUYIN_NATIVE'] },
    { ...defaultChannels[0], enabled: false },
    { ...defaultChannels[0], dailyEnabled: false },
    { ...defaultChannels[0], provider: 'ALIPAY' }
  ]) {
    for (const mobile of [false, true]) {
      const t = await openPage({ mobile, channels: [channel], url: 'https://pay.example/cashier.html?channelId=dy&product=DOUYIN_H5&handoff=douyin-h5&amount=1.00' });
      assert.equal(t.elements.payButton.disabled, true);
      t.elements.amount.value = '1.00';
      await t.submit();
      assert.equal(t.posts().length, 0);
      assert.equal(t.elements.payQrImage.src, '');
    }
  }
  const unknown = await openPage({ url: 'https://pay.example/cashier.html?channelId=dy&product=UNKNOWN' });
  unknown.elements.amount.value = '1.00';
  await unknown.submit();
  assert.equal(unknown.posts().length, 0);
  assert.equal(unknown.window.generatedOrderNos, 0);
});

test('merchant handoffs do not use H5 channels outside the merchant binding', async () => {
  const channels = [...defaultChannels, { id: 'ali', provider: 'ALIPAY', enabled: true, products: ['ALIPAY_F2F'] }];
  const t = await openPage({ channels, merchantChannelIds: ['ali'], url: 'https://pay.example/cashier.html?merchantId=M1&product=DOUYIN_H5&handoff=douyin-h5&amount=1.00', mobile: true });
  assert.equal(t.elements.payButton.disabled, true);
  await t.submit();
  assert.equal(t.posts().length, 0);
});

test('handoff-only fields never alter Alipay defaults or payment extra', async () => {
  const t = await openPage({ channels: [{ id: 'ali', provider: 'ALIPAY', enabled: true, products: ['ALIPAY_F2F'] }],
    url: 'https://pay.example/cashier.html?channelId=ali&product=ALIPAY_F2F&handoff=douyin-h5&amount=99.00&subject=wrong&profitSharing=false',
    response: { status: 'PENDING', qrCode: 'https://qr.alipay.example/original' } });
  assert.equal(t.elements.amount.value, '');
  assert.equal(t.elements.subject.value, '扫码收银台支付');
  assert.equal(t.elements.profitSharingPanel.hidden, true);
  t.elements.amount.value = '2.00';
  await t.submit();
  assert.equal(t.posts().length, 1);
  assert.equal(t.posts()[0].body.product, 'ALIPAY_F2F');
  assert.deepEqual(t.posts()[0].body.extra, { cashier: true });
  assert.equal(t.qrText(), 'https://qr.alipay.example/original');
});

test('desktop Native still creates its order and displays the upstream qrCode', async () => {
  const upstream = 'https://pay.douyin.example/native?qr=original';
  const t = await openPage({ url: 'https://pay.example/cashier.html?channelId=dy&product=DOUYIN_NATIVE',
    response: { status: 'PENDING', channelId: 'dy', qrCode: upstream } });
  t.elements.amount.value = '3.00';
  await t.submit();
  assert.equal(t.posts().length, 1);
  assert.equal(t.window.generatedOrderNos, 1);
  assert.equal(t.posts()[0].body.product, 'DOUYIN_NATIVE');
  assert.equal(t.qrText(), upstream);
  assert.equal(t.elements.payPanelTitle.textContent, '抖音付款二维码');
  assert.equal(t.timers.length, 1);
});

test('editing desktop handoff amount, title or sharing choice hides the obsolete QR', async () => {
  for (const id of ['amount', 'subject', 'profitSharingEnabled']) {
    const t = await openPage();
    t.elements.amount.value = '1.00';
    await t.submit();
    assert.ok(t.elements.payQrImage.src);
    if (id === 'profitSharingEnabled') t.elements[id].checked = false;
    else t.elements[id].value = id === 'amount' ? '2.00' : '新的标题';
    await t.emit(id, 'input');
    assert.equal(t.elements.payQrImage.src, '');
    assert.equal(t.elements.payPanel.classList.contains('active'), false);
    assert.match(t.elements.message.textContent, /重新生成/);
    await t.submit();
    assert.equal(t.posts().length, 0);
    const query = new URL(t.qrText()).searchParams;
    assert.equal(query.get(id === 'profitSharingEnabled' ? 'profitSharing' : id), id === 'profitSharingEnabled' ? 'false' : t.elements[id].value);
  }
});

test('mobile H5 never treats qrCode or redirectHtml as an H5 redirect and cannot inherit automatic payment', async () => {
  const t = await openPage({ mobile: true,
    url: 'https://pay.example/cashier.html?channelId=dy&product=DOUYIN_H5&handoff=douyin-h5&amount=1.00&autoPay=1&auth_code=OLD&state=autoPay%3D1',
    response: { status: 'PENDING', qrCode: 'https://invalid.example/h5-link', redirectHtml: '<form>not H5</form>' } });
  assert.equal(t.posts().length, 0);
  assert.equal(t.timers.length, 0);
  await t.submit();
  assert.equal(t.posts().length, 1);
  assert.equal(t.elements.payQrImage.src, '');
  assert.equal(t.timers.length, 0);
  assert.match(t.elements.message.textContent, /核对抖音 H5 返回的跳转地址/);
  assert.doesNotMatch(page, /抖音H5支付二维码/);
});

test('failed H5 responses never launch or poll even if they include a redirect URL', async () => {
  const t = await openPage({ mobile: true, response: { status: 'FAILED', message: '上游拒绝支付', redirectUrl: h5Redirect } });
  t.elements.amount.value = '1.00';
  const originalUrl = t.window.location.href;
  await t.submit();
  assert.equal(t.posts().length, 1);
  assert.equal(t.posts()[0].body.extra.settle_info.profit_sharing, true);
  assert.equal(t.elements.profitSharingPanel.hidden, true);
  assert.equal(t.window.location.href, originalUrl);
  assert.equal(t.timers.length, 0);
  assert.equal(t.elements.payQrImage.src, '');
  assert.match(t.elements.message.textContent, /上游拒绝支付/);
});
