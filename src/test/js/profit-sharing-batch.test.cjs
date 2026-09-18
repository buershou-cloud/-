const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { webcrypto } = require('node:crypto');
const test = require('node:test');

const page = fs.readFileSync(path.join(__dirname, '../../main/resources/static/index.html'), 'utf8');
const script = page.match(/<script>([\s\S]*?)<\/script>/)[1];
function pageFunction(name) {
  const start = script.search(new RegExp(`^  (?:async )?function ${name}\\(`, 'm'));
  assert.notEqual(start, -1, `Missing ${name}`);
  return script.slice(start, script.indexOf('\n  }', start) + 4);
}
const names = [
  'html', 'compact', 'profitShareRoyaltyParameter', 'profitShareSinglePayload',
  'singleProfitShareKey', 'readSingleProfitShare', 'saveSingleProfitShare',
  'newSingleProfitShareRequestNo', 'canonicalProfitShare', 'singleProfitShareFingerprint',
  'setSingleProfitShareBusy', 'selectableProfitShareOrders', 'selectProfitShareOrder',
  'selectAllProfitShareOrders', 'renderProfitShareOrders', 'profitShareMinorUnits',
  'profitShareMoneyFromUnits', 'prepareSelectedProfitShares', 'confirmSelectedProfitShares',
  'submitSelectedProfitShares', 'renderProfitShareBatchResults', 'gatewayFailed',
  'gatewayFailure', 'gatewayAttemptsText', 'confirmSingleProfitShare', 'sendSingleProfitShare', 'openBatchProfitShareOrder'
];
const order = (number, extra = {}) => ({ outTradeNo: `ORDER-${number}`, tradeNo: `TRADE-${number}`,
  channelId: 'ali-main', amount: 10, amountText: '10.00', status: 'COMPLETED', profitShared: false, ...extra });

function setup(options = {}) {
  const defaults = { profitShareOutTradeNo: '', profitShareChannel: 'ali-main', profitShareMode: 'PERCENTAGE',
    profitShareAmount: '20', profitShareDesc: '批量分账测试', profitShareOutRequestPrefix: 'PS',
    profitShareExtra: '{}', profitShareOperatorId: '', profitShareAppAuthToken: '', profitShareUnfreezeUnsplit: 'false' };
  const elements = new Proxy({}, { get(target, id) {
    return target[id] ||= { value: defaults[id] || '', disabled: false, checked: false, innerHTML: '', textContent: '',
      classList: { toggle() {} } };
  } });
  const storage = options.storage || new Map();
  const calls = [], confirmations = [], messages = [];
  const state = { orders: options.orders || [order(1), order(2), order(3)], singleProfitShareBusy: false,
    singleProfitShareBodies: new Map(), selectedProfitShareOrders: new Set(options.selected || ['ORDER-1', 'ORDER-2']),
    profitShareBatchBusy: false, profitShareBatchStop: false, profitShareBatchResults: [],
    profitSharingRelations: [{ channelId: 'ali-main', status: 'BOUND', receiverAccount: 'receiver@example.com', receiverType: 'loginName' }] };
  const ctx = vm.createContext({ state, TextEncoder, Uint8Array,
    $: id => elements[id], window: { crypto: webcrypto },
    channelById: id => ({ id, name: '模拟通道', provider: options.provider || 'ALIPAY' }),
    amountField: id => Number(elements[id].value) || undefined,
    parseJson: id => JSON.parse(elements[id].value),
    selectedProfitShareRelation: () => ({ account: elements.receiver.value || 'receiver@example.com', type: 'loginName', name: '模拟收入方' }),
    sessionStorage: {
      getItem: key => storage.get(key) || null,
      setItem: (key, value) => { if (options.storageWrite) options.storageWrite(key, value); storage.set(key, value); }
    },
    request: async (url, request) => {
      calls.push({ url, ...request });
      return options.request ? options.request(calls.length, JSON.parse(request.body)) : { status: 'SUCCESS' };
    },
    openProfitShareConfirm: async details => { confirmations.push(details); return options.confirm ? options.confirm(details) : options.cancel !== true; },
    loadOrders: async () => { if (options.failRefresh) throw Error('list failure'); },
    loadProfitSharingRelations: async () => {},
    syncProfitShareModeFields: () => {}, syncProfitShareRelationType: () => {},
    setProfitShareResult: value => messages.push(value)
  });
  vm.runInContext(names.map(pageFunction).join('\n'), ctx);
  return { ctx, state, elements, storage, calls, confirmations, messages };
}
const payload = call => JSON.parse(call.body);
const reject = (promise, pattern) => assert.rejects(promise, error => pattern.test(error.message));

