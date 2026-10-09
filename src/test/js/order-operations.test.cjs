const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const page = fs.readFileSync(path.join(__dirname, '../../main/resources/static/index.html'), 'utf8');
const script = page.match(/<script>([\s\S]*?)<\/script>/)[1];
function pageFunction(name) {
  const start = script.search(new RegExp(`^  (?:async )?function ${name}\\(`, 'm'));
  assert.notEqual(start, -1, `Missing ${name}`);
  return script.slice(start, script.indexOf('\n  }', start) + 4);
}

const payment = { outTradeNo: 'PAY1', createdAt: '2026-10-04 10:00:00', status: 'COMPLETED',
  amount: 100, amountText: '¥100.00', tradeNo: 'TRADE1', channelId: 'ali', profitShared: true };
const split = { recordType: 'PROFIT_SHARING', orderNo: 'SPLIT1', relatedOutTradeNo: 'PAY1',
  createdAt: '2026-10-04 11:00:00', status: 'SUCCESS', channelId: 'ali', provider: 'ALIPAY', amount: 30 };
const payout = { recordType: 'PAYOUT', orderNo: 'PAYOUT1', createdAt: '2026-10-04 12:00:00',
  status: 'SUCCESS', channelId: 'dy', provider: 'DOUYIN', amount: 20, recipient: '13***78' };

