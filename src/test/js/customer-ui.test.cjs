const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const source = name => fs.readFileSync(path.join(__dirname, '../../main/resources/static', name + '.html'), 'utf8');
const merchant = source('merchant');
const cashier = source('cashier');
const login = source('login');

function pageFunction(page, name) {
  const pattern = new RegExp(`^( +)(?:async )?function ${name}\\(`, 'm');
  const match = pattern.exec(page);
  assert.ok(match, `Missing function ${name}`);
  const end = page.indexOf('\n' + match[1] + '}', match.index) + match[1].length + 2;
  return page.slice(match.index, end);
}

function runFunctions(page, names, globals = {}) {
  const context = vm.createContext(globals);
  vm.runInContext(names.map(name => pageFunction(page, name)).join('\n'), context);
  return context;
}

function element(value = '') {
  const classes = new Set();
  return {
    value, textContent: '', innerHTML: '', hidden: false, disabled: false, style: {}, attributes: {},
    classList: { add(name) { classes.add(name); }, remove(name) { classes.delete(name); }, contains(name) { return classes.has(name); } },
    setAttribute(name, value) { this.attributes[name] = value; },
    getAttribute(name) { return this.attributes[name]; },
    removeAttribute(name) { delete this.attributes[name]; }
  };
}

test('merchant filters are inclusive, combine search/status/date, and clamp pagination', () => {
  const context = runFunctions(merchant, ['merchantOrderPage']);
  const orders = Array.from({ length: 27 }, (_, index) => ({
    outTradeNo: `ORDER-${index}`, tradeNo: `TRADE-${index}`, channelId: index % 2 ? 'Channel-B' : 'Channel-A',
    status: index % 2 ? 'UNPAID' : 'COMPLETED', createdAt: index < 20 ? '2026-10-25 12:30:00' : '2026-10-24 23:59:59'
  }));
  const page = context.merchantOrderPage(orders, { query: 'channel-a', status: 'COMPLETED', start: '2026-10-25', end: '2026-10-25' }, 99, 10);
  assert.equal(page.total, 10);
  assert.equal(page.page, 1);
  assert.equal(page.rows.length, 10);
  assert.equal(context.merchantOrderPage(orders, {}, 2, 10).rows[0].outTradeNo, 'ORDER-10');
  assert.equal(context.merchantOrderPage(orders, { query: 'trade-26' }, 1, 10).total, 1);
  assert.equal(context.merchantOrderPage(orders, { start: '2026-10-26', end: '2026-10-25' }, 1, 10).total, 0);
  assert.equal(context.merchantOrderPage([{ status: 'SUCCESS' }, { status: 'PAYING' }], { status: 'COMPLETED' }, 1, 10).total, 1);
  assert.equal(context.merchantOrderPage([{ status: 'PENDING' }, { status: 'PAYING' }, { status: 'FAILED' }], { status: 'PROCESSING' }, 1, 10).total, 2);
});

test('merchant amount formatting preserves decimal precision and unknown text for escaping', () => {
  const context = runFunctions(merchant, ['formatMerchantAmount']);
  assert.equal(context.formatMerchantAmount('¥12345.6'), '¥12,345.60');
  assert.equal(context.formatMerchantAmount('-1234.01'), '¥-1,234.01');
  assert.equal(context.formatMerchantAmount('9007199254740993.01'), '¥9,007,199,254,740,993.01');
  assert.equal(context.formatMerchantAmount('<img src=x>'), '<img src=x>');
});