test('batch sends only selected orders through single-order API with separate identities', async () => {
  const t = setup();
  await t.ctx.submitSelectedProfitShares();
  assert.deepEqual(t.calls.map(call => payload(call).outTradeNo), ['ORDER-1', 'ORDER-2']);
  assert.equal(new Set(t.calls.map(call => payload(call).outRequestNo)).size, 2);
  assert.ok(t.calls.every(call => call.url === '/api/v1/payments/profit-sharing'));
  assert.ok(t.state.profitShareBatchResults.every(item => item.status === 'ACCEPTED'));
  assert.equal(t.state.selectedProfitShareOrders.size, 0);
  assert.equal(t.confirmations.length, 1);
  assert.equal(t.confirmations[0].value, '¥ 4.00');
});

test('percentage estimates match server HALF_UP rounding per order before summing', async () => {
  const t = setup({ orders: [order(1, { amount: 1.01 }), order(2, { amount: 2.03 })], cancel: true });
  t.elements.profitShareAmount.value = '50';
  await t.ctx.submitSelectedProfitShares();
  assert.equal(t.confirmations[0].value, '¥ 1.53');
  assert.deepEqual(Array.from(t.confirmations[0].batchItems, item => item.amount), ['0.51', '1.02']);
});

test('fixed amount is applied per order, not divided across the batch', async () => {
  const t = setup();
  t.elements.profitShareMode.value = 'AMOUNT';
  t.elements.profitShareAmount.value = '2.01';
  await t.ctx.submitSelectedProfitShares();
  assert.equal(t.confirmations[0].value, '¥ 4.02');
  assert.match(t.confirmations[0].valueNote, /不是整批总金额/);
  assert.ok(t.calls.every(call => payload(call).royaltyParameters[0].amount === 2.01));
});

test('cancelling the batch sends nothing and stores no identities', async () => {
  const t = setup({ cancel: true });
  await t.ctx.submitSelectedProfitShares();
  assert.equal(t.calls.length, 0);
  assert.equal(t.storage.size, 0);
  assert.equal(t.state.selectedProfitShareOrders.size, 2);
  assert.equal(t.state.singleProfitShareBusy, false);
});

test('empty, oversized, cross-channel and stale selections fail before any transfer', async () => {
  for (const options of [
    { selected: [] }, { selected: ['MISSING'] },
    { orders: [order(1), order(2, { channelId: 'other' })] },
    { orders: [order(1), order(2, { status: 'REFUNDED' })] },
    { orders: [order(1), order(2, { tradeNo: '' })] },
    { orders: Array.from({ length: 51 }, (_, i) => order(i)), selected: Array.from({ length: 51 }, (_, i) => `ORDER-${i}`) }
  ]) {
    const t = setup(options);
    // Render prunes invalid/stale selections; reinsert to exercise preflight too.
    t.ctx.renderProfitShareOrders = () => {};
    await assert.rejects(t.ctx.submitSelectedProfitShares());
    assert.equal(t.calls.length, 0);
    assert.equal(t.storage.size, 0);
  }
});

test('channel and bound receiver must match all selected orders', async () => {
  for (const alter of [t => { t.elements.profitShareChannel.value = ''; },
    t => { t.state.profitSharingRelations[0].channelId = 'other'; },
    t => { t.state.profitSharingRelations[0].status = 'UNBOUND'; },
    t => { t.elements.receiver.value = 'not-bound@example.com'; }]) {
    const t = setup(); alter(t);
    await assert.rejects(t.ctx.submitSelectedProfitShares());
    assert.equal(t.calls.length, 0);
  }
});

test('invalid amounts, over-allocation and sub-cent percentage block the whole batch', async () => {
  for (const [mode, value] of [['PERCENTAGE', '101'], ['PERCENTAGE', '0'], ['AMOUNT', '-1'], ['AMOUNT', '1.234'], ['AMOUNT', '11'], ['PERCENTAGE', '0.01']]) {
    const t = setup();
    t.elements.profitShareMode.value = mode;
    t.elements.profitShareAmount.value = value;
    await assert.rejects(t.ctx.submitSelectedProfitShares());
    assert.equal(t.calls.length, 0);
    assert.equal(t.storage.size, 0);
  }
});

