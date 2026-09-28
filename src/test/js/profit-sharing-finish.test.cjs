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
const names = ['channelById', 'douyinProfitQueryPayload', 'singleProfitShareKey', 'readSingleProfitShare',
  'setSingleProfitShareBusy', 'profitShareFinishKey', 'readProfitShareFinish', 'saveProfitShareFinish',
  'syncProfitShareFinishRequest', 'profitShareFinishPayload', 'showProfitShareFinishResult',
  'queryProfitShareFinish', 'finishProfitShare'];
const response = (payload, status = 'PENDING') => ({ status, channelId: payload.channelIds[0],
  outTradeNo: payload.outTradeNo, tradeNo: payload.tradeNo,
  raw: { profit_sharing_operation: 'FINISH', profit_sharing_out_order_no: payload.outRequestNo } });

function setup(options = {}) {
  const defaults = { profitShareChannel: 'dy', profitShareOutTradeNo: 'ORDER-1', profitShareTradeNo: 'TRADE-1',
    profitShareQueryOutRequestNo: 'SPLIT-ORIGINAL', profitShareFinishRequestNo: '' };
  const elements = new Proxy({}, { get(target, id) {
    return target[id] ||= { value: defaults[id] || '', disabled: false, textContent: '' };
  } });
  const storage = options.storage || new Map();
  const calls = [], confirmations = [], messages = [];
  const state = { singleProfitShareBusy: false, singleProfitShareBodies: new Map(),
    channels: [{ id: 'dy', provider: 'DOUYIN' }, { id: 'dy2', provider: 'DOUYIN' }, { id: 'ali', provider: 'ALIPAY' }],
    orders: [{ outTradeNo: 'ORDER-1', tradeNo: 'TRADE-1', channelId: 'dy', profitShared: false },
      { outTradeNo: 'ORDER-2', tradeNo: 'TRADE-1', channelId: 'dy2', profitShared: false },
      { outTradeNo: 'ORDER-3', tradeNo: 'TRADE-2', channelId: 'dy', profitShared: false }] };
  let writes = 0;
  const ctx = vm.createContext({ state, Uint8Array, $: id => elements[id], window: { crypto: webcrypto },
    sessionStorage: {
      getItem(key) { if (options.failRead) throw Error('read failed'); return storage.get(key) ?? null; },
      setItem(key, value) { writes++; if (options.failWrite || (options.failResultWrite && writes > 1)) throw Error('write failed'); storage.set(key, value); }
    },
    renderProfitShareOrders() {}, renderProfitShareBatchResults() {},
    openProfitShareConfirm: async details => {
      confirmations.push(details);
      return options.confirm ? options.confirm(details) : options.cancel !== true;
    },
    request: async (url, config) => {
      const payload = JSON.parse(config.body);
      calls.push({ url, payload });
      return options.request ? options.request(url, payload) : response(payload);
    },
    setProfitShareResult: result => messages.push(result)
  });
  vm.runInContext(names.map(pageFunction).join('\n'), ctx);
  return { ctx, state, elements, storage, calls, confirmations, messages };
}
const rejected = (promise, pattern) => assert.rejects(promise, error => pattern.test(error.message));

test('finish confirmation can be cancelled without sending or recording a request', async () => {
  new vm.Script(script);
  const t = setup({ cancel: true });
  await t.ctx.finishProfitShare();
  assert.equal(t.calls.length, 0);
  assert.equal(t.storage.size, 0);
  assert.equal(t.confirmations[0].kind, 'finish');
  assert.equal(t.confirmations[0].order, 'ORDER-1');
  assert.match(t.confirmations[0].valueNote, /TRADE-1/);
  assert.match(t.confirmations[0].noticeTitle, /不能再次分账/);
  assert.equal(t.state.singleProfitShareBusy, false);
});