function merchantContext(orders = []) {
  const ids = [...merchant.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
  const elements = Object.fromEntries(ids.map(id => [id, element()]));
  elements.merchantOrderPageSize.value = '10';
  const context = runFunctions(merchant, ['escapeHtml', 'statusClass', 'formatMerchantAmount', 'merchantOrderPage', 'renderOrders', 'renderMerchantOrders', 'showMerchantOrderError'], {
    $: id => elements[id], merchantOrders: orders, merchantOrdersPage: 1
  });
  return { context, elements };
}

test('merchant results safely escape stored values and distinguish empty/search/load-error states', () => {
  const { context, elements } = merchantContext();
  const attack = '<img src=x onerror="alert(1)">';
  context.renderOrders([{ outTradeNo: attack, tradeNo: attack, channelId: attack, productName: attack, amountText: attack, statusText: attack, status: 'FAILED', createdAt: attack }]);
  assert.doesNotMatch(elements.portalOrders.innerHTML, /<img|onerror="/);
  assert.match(elements.portalOrders.innerHTML, /&lt;img/);
  assert.match(elements.portalOrders.innerHTML, /status failed/);
  elements.merchantOrderSearch.value = 'no match';
  context.renderMerchantOrders();
  assert.match(elements.portalOrders.innerHTML, /没有符合条件的订单/);
  context.renderOrders([]);
  assert.match(elements.portalOrders.innerHTML, /暂无订单/);
  context.renderOrders(null);
  assert.match(elements.portalOrders.innerHTML, /暂时无法加载订单/);
  assert.equal(elements.merchantOrdersPrevious.disabled, true);
  assert.equal(elements.merchantOrdersNext.disabled, true);
});

test('credentials start masked, reveal explicitly, and copy the actual credential', async () => {
  const elements = { md5Key: element(), rsaPrivateKey: element(), apiMessage: element(), integrationMessage: element() };
  const toggles = { md5Key: element(), rsaPrivateKey: element() };
  const copied = [];
  const context = runFunctions(merchant, ['setCredentialValue', 'toggleCredential', 'copyValue'], {
    $: id => elements[id], credentialValues: { md5Key: '', rsaPrivateKey: '' },
    document: { querySelector: selector => toggles[selector.match(/"([^"]+)"/)[1]] },
    navigator: { clipboard: { writeText: async value => copied.push(value) } }
  });
  context.setCredentialValue('md5Key', 'private-key-value');
  assert.equal(elements.md5Key.value, '••••••••••••••••');
  assert.equal(toggles.md5Key.getAttribute('aria-pressed'), 'false');
  await context.copyValue('md5Key');
  assert.deepEqual(copied, ['private-key-value']);
  assert.equal(elements.md5Key.value, '••••••••••••••••');
  context.toggleCredential('md5Key');
  assert.equal(elements.md5Key.value, 'private-key-value');
  context.toggleCredential('md5Key');
  assert.equal(elements.md5Key.value, '••••••••••••••••');
  context.setCredentialValue('md5Key', '');
  assert.equal(toggles.md5Key.disabled, true);
});

test('merchant refresh retains previous records on failure and ignores a response after logout', async () => {
  const { context, elements } = merchantContext([{ outTradeNo: 'EXISTING' }]);
  const portal = { merchant: { merchantId: 'M1', md5Key: 'key' } };
  Object.assign(context, { currentPortal: portal, merchantOrdersRequest: 0, request: async () => { throw Error('offline'); } });
  vm.runInContext(pageFunction(merchant, 'refreshMerchantOrders'), context);
  await context.refreshMerchantOrders();
  assert.equal(context.merchantOrders[0].outTradeNo, 'EXISTING');
  assert.match(elements.merchantOrderFeedback.textContent, /上次加载/);
  assert.equal(elements.refreshMerchantOrders.disabled, false);
  let resolve;
  context.request = () => new Promise(done => { resolve = done; });
  const pending = context.refreshMerchantOrders();
  context.currentPortal = null;
  context.merchantOrdersRequest++;
  resolve({ orders: [{ outTradeNo: 'LATE' }] });
  await pending;
  assert.equal(context.merchantOrders[0].outTradeNo, 'EXISTING');
});

function cashierContext() {
  const ids = [...cashier.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
  const elements = Object.fromEntries(ids.map(id => [id, element()]));
  const context = runFunctions(cashier, ['cashierMessageText', 'setMessage', 'renderPaymentStatus', 'writeResult', 'showPaymentSuccess', 'showPaymentUnconfirmed'], {
    $: id => elements[id], latestPaymentSummary: null
  });
  return { context, elements };
}

test('created payment and preauthorization are not presented as confirmed receipts', () => {
  const { context, elements } = cashierContext();
  context.writeResult('支付请求已创建', 'ok');
  assert.equal(elements.paymentStatusCard.hidden, false);
  assert.equal(elements.paymentStatusTitle.textContent, '请求已提交');
  assert.doesNotMatch(elements.paymentStatusCard.className, /success/);
  context.writeResult('预授权已提交，请核对冻结结果。', 'ok');
  assert.doesNotMatch(elements.paymentStatusTitle.textContent, /支付成功/);
  context.writeResult({ message: '请核对付款信息', attempts: [{ channelId: 'internal', options: { secret: 'hidden' } }] }, 'bad');
  assert.equal(elements.paymentStatusDetail.textContent, '请核对付款信息');
  assert.doesNotMatch(elements.paymentStatusDetail.textContent, /internal|hidden/);
});

test('a confirmed payment remains visible with its own amount and order number', () => {
  const { context, elements } = cashierContext();
  context.showPaymentSuccess({ status: 'SUCCESS' }, { totalAmount: 1234.56, outTradeNo: 'ORDER-123' });
  assert.equal(elements.paymentStatusCard.hidden, false);
  assert.equal(elements.paymentStatusTitle.textContent, '支付成功');
  assert.equal(elements.paymentStatusAmount.textContent, '¥1,234.56');
  assert.equal(elements.paymentStatusOrder.textContent, '订单号：ORDER-123');
  assert.equal(elements.paymentStatusAmount.hidden, false);
  assert.equal(elements.paymentStatusOrder.hidden, false);
});

test('polling renders a receipt only after the existing success condition is met', async () => {
  const timers = [];
  let receiptCount = 0;
  let result = { status: 'PENDING' };
  const context = runFunctions(cashier, ['isPaymentComplete', 'isPaymentClosed', 'startOrderCodePolling'], {
    currentProduct: 'DOUYIN_NATIVE', paymentPollAttempts: 0, paymentPollTimer: null,
    clearPaymentPolling() {}, isQrPollingProduct: () => true, paymentChannelId: () => 'C1',
    queryPaymentStatus: async () => result, responseTradeStatus: () => '', hidePaymentQr() {},
    showPaymentSuccess() { receiptCount++; }, writeResult() {}, setTimeout(callback) { timers.push(callback); return timers.length; }
  });
  context.startOrderCodePolling({}, { outTradeNo: 'ORDER-1', totalAmount: 1 });
  await timers.shift()();
  assert.equal(receiptCount, 0);
  result = { status: 'SUCCESS' };
  await timers.shift()();
  assert.equal(receiptCount, 1);
});

for (const [pollingFunction, limit] of [['startOrderCodePolling', 24], ['startJsapiPaymentPolling', 30], ['startAppPaymentPolling', 90]]) {
  test(`${pollingFunction} keeps exhausted unknown results distinct from confirmed closure`, async () => {
    for (const scenario of ['network', 'pending', 'closed', ...(pollingFunction === 'startOrderCodePolling' ? ['cancel-closed'] : [])]) {
      const { context, elements } = cashierContext();
      const timers = [];
      const cancelCalls = [];
      Object.assign(context, {
        currentProduct: 'DOUYIN_NATIVE', paymentPollAttempts: 0, paymentPollTimer: null,
        latestPaymentSummary: { totalAmount: 999, outTradeNo: 'OTHER-ORDER' },
        clearPaymentPolling() {}, isQrPollingProduct: () => true, paymentChannelId: () => 'CHANNEL-1',
        async queryPaymentStatus() {
          if (scenario === 'network') throw Error('internal network diagnostics');
          return { status: scenario === 'closed' ? 'CLOSED' : 'PENDING' };
        },
        async cancelPayment(...args) {
          cancelCalls.push(args);
          return { status: scenario === 'cancel-closed' ? 'CLOSED' : 'PENDING' };
        },
        responseTradeStatus: () => '', hidePaymentQr() {},
        setTimeout(callback) { timers.push(callback); return timers.length; }
      });
      vm.runInContext(['isPaymentComplete', 'isPaymentClosed', pollingFunction].map(name => pageFunction(cashier, name)).join('\n'), context);
      context[pollingFunction]({}, { totalAmount: '1234.56', outTradeNo: 'ORDER-123' });
      context.paymentPollAttempts = limit - 1;
      await timers.shift()();
      assert.equal(timers.length, 0, `${scenario}: polling must still finish at its existing limit`);
      if (scenario === 'closed' || scenario === 'cancel-closed') {
        assert.match(elements.paymentStatusCard.className, /is-error/);
        assert.match(elements.paymentStatusDetail.textContent, /已关闭|已撤销/);
      } else {
        assert.match(elements.paymentStatusCard.className, /is-warning/);
        assert.equal(elements.paymentStatusTitle.textContent, '付款结果待确认');
        assert.match(elements.paymentStatusDetail.textContent, /请勿重复支付/);
        assert.doesNotMatch(elements.paymentStatusDetail.textContent, /internal|暂未完成|支付失败|支付成功/);
        assert.equal(elements.paymentStatusAmount.textContent, '¥1,234.56');
        assert.equal(elements.paymentStatusOrder.textContent, '订单号：ORDER-123');
      }
      assert.deepEqual(cancelCalls, pollingFunction === 'startOrderCodePolling' && ['pending', 'cancel-closed'].includes(scenario)
        ? [['ORDER-123', 'CHANNEL-1']] : []);
    }
  });
}

test('moving the verification slider does not claim the server has verified it', () => {
  const elements = Object.fromEntries(['verifyTrack', 'verifyHandle', 'puzzleStage', 'verifySlider', 'verifyPiece', 'verifyText', 'refreshVerify', 'closeVerifyModal', 'verifyModal'].map(id => [id, element()]));
  elements.verifyTrack.clientWidth = 480;
  elements.verifyHandle.offsetWidth = 62;
  elements.puzzleStage.clientWidth = 480;
  elements.verifyTrack.style.setProperty = () => {};
  const context = runFunctions(login, ['clamp', 'setSliderValue', 'setVerifyBusy'], {
    $: id => elements[id], sliderState: { minX: 16, maxX: 400, imageWidth: 480, pieceSize: 64, pieceY: 82, value: 16 }, loginSubmitting: false
  });
  context.setSliderValue(120, true);
  assert.doesNotMatch(elements.verifyText.textContent, /已完成|通过|成功/);
  assert.equal(elements.verifyTrack.classList.contains('verified'), false);
  context.setVerifyBusy(true);
  assert.equal(elements.verifyText.textContent, '正在验证…');
  assert.equal(elements.verifyHandle.disabled, true);
  assert.equal(elements.verifyModal.getAttribute('aria-busy'), 'true');
});
