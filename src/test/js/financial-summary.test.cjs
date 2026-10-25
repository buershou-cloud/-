const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const page = fs.readFileSync(path.join(__dirname, '../../main/resources/static/index.html'), 'utf8');
function pageFunction(name) {
  const start = page.search(new RegExp(`^  (?:async )?function ${name}\\(`, 'm'));
  assert.notEqual(start, -1, `Missing ${name}`);
  return page.slice(start, page.indexOf('\n  }', start) + 4);
}

function summary(overrides = {}) {
  return {
    todayTradeAmount: 30, todayTradeCount: 2, monthTradeAmount: 130, monthTradeCount: 3,
    todayRefundAmount: 30, todayRefundCount: 1, reportReceivable: 99, reportFee: 1,
    reportMismatch: null,
    reportRows: [
      { date: '2026-10-25', count: 2, payAmount: 30, refundAmount: 30, settlementStatus: 'UNCONFIRMED' },
      { date: '2026-10-24', count: 1, payAmount: 100, refundAmount: 0, settlementStatus: 'UNCONFIRMED' }
    ], ...overrides
  };
}

function setup(summaryRequest = async () => summary()) {
  const elements = new Proxy({}, { get(target, id) {
    return target[id] ||= { value: '', textContent: '', innerHTML: '' };
  }});
  const calls = [];
  const state = { financialSummary: null, financialSummaryError: '', financialSummaryLoadSequence: 0,
    orders: [], merchants: [], orderLoadSequence: 0, orderPage: 1 };
  const ctx = vm.createContext({ state, elements, URLSearchParams, $: id => elements[id],
    localDateText: () => '2026-10-25', localMonthText: () => '2026-10',
    renderOrders() {}, renderProfitShareOrders() {},
    request: async url => {
      calls.push(url);
      if (url.includes('/summary')) return summaryRequest(url);
      if (url.includes('/operations')) return { records: [], warnings: [] };
      return [{ outTradeNo: 'SELECTED', amount: 10, status: 'COMPLETED', createdAt: '2026-10-25 09:00:00' }];
    }
  });
  const names = ['loadFinancialSummary', 'refreshSummaryViews', 'renderDashboardMetrics', 'renderReports',
    'loadOrders', 'orderQueryString', 'setText', 'html', 'money', 'paidOrder', 'orderAmountValue',
    'refundedAmountValue', 'renderMerchantSettlementRows'];
  vm.runInContext(names.map(pageFunction).join('\n'), ctx);
  return { ctx, state, elements, calls };
}

test('order filters do not change global metrics or the daily refund totals', async () => {
  const { ctx, state, elements, calls } = setup();
  elements.orderOutTradeNo.value = 'SELECTED';
  elements.orderChannelFilter.value = 'ali-main';
  await ctx.loadOrders();
  await new Promise(setImmediate);
  assert.equal(state.orders.length, 1);
  assert.equal(elements.todayTradeAmount.textContent, '¥30.00');
  assert.equal(elements.monthTradeAmount.textContent, '¥130.00');
  assert.equal(elements.todayRefundAmount.textContent, '¥30.00');
  assert.equal(elements.todayRefundCount.textContent, '1 笔');
  assert.equal(elements.reportReceivable.textContent, '¥99.00');
  const query = new URL(calls.find(url => url.includes('/summary')), 'http://localhost').searchParams;
  assert.equal(query.get('today'), '2026-10-25');
  assert.equal(query.get('month'), '2026-10');
  assert.equal(query.has('outTradeNo'), false);
  assert.equal(query.has('channelId'), false);
});

test('missing summary does not silently substitute list totals or zero mismatch counts', async () => {
  let fail = false;
  const { ctx, state, elements } = setup(async () => {
    if (fail) throw Error('offline');
    return summary();
  });
  await ctx.loadFinancialSummary();
  state.orders = [{ status: 'COMPLETED', amount: 9999, createdAt: '2026-10-25' }];
  fail = true;
  assert.equal(await ctx.loadFinancialSummary(), false);
  assert.equal(elements.todayTradeAmount.textContent, '加载失败');
  assert.equal(elements.todayRefundCount.textContent, '加载失败');
  assert.equal(elements.reportReceivable.textContent, '加载失败');
  assert.equal(elements.reportMismatch.textContent, '加载失败');
  assert.match(elements.reportRows.innerHTML, /统计加载失败/);
  assert.equal(state.financialSummary, null);
});

test('status reconciliation completes before its refreshed global summary is requested', async () => {
  const { ctx, elements } = setup();
  let finishReconciliation;
  let summaryCalls = 0;
  ctx.request = async url => {
    if (url.includes('/summary')) {
      summaryCalls++;
      return summary({ todayTradeAmount: 500 });
    }
    if (url.includes('/operations')) return { records: [], warnings: [] };
    return new Promise(resolve => { finishReconciliation = resolve; });
  };
  const loading = ctx.loadOrders(false, true);
  assert.equal(summaryCalls, 0);
  finishReconciliation([]);
  await loading;
  await new Promise(setImmediate);
  assert.equal(summaryCalls, 1);
  assert.equal(elements.todayTradeAmount.textContent, '¥500.00');
});

test('incomplete summary payload is reported as unavailable', async () => {
  const { ctx, elements } = setup(async () => ({ todayTradeAmount: 1, reportRows: [] }));
  assert.equal(await ctx.loadFinancialSummary(), false);
  assert.equal(elements.monthTradeAmount.textContent, '加载失败');
});

test('late old summary cannot overwrite a newer response', async () => {
  const pending = [];
  const { ctx, state } = setup(() => new Promise(resolve => pending.push(resolve)));
  const older = ctx.loadFinancialSummary();
  const newer = ctx.loadFinancialSummary();
  pending[1](summary({ todayTradeAmount: 200 }));
  assert.equal(await newer, true);
  pending[0](summary({ todayTradeAmount: 100 }));
  assert.equal(await older, false);
  assert.equal(state.financialSummary.todayTradeAmount, 200);
});

test('older report dates remain unconfirmed without settlement evidence', async () => {
  const { ctx, elements } = setup();
  await ctx.loadFinancialSummary();
  assert.equal(elements.reportMismatch.textContent, '未确认');
  assert.equal((elements.reportRows.innerHTML.match(/未确认/g) || []).length, 2);
  assert.doesNotMatch(elements.reportRows.innerHTML, /已结算/);
  ctx.renderMerchantSettlementRows({ merchantId: 'M1', feeRate: 0, settlementStatus: '已结算' }, [
    { createdAt: '2026-10-25 12:00:00', status: 'COMPLETED', amount: 10 },
    { createdAt: '2026-10-24 12:00:00', status: 'COMPLETED', amount: 20 }
  ]);
  assert.equal((elements.merchantSettlementRows.innerHTML.match(/未确认/g) || []).length, 2);
  assert.doesNotMatch(elements.merchantSettlementRows.innerHTML, /已结算|SETTLE-/);
});

test('historical receipts without event times remain visible without invented dates', async () => {
  const data = summary();
  data.reportRows.push({ date: '时间未记录', count: 1, payAmount: 5, refundAmount: 0, settlementStatus: 'UNCONFIRMED' });
  const { ctx, elements } = setup(async () => data);
  assert.equal(await ctx.loadFinancialSummary(), true);
  assert.match(elements.reportRows.innerHTML, /时间未记录/);
  assert.ok(elements.reportRows.innerHTML.indexOf('时间未记录') > elements.reportRows.innerHTML.indexOf('2026-10-24'));
});