test('partial failure stops later orders, retains original identity and removes accepted selections', async () => {
  const t = setup({ selected: ['ORDER-1', 'ORDER-2', 'ORDER-3'], request: async index => index === 2 ? { status: 'FAILED', code: 'ACQ.ERROR', message: '模拟拒绝' } : { status: 'SUCCESS' } });
  await t.ctx.submitSelectedProfitShares();
  assert.equal(t.calls.length, 2);
  assert.deepEqual(Array.from(t.state.profitShareBatchResults, item => item.status), ['ACCEPTED', 'UNCONFIRMED', 'NOT_SENT']);
  assert.deepEqual([...t.state.selectedProfitShareOrders], ['ORDER-2', 'ORDER-3']);
  assert.equal(t.storage.size, 2);
  assert.equal(t.ctx.readSingleProfitShare(t.ctx.singleProfitShareKey(t.state.orders[1])).outRequestNo, payload(t.calls[1]).outRequestNo);
  await reject(t.ctx.submitSelectedProfitShares(), /结果未确认/);
  assert.equal(t.calls.length, 2);
  t.elements.profitShareOutTradeNo.value = 'ORDER-2';
  t.elements.profitShareAmount.value = '90';
  await t.ctx.sendSingleProfitShare(true);
  assert.equal(t.calls[2].body, t.calls[1].body);
  assert.deepEqual([...t.state.selectedProfitShareOrders], ['ORDER-3']);
  assert.equal(t.state.profitShareBatchResults[1].status, 'ACCEPTED');
});

test('timeout, pending, unknown and malformed results pause instead of treating them as success', async () => {
  for (const response of ['timeout', { status: 'PENDING' }, { status: 'UNKNOWN' }, null, 'bad payload']) {
    const t = setup({ request: async () => { if (response === 'timeout') throw Error('timeout'); return response; } });
    await t.ctx.submitSelectedProfitShares();
    assert.equal(t.calls.length, 1);
    assert.deepEqual(Array.from(t.state.profitShareBatchResults, item => item.status), ['UNCONFIRMED', 'NOT_SENT']);
    assert.equal(t.ctx.readSingleProfitShare(t.ctx.singleProfitShareKey(t.state.orders[0])).status, 'UNCONFIRMED');
  }
});

test('moving queued or failed orders to single handling restores original batch rules', async () => {
  const t = setup({ request: async () => { throw Error('timeout'); } });
  t.elements.profitShareAppAuthToken.value = 'original-test-token';
  t.elements.profitShareExtra.value = '{"test_extra":"original"}';
  await t.ctx.submitSelectedProfitShares();
  for (const id of ['ORDER-1', 'ORDER-2']) {
    t.elements.profitShareAmount.value = '100';
    t.elements.profitShareAppAuthToken.value = 'changed-token';
    await t.ctx.openBatchProfitShareOrder(id);
    assert.equal(t.elements.profitShareOutTradeNo.value, id);
    assert.equal(t.elements.profitShareAmount.value, 20);
    assert.equal(t.elements.profitShareDesc.value, '批量分账测试');
    assert.equal(t.elements.profitShareTransIn.value, 'loginName|receiver@example.com');
    assert.equal(t.elements.profitShareAppAuthToken.value, 'original-test-token');
    assert.equal(t.calls.length, 1);
  }
  assert.equal(t.storage.size, 1); // Queued, unsent requests have no persistent attempt.
});

test('storage failure before sending stops the queue without a transfer', async () => {
  const t = setup({ storageWrite: () => { throw Error('quota'); } });
  await t.ctx.submitSelectedProfitShares();
  assert.equal(t.calls.length, 0);
  assert.ok(t.state.profitShareBatchResults.every(item => item.status === 'NOT_SENT'));
});

test('storage failure after acceptance preserves accepted result and stops remaining orders', async () => {
  const t = setup({ storageWrite: (key, value) => { if (JSON.parse(value).status === 'ACCEPTED') throw Error('quota'); } });
  await t.ctx.submitSelectedProfitShares();
  assert.equal(t.calls.length, 1);
  assert.equal(t.state.profitShareBatchResults[0].status, 'ACCEPTED');
  assert.match(t.state.profitShareBatchResults[0].message, /记录保存失败/);
  assert.equal(t.state.profitShareBatchResults[1].status, 'NOT_SENT');
});

test('stop lets the current request finish but never starts the next one', async () => {
  let finish, notify;
  const started = new Promise(resolve => { notify = resolve; });
  const t = setup({ request: () => { notify(); return new Promise(resolve => { finish = resolve; }); } });
  const pending = t.ctx.submitSelectedProfitShares();
  await started;
  t.state.profitShareBatchStop = true;
  finish({ status: 'SUCCESS' });
  await pending;
  assert.equal(t.calls.length, 1);
  assert.deepEqual(Array.from(t.state.profitShareBatchResults, item => item.status), ['ACCEPTED', 'NOT_SENT']);
});

