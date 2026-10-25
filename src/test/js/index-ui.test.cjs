const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const page = fs.readFileSync(path.join(__dirname, '../../main/resources/static/index.html'), 'utf8');
function fn(name) {
  const start = page.search(new RegExp(`^  (?:async )?function ${name}\\(`, 'm'));
  assert.notEqual(start, -1, name);
  return page.slice(start, page.indexOf('\n  }', start) + 4);
}
function element() {
  const classes = new Set();
  return { value: '', innerHTML: '', textContent: '', attributes: {}, disabled: false, focused: false,
    classList: { add: n => classes.add(n), remove: n => classes.delete(n), contains: n => classes.has(n),
      toggle(n, on) { on ? classes.add(n) : classes.delete(n); } },
    focus() { this.focused = true; }, setAttribute(n, v) { this.attributes[n] = v; } };
}
function setup(names, additions = {}) {
  const elements = new Proxy({}, { get: (target, id) => target[id] ||= element() });
  const state = { orders: [], orderOperations: [], orderPage: 1, orderPageSize: 10 };
  const document = { activeElement: element(), body: element() };
  const copied = [];
  const context = vm.createContext({ state, document, $: id => elements[id], json: JSON.stringify,
    navigator: { clipboard: { writeText: async v => copied.push(v) } },
    productLabels: {}, payoutBusinessText: v => String(v), ...additions });
  vm.runInContext(['html', 'money', 'setText', ...names].map(fn).join('\n'), context);
  return { context, state, elements, document, copied };
}

test('closed and unfamiliar payment states never claim unpaid or completed', () => {
  const { context } = setup(['statusCell']);
  assert.match(context.statusCell('CLOSED'), /已关闭/);
  assert.match(context.statusCell('UNPAID'), /未支付/);
  assert.match(context.statusCell('FAILED'), /失败/);
  assert.match(context.statusCell('future-state'), /结果待核对/);
  assert.doesNotMatch(context.statusCell('future-state'), /未支付|已完成/);
});

test('display filters before pagination without altering payment accounting records', () => {
  const { context, state, elements } = setup(['orderMatchesStatus', 'displayOrderRecords', 'renderOrders', 'statusCell'], {
    productCell: () => '', refundCell: () => '', orderActionCell: () => '', renderOrderPagination() {}, operationOrderRow: () => ''
  });
  state.orders = Array.from({ length: 24 }, (_, i) => ({ outTradeNo: `P${i}`, amount: 12345.6,
    status: i % 2 ? 'UNPAID' : 'COMPLETED', createdAt: `2026-10-25 10:${String(i).padStart(2, '0')}:00` }));
  state.orderOperations = [{ recordType: 'PAYOUT', status: 'SUCCESS', orderNo: 'OUT1' }];
  elements.orderStatusFilter.value = 'UNPAID';
  state.orderPage = 2;
  context.renderOrders();
  assert.equal(context.displayOrderRecords().length, 12);
  assert.equal((elements.recentOrders.innerHTML.match(/<tr>/g) || []).length, 2);
  assert.match(elements.recentOrders.innerHTML, /amount-neutral/);
  assert.doesNotMatch(elements.recentOrders.innerHTML, /amount-green/);
  assert.match(elements.recentOrders.innerHTML, /¥12,345\.60/);
  elements.orderStatusFilter.value = 'CONFIRMED';
  elements.orderRecordType.value = 'PAYMENT';
  assert.equal(context.displayOrderRecords().length, 12);
  assert.equal(state.orders.length, 24);
  assert.equal(state.orderOperations.length, 1);
  assert.equal(context.orderMatchesStatus({ recordType: 'PAYOUT', status: 'PENDING' }, 'CONFIRMED'), false);
  assert.equal(context.orderMatchesStatus({ recordType: 'PAYOUT', status: 'unfamiliar' }, 'UNKNOWN'), true);
});