test('finish uses a separate request ID, carries no recipient or secrets and never marks split success', async () => {
  const t = setup();
  t.elements.profitShareExtra.value = '{"private_key":"secret"}';
  t.elements.profitShareAppAuthToken.value = 'secret-token';
  await t.ctx.finishProfitShare();
  assert.equal(t.calls[0].url, '/api/v1/payments/profit-sharing/finish');
  assert.deepEqual(Object.keys(t.calls[0].payload).sort(), ['channelIds', 'description', 'extra', 'outRequestNo', 'outTradeNo', 'tradeNo']);
  assert.deepEqual(t.calls[0].payload.extra, {});
  assert.match(t.calls[0].payload.outRequestNo, /^PSF_[a-f0-9]{24}$/);
  assert.equal(t.elements.profitShareFinishRequestNo.value, t.calls[0].payload.outRequestNo);
  assert.equal(t.elements.profitShareQueryOutRequestNo.value, 'SPLIT-ORIGINAL');
  assert.match(t.messages.at(-1).message, /已提交.*处理中/);
  assert.equal(t.state.orders[0].profitShared, false);
  assert.deepEqual(Object.keys(JSON.parse([...t.storage.values()][0])).sort(), ['outRequestNo', 'outTradeNo', 'status']);
  assert.doesNotMatch([...t.storage.values()][0], /secret|receiver|token/i);
});

test('confirmation and in-flight locks prevent concurrent finish submissions', async () => {
  let approve, resolve;
  const t = setup({ confirm: () => new Promise(done => { approve = done; }), request: () => new Promise(done => { resolve = done; }) });
  const first = t.ctx.finishProfitShare();
  await t.ctx.finishProfitShare();
  await t.ctx.queryProfitShareFinish();
  assert.equal(t.confirmations.length, 1);
  assert.equal(t.calls.length, 0);
  assert.equal(t.elements.finishProfitShareBtn.disabled, true);
  assert.equal(t.elements.profitShareAdvancedFields.disabled, true);
  approve(true);
  await new Promise(done => setImmediate(done));
  await t.ctx.finishProfitShare();
  assert.equal(t.calls.length, 1);
  resolve(response(t.calls[0].payload));
  await first;
  assert.equal(t.elements.finishProfitShareBtn.disabled, false);
});

test('timeout and page reload reuse the saved finish identity by querying instead of submitting again', async () => {
  const first = setup({ request: async () => { throw { message: 'timeout' }; } });
  await rejected(first.ctx.finishProfitShare(), /原解冻请求号.*不要重新分账/s);
  const original = first.calls[0].payload.outRequestNo;
  const reload = setup({ storage: first.storage });
  reload.ctx.syncProfitShareFinishRequest();
  assert.equal(reload.elements.profitShareFinishRequestNo.value, original);
  await reload.ctx.finishProfitShare();
  await reload.ctx.queryProfitShareFinish();
  assert.equal(reload.confirmations.length, 0);
  assert.ok(reload.calls.every(call => call.url.endsWith('/query') && call.payload.outRequestNo === original));
  assert.ok(reload.calls.every(call => call.payload.extra.operation === 'FINISH'));
  assert.equal(reload.elements.profitShareQueryOutRequestNo.value, 'SPLIT-ORIGINAL');
});

test('finish identities are isolated by both channel and payment transaction', async () => {
  const t = setup();
  await t.ctx.finishProfitShare();
  t.elements.profitShareChannel.value = 'dy2';
  t.elements.profitShareOutTradeNo.value = 'ORDER-2';
  t.ctx.syncProfitShareFinishRequest();
  assert.equal(t.elements.profitShareFinishRequestNo.value, '');
  await t.ctx.finishProfitShare();
  t.elements.profitShareChannel.value = 'dy';
  t.elements.profitShareOutTradeNo.value = 'ORDER-3';
  t.elements.profitShareTradeNo.value = 'TRADE-2';
  await t.ctx.finishProfitShare();
  assert.equal(new Set(t.calls.map(call => call.payload.outRequestNo)).size, 3);
  assert.equal(t.storage.size, 3);
});

test('an unresolved split blocks ending the same order until its original result is checked', async () => {
  const t = setup();
  t.storage.set(t.ctx.singleProfitShareKey(), JSON.stringify({ outRequestNo: 'SPLIT_ORIGINAL', status: 'UNCONFIRMED', fingerprint: 'a'.repeat(64) }));
  await rejected(t.ctx.finishProfitShare(), /先查询分账结果/);
  assert.equal(t.calls.length, 0);
  assert.equal(t.confirmations.length, 0);
});