function setup(options = {}) {
  const elements = new Proxy({}, { get(target, id) {
    if (!target[id]) {
      const classes = new Set();
      target[id] = { value: id === 'orderRecordType' ? 'ALL' : '', textContent: '', innerHTML: '', disabled: false,
        classList: { toggle(name, enabled) { enabled ? classes.add(name) : classes.delete(name); }, contains(name) { return classes.has(name); } } };
    }
    return target[id];
  }});
  const calls = [];
  const state = { orders: [], orderOperations: [], orderOperationsError: '', orderPage: 1, orderPageSize: 10,
    orderLoadSequence: 0, payouts: [], paymentPasswordConfigured: true };
  const ctx = vm.createContext({ state, URLSearchParams, $: id => elements[id],
    html: value => String(value ?? '').replace(/[<>&"]/g, c => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c])),
    money: value => `¥${Number(value).toFixed(2)}`,
    payoutProviderText: value => value === 'DOUYIN' ? '抖音' : '支付宝',
    payoutBusinessText: value => String(value || '-'),
    productCell: order => order.profitShared ? '已分账' : '收款产品', refundCell: () => '未退款', statusCell: () => '已完成',
    orderActionCell: order => `<button data-order-action="refund" data-order-no="${order.outTradeNo}">退款</button>`,
    renderProfitShareOrders() {}, refreshSummaryViews() {}, renderPayouts() {},
    setDashboardResult: result => { elements.dashboardResult.textContent = result.message; },
    closeOrderMenus() {},
    request: async (url, config) => {
      calls.push({ url, config });
      if (options.request) return options.request(url, config);
      return url.includes('/operations') ? { records: [split, payout], warnings: [] } : [payment];
    },
    loadPayouts: options.loadPayouts || (async () => {}),
    payoutPayload: () => ({ channelId: 'dy', amount: '20.00', recipientType: 'DOUYIN_OPEN_ID' }),
    payoutBatchPayload: () => ({ items: [{ channelId: 'dy', amount: '20.00' }] }),
    openConfirmModal: async () => true,
    payoutOperationSummary: data => `代付单号：${data.outBizNo}\n状态：${data.status}`,
    payoutStatusText: value => value
  });
  const functions = ['loadOrders', 'queryOrders', 'orderQueryString', 'displayOrderRecords', 'renderOrders',
    'renderOrderPagination', 'compactPageItems', 'operationTypeText', 'operationStatusText', 'operationOrderRow',
    'handleOperationAction', 'refreshAfterPayout', 'submitPayout', 'submitPayoutBatch', 'queryPayout', 'paidOrder',
    'payoutQueryKey', 'payoutQueryState', 'payoutQueryFeedback', 'publishPayoutQuery', 'payoutQueryHeading',
    'handlePayoutQuery', 'normalizeStatusCode'];
  vm.runInContext(functions.map(pageFunction).join('\n'), ctx);
  return { ctx, state, elements, calls };
}

test('all record types share sorted display and pagination while payment accounting stays separate', async () => {
  const { ctx, state, elements } = setup();
  await ctx.loadOrders();
  assert.deepEqual(state.orders, [payment]);
  assert.deepEqual(Array.from(ctx.displayOrderRecords(), record => record.orderNo), ['PAYOUT1', 'SPLIT1', 'PAY1']);
  assert.equal(state.orders.filter(ctx.paidOrder).reduce((sum, order) => sum + order.amount, 0), 100);
  assert.match(elements.recentOrders.innerHTML, /−¥30\.00/);
  assert.match(elements.recentOrders.innerHTML, /−¥20\.00/);
  assert.match(elements.recentOrders.innerHTML, /原收款单 PAY1/);
  assert.match(elements.orderPagination.innerHTML, /共 3 笔订单记录/);
  const rows = elements.recentOrders.innerHTML.split('</tr>');
  for (const row of rows.slice(0, 2)) {
    assert.match(row, /支出/);
    assert.doesNotMatch(row, /data-order-action|data-order-menu/);
  }
  assert.match(rows[0], /查询原单/);
  assert.doesNotMatch(rows[1], /查询原单/);
  assert.match(rows[2], /data-order-action="refund"/);
  elements.orderRecordType.value = 'PROFIT_SHARING';
  ctx.renderOrders();
  assert.match(elements.orderPagination.innerHTML, /共 1 笔订单记录/);
  assert.doesNotMatch(elements.recentOrders.innerHTML, /PAYOUT1|data-order-action/);
  elements.orderRecordType.value = 'PAYMENT';
  ctx.renderOrders();
  assert.match(elements.recentOrders.innerHTML, /已分账/);
  assert.deepEqual(state.orders, [payment]);
});

test('unknown historical sharing amount is not fabricated and output remains escaped', () => {
  const { ctx, state, elements } = setup();
  state.orderOperations = [{ ...split, amount: null, recipient: '<script>unsafe</script>', orderNo: 'S"1' }];
  ctx.renderOrders();
  assert.match(elements.recentOrders.innerHTML, /金额未记录/);
  assert.doesNotMatch(elements.recentOrders.innerHTML, /¥0\.00|<script>/);
  assert.match(elements.recentOrders.innerHTML, /&lt;script&gt;/);
  assert.match(page, /无法还原当时的分账请求号和金额/);
});

test('operation API failure stays visible without discarding successful payment results', async () => {
  const { ctx, state, elements } = setup({ request: async url => {
    if (url.includes('/operations')) throw Error('offline');
    return [payment];
  }});
  await ctx.queryOrders();
  assert.deepEqual(state.orders, [payment]);
  assert.equal(state.orderOperations.length, 0);
  assert.equal(elements.orderOperationsError.classList.contains('hidden'), false);
  assert.match(elements.orderOperationsError.textContent, /不能视为没有记录/);
  assert.match(elements.dashboardResult.textContent, /支出记录不完整/);
});

test('partial operation results remain visible alongside backend warnings', async () => {
  const { ctx, state, elements } = setup({ request: async url => url.includes('/operations')
    ? { records: [split], warnings: ['代付记录暂时不可用'] } : [payment] });
  await ctx.loadOrders();
  assert.deepEqual(state.orderOperations, [split]);
  assert.match(elements.recentOrders.innerHTML, /SPLIT1/);
  assert.match(elements.orderOperationsError.textContent, /代付记录暂时不可用/);
});

test('both sources receive the same filters and stale operation responses cannot replace newer records', async () => {
  const pending = [];
  const { ctx, state, elements, calls } = setup({ request: () => new Promise(resolve => pending.push(resolve)) });
  elements.orderOutTradeNo.value = ' SPLIT+1 ';
  elements.orderChannelFilter.value = 'dy';
  const old = ctx.loadOrders();
  elements.orderOutTradeNo.value = 'PAYOUT1';
  const fresh = ctx.loadOrders();
  for (const call of calls.slice(0, 2)) {
    const query = new URL(call.url, 'https://pay.example').searchParams;
    assert.equal(query.get('outTradeNo'), 'SPLIT+1');
    assert.equal(query.get('channelId'), 'dy');
  }
  pending[2]([]);
  pending[3]({ records: [payout], warnings: [] });
  assert.equal(await fresh, true);
  pending[0]([payment]);
  pending[1]({ records: [split], warnings: [] });
  assert.equal(await old, false);
  assert.deepEqual(state.orderOperations, [payout]);
  assert.equal(state.orders.length, 0);
});

test('successful payout is returned even if both list refreshes fail, without a second transfer', async () => {
  const response = { outBizNo: 'PAYOUT1', status: 'SUCCESS' };
  const { ctx, elements, calls } = setup({ loadPayouts: async () => { throw Error('list offline'); },
    request: async (url, config) => {
      if (url === '/api/v1/payouts' && config?.method === 'POST') return response;
      throw Error('order list offline');
    } });
  assert.equal(await ctx.submitPayout(), response);
  assert.equal(calls.filter(call => call.url === '/api/v1/payouts').length, 1);
  assert.match(elements.payoutResult.textContent, /SUCCESS/);
  assert.match(elements.payoutResult.textContent, /结果仍有效.*不要重复转账/);
  assert.equal(elements.payoutPaymentPassword.value, '');
});

test('batch and query outcomes are preserved independently of refresh failures', async () => {
  const batch = { total: 1, processed: 1, rejected: 0, results: [{ payout: { outBizNo: 'PAYOUT1', status: 'SUCCESS' } }] };
  const response = { outBizNo: 'PAYOUT1', status: 'SUCCESS' };
  const { ctx, elements } = setup({ loadPayouts: async () => { throw Error('offline'); }, request: async url => {
    if (url.endsWith('/batch')) return batch;
    if (url.endsWith('/query')) return response;
    throw Error('offline');
  }});
  assert.equal(await ctx.submitPayoutBatch(), batch);
  assert.match(elements.payoutResult.textContent, /已受理 1 笔/);
  assert.match(elements.payoutResult.textContent, /不要重复转账/);
  assert.equal(await ctx.queryPayout('PAYOUT1'), response);
  assert.equal(ctx.payoutQueryState({ outBizNo: 'PAYOUT1' }).message, '成功（刷新失败）');
  assert.match(ctx.payoutQueryState({ outBizNo: 'PAYOUT1' }).detail, /以上查询结果仍有效/);
});

test('type changes and pagination use the independent display records', () => {
  const { ctx, state, elements } = setup();
  state.orderPage = 3;
  const listener = script.match(/\$\("orderRecordType"\)\.addEventListener\("change", \(\) => \{([\s\S]*?)\n  \}\);/)[1];
  vm.runInContext(listener, ctx);
  assert.equal(state.orderPage, 1);
  assert.match(page, /<option value="ALL">全部<\/option>/);
  assert.equal((script.match(/Math\.ceil\(state\.orders\.length \/ state\.orderPageSize\)/g) || []).length, 0);
  assert.match(elements.orderPagination.innerHTML, /暂无可显示记录/);
});