test('today and seven-day shortcuts use Beijing calendar across UTC day and month boundaries', () => {
  const { context, elements } = setup(['setOrderDateRange', 'updateOrderDateSummary']);
  context.setOrderDateRange('today', new Date('2026-10-25T17:00:00Z'));
  assert.equal(elements.orderBeginTime.value, '2026-10-26T00:00');
  assert.equal(elements.orderEndTime.value, '2026-10-26T23:59:59');
  context.setOrderDateRange('week', new Date('2026-02-28T17:00:00Z'));
  assert.equal(elements.orderBeginTime.value, '2026-02-23T00:00');
  assert.equal(elements.orderEndTime.value, '2026-03-01T23:59:59');
  context.setOrderDateRange('all');
  assert.equal(elements.orderBeginTime.value, '');
  assert.equal(elements.orderDateSummary.textContent, '不限时间');
});

test('detail drawer escapes stored content, copies only its order ID and restores focus', async () => {
  const { context, state, elements, document, copied } = setup(['openOrderDetail', 'closeOrderDetail', 'copyOrderDetailNumber',
    'statusCell', 'operationTypeText', 'operationStatusText', 'refundedAmountValue']);
  const attack = '<img src=x onerror="attack()">';
  const trigger = element();
  context.openOrderDetail({ recordType: 'PAYMENT', payment: { outTradeNo: attack, tradeNo: attack,
    merchantName: attack, subject: attack, amount: 1234.5, status: 'COMPLETED' } }, trigger);
  assert.doesNotMatch(elements.orderDetailBody.innerHTML, /<img|onerror="/);
  assert.match(elements.orderDetailBody.innerHTML, /&lt;img/);
  assert.match(elements.orderDetailBody.innerHTML, /¥1,234\.50/);
  assert.match(elements.orderDetailBody.innerHTML, /不代表刚完成一次平台查单/);
  assert.equal(elements.orderDetailOverlay.attributes['aria-hidden'], 'false');
  assert.equal(elements.closeOrderDetailBtn.focused, true);
  assert.equal(document.body.classList.contains('order-detail-open'), true);
  await context.copyOrderDetailNumber();
  assert.deepEqual(copied, [attack]);
  context.closeOrderDetail();
  assert.equal(state.orderDetailRecord, null);
  assert.equal(elements.orderDetailOverlay.attributes['aria-hidden'], 'true');
  assert.equal(trigger.focused, true);
  assert.equal(document.body.classList.contains('order-detail-open'), false);
  context.openOrderDetail({ recordType: 'PAYOUT', orderNo: 'P1', amount: null, message: attack, status: 'UNKNOWN' });
  assert.match(elements.orderDetailBody.innerHTML, /金额未记录/);
  assert.doesNotMatch(elements.orderDetailBody.innerHTML, /<img|¥0\.00/);
});

test('an error unhides the initially empty dashboard result and stays plain text', () => {
  const { context, elements } = setup(['setDashboardResult']);
  elements.dashboardResult.classList.add('hidden');
  context.setDashboardResult({ message: '<script>query failed</script>' });
  assert.equal(elements.dashboardResult.classList.contains('hidden'), false);
  assert.equal(elements.dashboardResult.textContent, '<script>query failed</script>');
  assert.equal(elements.dashboardResult.innerHTML, '');
});

test('editing channel products preserves unmodified secrets and rejects an empty selection', async () => {
  let selected = [{ value: 'ALIPAY_PAGE' }, { value: 'ALIPAY_F2F' }];
  const { context, state, elements } = setup(['compact', 'channelFormPayload'], {
    document: { querySelectorAll: () => selected }, syncChannelCallbackUrls() {},
    amountField: () => undefined, appendUploadedCertificateContent: async () => {}
  });
  state.editingChannelId = 'C1';
  elements.newChannelId.value = 'C1';
  elements.newChannelProvider.value = 'ALIPAY';
  const payload = await context.channelFormPayload();
  assert.deepEqual(Array.from(payload.products), ['ALIPAY_PAGE', 'ALIPAY_F2F']);
  for (const key of ['merchantPrivateKey', 'alipayPublicKey', 'appAuthToken', 'enabled', 'dailyEnabled']) {
    assert.equal(Object.hasOwn(payload, key), false, `${key} must remain unchanged`);
  }
  selected = [];
  await assert.rejects(() => context.channelFormPayload(), /至少选择一个支付产品/);
});
