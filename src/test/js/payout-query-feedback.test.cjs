const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const page = fs.readFileSync(path.join(__dirname, '../../main/resources/static/index.html'), 'utf8');
const script = page.match(/<script>([\s\S]*?)<\/script>/)[1];
function pageFunction(name) {
  const match = new RegExp(`^( +)(?:async )?function ${name}\\(`, 'm').exec(script);
  assert.ok(match, `Missing ${name}`);
  return script.slice(match.index, script.indexOf(`\n${match[1]}}`, match.index) + match[1].length + 2);
}
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const douyin = { outBizNo: 'DY+1001', provider: 'DOUYIN', channelId: 'dy-original', status: 'PROCESSING', amount: 0.1 };
const alipay = { outBizNo: 'ALI1002', provider: 'ALIPAY', channelId: 'ali-original', status: 'PROCESSING', amount: 1 };
function operation(record) { return { ...record, recordType: 'PAYOUT', orderNo: record.outBizNo }; }
function button(record, action = 'query') {
  return { disabled: false, textContent: '查询原单', dataset: {
    payoutQuery: record.outBizNo, payoutProvider: record.provider, payoutChannel: record.channelId,
    operationType: 'PAYOUT', operationNo: record.outBizNo, operationChannel: record.channelId, operationAction: action
  }};
}
function setup(options = {}) {
  const elements = new Proxy({}, { get(target, id) {
    return target[id] ||= { textContent: '', innerHTML: '', hidden: true, value: '', listeners: {},
      addEventListener(type, listener) { this.listeners[type] = listener; }, scrollIntoView() {} };
  }});
  const state = { payouts: [douyin, alipay], orderOperations: [operation(douyin), operation(alipay)],
    payoutQueries: new Map(), payoutQueryLatest: {}, orderOperationsError: '' };
  const calls = [];
  const ctx = vm.createContext({ state, $: id => elements[id],
    html: value => String(value ?? '').replace(/[<>&"]/g, c => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c])),
    money: value => `¥${Number(value).toFixed(2)}`,
    request: async (url, config) => {
      calls.push({ url, config });
      return options.request ? options.request(url, config) : { ...douyin, status: 'SUCCESS' };
    },
    loadPayouts: options.loadPayouts || (async () => {}),
    loadOrders: options.loadOrders || (async () => {}),
    setDashboardResult: result => { elements.dashboardResult.textContent = result.message; },
    renderOrders: () => { elements.recentOrders.innerHTML = state.orderOperations.map(ctx.operationOrderRow).join(''); }
  });
  const functions = ['normalizeStatusCode', 'payoutProviderText', 'payoutStatusText', 'payoutStatusBadge',
    'stripPayoutDiagnosticId', 'payoutBusinessText', 'payoutOperationSummary', 'renderPayouts',
    'payoutQueryKey', 'payoutQueryState', 'payoutQueryFeedback', 'publishPayoutQuery', 'payoutQueryHeading',
    'handlePayoutQuery', 'queryPayout', 'refreshAfterPayout', 'operationTypeText', 'operationStatusText',
    'operationOrderRow', 'handleOperationAction'];
  vm.runInContext(functions.map(pageFunction).join('\n'), ctx);
  if (options.realLoadPayouts) vm.runInContext(pageFunction('loadPayouts'), ctx);
  const listener = script.match(/  \$\("payoutTableBody"\)\.addEventListener\("click", \(event\) => \{[\s\S]*?\n  \}\);/)[0];
  vm.runInContext(listener, ctx);
  return { ctx, state, elements, calls };
}

test('payout record click immediately shows original identity, busy button and inline live progress', async () => {
  const pending = deferred();
  const { ctx, state, elements, calls } = setup({ request: () => pending.promise });
  const trigger = button(douyin);
  elements.payoutChannel.value = 'ali-selected';
  elements.payoutTableBody.listeners.click({ target: { closest: () => trigger } });
  assert.equal(trigger.disabled, true);
  assert.equal(trigger.textContent, '查询中…');
  assert.equal(elements.payoutQueryResult.hidden, false);
  assert.match(elements.payoutQueryResult.textContent, /正在向平台查询原单/);
  assert.match(elements.payoutQueryResult.textContent, /DY\+1001[\s\S]*抖音[\s\S]*dy-original/);
  assert.match(elements.payoutTableBody.innerHTML, /disabled>查询中…/);
  assert.match(elements.payoutTableBody.innerHTML, /role="status" aria-live="polite" aria-busy="true"/);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, '/api/v1/payouts/DY%2B1001/query');
  assert.equal(calls[0].config.body, '{}');
  assert.equal(calls[0].config.method, 'POST');
  pending.resolve({ ...douyin, status: 'PROCESSING' });
  await state.payoutQueries.get(ctx.payoutQueryKey(douyin)).promise;
  await Promise.resolve();
  assert.equal(trigger.disabled, false);
  assert.equal(trigger.textContent, '查询原单');
  assert.match(elements.payoutQueryResult.textContent, /查询完成：上游仍在处理中，尚未确认到账/);
  assert.doesNotMatch(elements.payoutQueryResult.textContent, /确认代付成功/);
  assert.equal(calls.length, 1);
});

test('order management query shows platform progress immediately, while detail is explicitly local', async () => {
  const pending = deferred();
  const { ctx, elements } = setup({ request: () => pending.promise });
  await ctx.handleOperationAction(button(douyin, 'detail'));
  assert.match(elements.dashboardResult.textContent, /^本地代付记录：DY\+1001/);
  const trigger = button(douyin);
  const query = ctx.handleOperationAction(trigger);
  assert.equal(trigger.textContent, '查询中…');
  assert.match(elements.dashboardResult.textContent, /^正在向平台查询原单/);
  assert.doesNotMatch(elements.dashboardResult.textContent, /本地代付记录/);
  assert.match(elements.recentOrders.innerHTML, /disabled>查询中…/);
  pending.resolve({ ...douyin, status: 'SUCCESS' });
  await query;
  assert.match(elements.dashboardResult.textContent, /^查询完成：平台确认代付成功/);
  assert.equal(trigger.disabled, false);
});

test('same original payout across both entries is deduplicated, including rerendered buttons', async () => {
  const pending = deferred();
  const { ctx, calls, elements } = setup({ request: () => pending.promise });
  const original = button(douyin);
  const first = ctx.handlePayoutQuery(original, douyin, 'payouts');
  const second = ctx.handleOperationAction(button(douyin));
  await ctx.handlePayoutQuery(original, douyin, 'payouts');
  ctx.renderPayouts();
  assert.match(elements.payoutTableBody.innerHTML, /disabled>查询中…/);
  assert.equal(calls.length, 1);
  pending.resolve({ ...douyin, status: 'SUCCESS' });
  await Promise.all([first, second]);
  assert.match(elements.payoutQueryResult.textContent, /确认代付成功/);
  assert.match(elements.dashboardResult.textContent, /确认代付成功/);
  assert.doesNotMatch(elements.payoutTableBody.innerHTML, /disabled>查询中…/);
});

test('parallel providers finish in reverse order without overwriting latest summary or another row', async () => {
  const dy = deferred(), ali = deferred();
  const { ctx, elements, calls } = setup({ request: url => url.includes('DY%2B') ? dy.promise : ali.promise });
  const first = ctx.handleOperationAction(button(douyin));
  const second = ctx.handleOperationAction(button(alipay));
  ali.resolve({ ...alipay, status: 'SUCCESS' });
  await second;
  const latest = elements.dashboardResult.textContent;
  dy.resolve({ ...douyin, status: 'PROCESSING' });
  await first;
  assert.equal(elements.dashboardResult.textContent, latest);
  assert.match(latest, /ALI1002[\s\S]*支付宝[\s\S]*ali-original/);
  assert.match(ctx.payoutQueryFeedback(douyin), /上游仍在处理中[\s\S]*DY\+1001/);
  assert.match(ctx.payoutQueryFeedback(alipay), /确认代付成功[\s\S]*ALI1002/);
  assert.equal(calls.length, 2);
  assert.ok(calls.every(call => call.url.endsWith('/query') && call.config.body === '{}'));
});

test('viewing another local record during a slow query is not overwritten by the old result', async () => {
  const pending = deferred();
  const { ctx, elements } = setup({ request: () => pending.promise });
  const query = ctx.handleOperationAction(button(douyin));
  await ctx.handleOperationAction(button(alipay, 'detail'));
  pending.resolve({ ...douyin, status: 'SUCCESS' });
  await query;
  assert.match(elements.dashboardResult.textContent, /^本地代付记录：ALI1002/);
  assert.match(ctx.payoutQueryFeedback(douyin), /确认代付成功/);
});

test('upstream FAILED is visibly different from a failed query and restores the button', async () => {
  const { ctx, elements } = setup({ request: async () => ({ ...douyin, status: 'FAILED', failReason: '余额不足' }) });
  const trigger = button(douyin);
  await ctx.handlePayoutQuery(trigger, douyin, 'payouts');
  assert.match(elements.payoutQueryResult.textContent, /查询完成：平台确认代付失败[\s\S]*余额不足/);
  assert.doesNotMatch(elements.payoutQueryResult.textContent, /原单查询失败/);
  assert.equal(trigger.disabled, false);
});

test('network failure stays beside its record, escapes content and permits only an explicit requery', async () => {
  const { ctx, elements, calls } = setup({ request: async () => { throw Error('<timeout> log_id:SECRET'); } });
  const trigger = button(douyin);
  await ctx.handlePayoutQuery(trigger, douyin, 'payouts');
  assert.match(elements.payoutQueryResult.textContent, /原单查询失败：[\s\S]*DY\+1001[\s\S]*此次查询未确认代付结果/);
  assert.match(elements.payoutTableBody.innerHTML, /&lt;timeout&gt;/);
  assert.doesNotMatch(elements.payoutTableBody.innerHTML, /<timeout>|SECRET/);
  assert.equal(trigger.disabled, false);
  assert.equal(trigger.textContent, '查询原单');
  assert.equal(calls.length, 1);
  await ctx.handlePayoutQuery(trigger, douyin, 'payouts');
  assert.equal(calls.length, 2);
});

test('mismatched order, provider or channel response cannot claim a successful payout', async () => {
  for (const mismatch of [{ outBizNo: 'OTHER' }, { provider: 'ALIPAY' }, { channelId: 'OTHER' }]) {
    const { ctx, elements } = setup({ request: async () => ({ ...douyin, status: 'SUCCESS', ...mismatch }) });
    await ctx.handlePayoutQuery(button(douyin), douyin, 'payouts');
    assert.match(elements.payoutQueryResult.textContent, /原单查询失败：平台返回的原单、平台或通道信息不匹配/);
    assert.doesNotMatch(elements.payoutQueryResult.textContent, /确认代付成功/);
  }
});

test('HTTP 502 unconfirmed query cannot present cached SUCCESS as a fresh platform confirmation', async () => {
  const { ctx, state, elements } = setup();
  state.payouts = [{ ...alipay, status: 'SUCCESS' }];
  state.orderOperations = [operation(state.payouts[0])];
  const fetchCalls = [];
  ctx.AbortController = AbortController;
  ctx.window = { setTimeout, clearTimeout };
  ctx.fetch = async (url, options) => {
    fetchCalls.push({ url, options });
    return { ok: false, status: 502, text: async () => JSON.stringify({
      code: 'PAYOUT_QUERY_UNCONFIRMED',
      message: '本次原单查询未能确认结果（ORDER_NOT_EXIST）：平台未找到原单；本地状态为SUCCESS；请勿重复代付'
    }) };
  };
  vm.runInContext(pageFunction('request'), ctx);
  const trigger = button(alipay);
  await ctx.handleOperationAction(trigger);
  assert.match(elements.dashboardResult.textContent, /^原单查询失败：本次原单查询未能确认结果/);
  assert.match(elements.dashboardResult.textContent, /本地状态为SUCCESS/);
  assert.doesNotMatch(elements.dashboardResult.textContent, /查询完成|平台确认代付成功/);
  assert.match(ctx.payoutQueryFeedback(alipay), /此次查询未确认代付结果/);
  assert.equal(state.payouts[0].status, 'SUCCESS');
  assert.equal(trigger.disabled, false);
  assert.equal(fetchCalls.length, 1);
  assert.equal(fetchCalls[0].url, '/api/v1/payouts/ALI1002/query');
});

test('successful query survives failed list refresh without hiding its outcome or resending funds', async () => {
  const { ctx, elements, calls } = setup({ loadPayouts: async () => { throw Error('offline'); },
    loadOrders: async () => { throw Error('offline'); } });
  elements.payoutResult.textContent = '先前代付提交结果';
  const response = await ctx.queryPayout(douyin.outBizNo, douyin);
  assert.equal(response.status, 'SUCCESS');
  assert.match(elements.payoutQueryResult.textContent, /确认代付成功[\s\S]*记录刷新失败；以上查询结果仍有效/);
  assert.doesNotMatch(elements.payoutQueryResult.textContent, /原单查询失败/);
  assert.equal(elements.payoutResult.textContent, '先前代付提交结果');
  assert.equal(calls.length, 1);
});

test('unknown and pending responses do not imply successful arrival', async () => {
  for (const status of ['UNKNOWN', 'PENDING', 'unexpected']) {
    const { ctx, elements } = setup({ request: async () => ({ ...douyin, status }) });
    await ctx.queryPayout(douyin.outBizNo, douyin);
    assert.match(elements.payoutQueryResult.textContent, /查询完成/);
    assert.match(elements.payoutQueryResult.textContent, /尚未确认到账|结果仍待核对/);
    assert.doesNotMatch(elements.payoutQueryResult.textContent, /确认代付成功/);
  }
});

test('late payout list refresh cannot replace a newer response', async () => {
  const old = deferred(), fresh = deferred();
  let count = 0;
  const { ctx, state } = setup({ realLoadPayouts: true, request: () => (++count === 1 ? old.promise : fresh.promise) });
  const first = ctx.loadPayouts(), second = ctx.loadPayouts();
  fresh.resolve([{ ...douyin, status: 'SUCCESS' }]);
  assert.equal(await second, true);
  old.resolve([douyin]);
  assert.equal(await first, false);
  assert.equal(state.payouts[0].status, 'SUCCESS');
});

test('query feedback is adjacent to payout records in the real page and is announced accessibly', () => {
  assert.match(page, /<h2 class="panel-title">代付记录<\/h2>[\s\S]*?id="payoutQueryResult"[^>]*role="status"[^>]*aria-live="polite"[\s\S]*?id="payoutTableBody"/);
});