test('only a matching FINISH success confirms unfrozen funds, never plain split success', async () => {
  const cases = [data => { delete data.raw.profit_sharing_operation; },
    data => { data.channelId = 'dy2'; }, data => { data.tradeNo = 'OTHER'; },
    data => { data.outTradeNo = 'OTHER'; }, data => { data.raw.profit_sharing_out_order_no = 'OTHER'; },
    data => { data.status = 'PENDING'; }, data => { data.status = 'FAILED'; },
    data => { data.status = 'UNKNOWN'; }];
  for (const mutate of cases) {
    const t = setup({ request: async (url, payload) => { const data = response(payload, 'SUCCESS'); mutate(data); return data; } });
    await t.ctx.finishProfitShare();
    assert.doesNotMatch(t.messages.at(-1).message, /已确认结束/);
    assert.notEqual(JSON.parse([...t.storage.values()][0]).status, 'SUCCESS');
    assert.equal(t.state.orders[0].profitShared, false);
  }
  const good = setup({ request: async (url, payload) => response(payload, 'SUCCESS') });
  await good.ctx.finishProfitShare();
  assert.match(good.messages.at(-1).message, /已确认结束分账并解冻/);
  await good.ctx.finishProfitShare();
  assert.equal(good.calls[1].url, '/api/v1/payments/profit-sharing/query');
  assert.equal(good.state.orders[0].profitShared, false);
});

test('storage failure prevents a new finish and result-write failure preserves original identity', async () => {
  for (const option of [{ failRead: true }, { failWrite: true }]) {
    const t = setup(option);
    await rejected(t.ctx.finishProfitShare(), /无法.*解冻请求/);
    assert.equal(t.calls.length, 0);
  }
  const first = setup({ failResultWrite: true });
  await rejected(first.ctx.finishProfitShare(), /无法保存/);
  const reload = setup({ storage: first.storage });
  await reload.ctx.finishProfitShare();
  assert.equal(reload.calls[0].payload.outRequestNo, first.calls[0].payload.outRequestNo);
  assert.equal(reload.calls[0].url, '/api/v1/payments/profit-sharing/query');
});

test('later pending or mismatched queries never downgrade saved success or falsely display confirmed success', async () => {
  let status = 'SUCCESS';
  const t = setup({ request: async (url, payload) => {
    const data = response(payload, status);
    if (status === 'MISMATCH') { data.status = 'SUCCESS'; data.tradeNo = 'OTHER'; }
    return data;
  } });
  await t.ctx.finishProfitShare();
  for (status of ['PENDING', 'MISMATCH']) {
    await t.ctx.queryProfitShareFinish();
    assert.equal(JSON.parse([...t.storage.values()][0]).status, 'SUCCESS');
    assert.doesNotMatch(t.messages.at(-1).message, /已确认结束/);
  }
});

test('historical finish request IDs with hyphens and stars can be queried and remembered without affecting split ID', async () => {
  const t = setup({ request: async (url, payload) => response(payload, 'SUCCESS') });
  t.elements.profitShareFinishRequestNo.value = 'FINISH-1001*';
  await t.ctx.queryProfitShareFinish();
  assert.equal(t.calls[0].payload.outRequestNo, 'FINISH-1001*');
  assert.equal(t.calls[0].url, '/api/v1/payments/profit-sharing/query');
  assert.equal(t.calls[0].payload.extra.operation, 'FINISH');
  t.ctx.syncProfitShareFinishRequest();
  assert.equal(t.elements.profitShareFinishRequestNo.value, 'FINISH-1001*');
  await t.ctx.finishProfitShare();
  assert.equal(t.calls[1].payload.outRequestNo, 'FINISH-1001*');
  assert.equal(t.calls[1].url, '/api/v1/payments/profit-sharing/query');
  assert.equal(t.elements.profitShareQueryOutRequestNo.value, 'SPLIT-ORIGINAL');
});

test('finish rejects Alipay channels and mismatched order identity before any confirmation or API call', async () => {
  for (const field of ['profitShareChannel', 'profitShareTradeNo', 'profitShareOutTradeNo']) {
    const t = setup();
    t.elements[field].value = field === 'profitShareChannel' ? 'ali' : 'OTHER';
    await rejected(t.ctx.finishProfitShare(), /仅适用于抖音|不一致/);
    assert.equal(t.confirmations.length, 0);
    assert.equal(t.calls.length, 0);
  }
});