test('a waiting confirmation locks duplicate submissions and freezes every payload', async () => {
  let approve, notify;
  const opened = new Promise(resolve => { notify = resolve; });
  const t = setup({ confirm: () => { notify(); return new Promise(resolve => { approve = resolve; }); } });
  const pending = t.ctx.submitSelectedProfitShares();
  await opened;
  assert.equal(t.calls.length, 0);
  await t.ctx.submitSelectedProfitShares();
  t.elements.profitShareAmount.value = '90';
  t.elements.receiver.value = 'changed@example.com';
  approve(true);
  await pending;
  assert.equal(t.confirmations.length, 1);
  assert.equal(t.calls.length, 2);
  assert.ok(t.calls.every(call => payload(call).royaltyParameters[0].amount_percentage === 20));
  assert.ok(t.calls.every(call => payload(call).royaltyParameters[0].trans_in === 'receiver@example.com'));
});

test('changed order data after confirmation is checked before sending', async () => {
  const t = setup({ confirm: () => { t.state.orders[0].status = 'REFUNDED'; return true; } });
  await t.ctx.submitSelectedProfitShares();
  assert.equal(t.calls.length, 0);
  assert.match(t.state.profitShareBatchResults[0].message, /订单信息已变化/);
});

test('select-all excludes previously shared orders but individual selection allows them', async () => {
  const t = setup({ orders: [order(1), order(2, { profitShared: true }), order(3, { channelId: 'other' })], selected: [], cancel: true });
  t.ctx.selectAllProfitShareOrders(true);
  assert.deepEqual([...t.state.selectedProfitShareOrders], ['ORDER-1']);
  t.ctx.selectProfitShareOrder('ORDER-2', true);
  await t.ctx.submitSelectedProfitShares();
  assert.match(t.confirmations[0].noticeTitle, /1 笔曾分账/);
  assert.equal(t.confirmations[0].batchItems[1].shared, true);
});

test('selection cap rejects oversized select-all without silently selecting a subset', () => {
  const t = setup({ orders: Array.from({ length: 51 }, (_, i) => order(i)), selected: [] });
  assert.throws(() => t.ctx.selectAllProfitShareOrders(true), error => /超过 50/.test(error.message));
  assert.equal(t.state.selectedProfitShareOrders.size, 0);
  for (let i = 0; i < 50; i++) t.ctx.selectProfitShareOrder(`ORDER-${i}`, true);
  assert.throws(() => t.ctx.selectProfitShareOrder('ORDER-50', true), error => /最多选择 50/.test(error.message));
  assert.equal(t.state.selectedProfitShareOrders.size, 50);
});

test('render prunes stale selections and maintains the select-all partial state', () => {
  const t = setup({ selected: ['ORDER-1', 'MISSING'] });
  t.ctx.renderProfitShareOrders();
  assert.deepEqual([...t.state.selectedProfitShareOrders], ['ORDER-1']);
  assert.equal(t.elements.selectAllProfitShareOrders.indeterminate, true);
  t.elements.profitShareChannel.value = 'other';
  t.ctx.renderProfitShareOrders();
  assert.equal(t.state.selectedProfitShareOrders.size, 0);
  assert.equal(t.elements.shareChannelBtn.disabled, true);
});

test('refresh failure cannot relabel accepted transfers as failed', async () => {
  const t = setup({ failRefresh: true });
  await t.ctx.submitSelectedProfitShares();
  assert.ok(t.state.profitShareBatchResults.every(item => item.status === 'ACCEPTED'));
  assert.match(t.messages.at(-1).message, /列表刷新失败/);
});

test('batch session storage never persists receiver details or authorization', async () => {
  const t = setup();
  t.elements.profitShareAppAuthToken.value = 'test-private-token';
  await t.ctx.submitSelectedProfitShares();
  for (const raw of t.storage.values()) {
    assert.deepEqual(Object.keys(JSON.parse(raw)).sort(), ['fingerprint', 'outRequestNo', 'status']);
    assert.doesNotMatch(raw, /test-private-token|receiver@example/);
  }
});

test('Douyin batch retains amount and unfreeze options', async () => {
  const t = setup({ provider: 'DOUYIN' });
  t.elements.profitShareAmount.value = '2';
  t.elements.profitShareUnfreezeUnsplit.value = 'true';
  await t.ctx.submitSelectedProfitShares();
  assert.ok(t.calls.every(call => payload(call).royaltyParameters[0].amount === 2 && payload(call).extra.unfreeze_unsplit === true));
});

test('UI no longer submits the entire channel and exposes selection, stop and row results', () => {
  assert.doesNotMatch(script, /request\("\/api\/v1\/payments\/profit-sharing\/channel"/);
  assert.match(page, /id="selectAllProfitShareOrders"/);
  assert.match(page, /id="stopProfitShareBatchBtn"/);
  assert.match(page, /id="profitShareBatchResults"/);
  assert.match(script, /await submitSelectedProfitShares\(\)/);
  assert.match(script, /window.addEventListener\("beforeunload"/);
});
