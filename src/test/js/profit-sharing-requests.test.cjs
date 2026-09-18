const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { webcrypto } = require('node:crypto');
const test = require('node:test');

const page = fs.readFileSync(path.join(__dirname, '../../main/resources/static/index.html'), 'utf8');
const script = page.match(/<script>([\s\S]*?)<\/script>/)[1];
const functions = [
  'compact', 'profitShareRoyaltyParameter', 'profitShareSinglePayload',
  'submitSingleProfitShare', 'singleProfitShareKey', 'readSingleProfitShare',
  'saveSingleProfitShare', 'newSingleProfitShareRequestNo', 'canonicalProfitShare',
  'singleProfitShareFingerprint', 'confirmSingleProfitShare', 'setSingleProfitShareBusy',
  'sendSingleProfitShare', 'reviewSingleProfitShare', 'gatewayFailed',
  'gatewayFailure', 'gatewayAttemptsText'
];

function pageFunction(name) {
  const start = script.search(new RegExp(`^  (?:async )?function ${name}\\(`, 'm'));
  assert.notEqual(start, -1, `Missing ${name}`);
  return script.slice(start, script.indexOf('\n  }', start) + 4);
}

function setup(options = {}) {
  const storage = options.storage || new Map();
  const values = {
    profitShareOutTradeNo: 'ORDER-1', profitShareChannel: 'ali-main',
    profitShareMode: 'PERCENTAGE', profitShareAmount: '20', profitShareDesc: '订单分账',
    profitShareOutRequestPrefix: 'PS', profitShareExtra: '{}',
    profitShareOperatorId: '', profitShareAppAuthToken: '', profitShareUnfreezeUnsplit: 'false'
  };
  const elements = new Proxy({}, {
    get(target, id) { return target[id] ||= { value: values[id] || '', disabled: false }; }
  });
  const calls = [], confirmations = [], messages = [];
  const ctx = vm.createContext({
    TextEncoder, Uint8Array, Map,
    state: { orders: [{ outTradeNo: 'ORDER-1', tradeNo: 'TRADE-1', channelId: 'ali-main' }], singleProfitShareBusy: false, singleProfitShareBodies: new Map() },
    $: id => elements[id],
    channelById: id => ({ id, provider: options.provider || 'ALIPAY' }),
    amountField: id => Number(elements[id].value) || undefined,
    parseJson: id => JSON.parse(elements[id].value),
    selectedProfitShareRelation: () => ({ account: elements.receiver.value || 'receiver@example.com', type: 'loginName', name: '测试收入方' }),
    sessionStorage: {
      getItem: key => { if (options.failRead) throw Error('storage unavailable'); return storage.get(key) ?? null; },
      setItem: (key, value) => { if (options.failWrite) throw Error('quota exceeded'); storage.set(key, value); }
    },
    window: { crypto: options.crypto || webcrypto },
    openProfitShareConfirm: async details => {
      confirmations.push(JSON.stringify(details));
      return options.confirmation ? options.confirmation(details) : options.confirm !== false;
    },
    request: async (url, request) => {
      calls.push({ url, ...request });
      return options.request ? options.request(url, request) : { status: 'SUCCESS', code: '10000' };
    },
    loadOrders: async () => { if (options.failRefresh) throw Error('list failed'); },
    setProfitShareResult: result => messages.push(result),
    renderProfitShareOrders: () => {}
  });
  vm.runInContext(functions.map(pageFunction).join('\n'), ctx);
  return { ctx, elements, storage, calls, confirmations, messages };
}

const payload = call => JSON.parse(call.body);
const rejection = async (promise, pattern) => assert.rejects(promise, error => pattern.test(error.message));
const timeout = async () => { throw { code: 'REQUEST_TIMEOUT', message: '请求超时' }; };

test('two new allocations for the same order have distinct request IDs', async () => {
  const { ctx, calls, elements, confirmations } = setup();
  await ctx.submitSingleProfitShare();
  elements.profitShareAmount.value = '30';
  await ctx.submitSingleProfitShare();
  assert.equal(calls.length, 2);
  assert.notEqual(payload(calls[0]).outRequestNo, payload(calls[1]).outRequestNo);
  assert.equal(payload(calls[0]).royaltyParameters[0].amount_percentage, 20);
  assert.equal(payload(calls[1]).royaltyParameters[0].amount_percentage, 30);
  assert.match(confirmations[1], /额外划拨/);
});

test('even two equal new allocations have different identities', async () => {
  const { ctx, calls } = setup();
  await ctx.submitSingleProfitShare();
  await ctx.submitSingleProfitShare();
  assert.notEqual(payload(calls[0]).outRequestNo, payload(calls[1]).outRequestNo);
});

test('request IDs use allowed characters and remain short for long order numbers', () => {
  const { ctx, elements } = setup();
  elements.profitShareOutTradeNo.value = 'ORDER'.repeat(50);
  elements.profitShareOutRequestPrefix.value = 'A'.repeat(16);
  const ids = new Set(Array.from({ length: 1000 }, () => ctx.newSingleProfitShareRequestNo()));
  assert.equal(ids.size, 1000);
  for (const id of ids) assert.match(id, /^[A-Za-z0-9_]{1,64}$/);
});

test('invalid request prefix is rejected before a transfer', async () => {
  for (const prefix of ['分账', 'a-b', 'A'.repeat(17)]) {
    const { ctx, elements, calls } = setup();
    elements.profitShareOutRequestPrefix.value = prefix;
    await rejection(ctx.submitSingleProfitShare(), /请求号前缀/);
    assert.equal(calls.length, 0);
  }
});

test('timeout retry sends byte-identical body despite edited form and credentials', async () => {
  const { ctx, elements, calls, confirmations } = setup({ request: timeout });
  elements.profitShareAppAuthToken.value = 'test-only-original-token';
  await rejection(ctx.submitSingleProfitShare(), /请求超时/);
  elements.profitShareAmount.value = '50';
  elements.receiver.value = 'someone-else@example.com';
  elements.profitShareAppAuthToken.value = 'test-only-different-token';
  elements.profitShareExtra.value = 'not valid JSON';
  await rejection(ctx.sendSingleProfitShare(true), /请求超时/);
  assert.equal(calls.length, 2);
  assert.equal(calls[1].body, calls[0].body);
  assert.match(confirmations[1], /20%/);
  assert.match(confirmations[1], /receiver@example.com/);
});

test('an unresolved request blocks a new allocation and preserves the original ID', async () => {
  const { ctx, calls, elements } = setup({ request: timeout });
  await rejection(ctx.submitSingleProfitShare(), /请求超时/);
  elements.profitShareAmount.value = '80';
  await rejection(ctx.submitSingleProfitShare(), /结果未确认/);
  assert.equal(calls.length, 1);
  assert.equal(elements.profitShareLastRequestNo.value, payload(calls[0]).outRequestNo);
});

test('double clicking while in flight sends only one request and restores buttons', async () => {
  let resolve, started;
  const ready = new Promise(done => { started = done; });
  const { ctx, elements, calls } = setup({ request: () => { started(); return new Promise(done => { resolve = done; }); } });
  const first = ctx.submitSingleProfitShare();
  await ready;
  await ctx.submitSingleProfitShare();
  await ctx.sendSingleProfitShare(true);
  assert.equal(calls.length, 1);
  assert.equal(elements.shareSingleBtn.disabled, true);
  assert.equal(elements.shareChannelBtn.disabled, true);
  resolve({ status: 'SUCCESS' });
  await first;
  assert.equal(elements.shareSingleBtn.disabled, false);
  assert.equal(elements.retrySingleProfitShareBtn.disabled, false);
});

test('cancelled confirmation sends nothing and stores no request', async () => {
  const { ctx, calls, storage } = setup({ confirm: false });
  await ctx.submitSingleProfitShare();
  assert.equal(calls.length, 0);
  assert.equal(storage.size, 0);
});

test('session record stores only identity/status/digest, not credentials or receiver data', async () => {
  const { ctx, elements, storage } = setup();
  elements.profitShareAppAuthToken.value = 'private-test-token';
  elements.profitShareExtra.value = '{"private_field":"secret-test-value"}';
  await ctx.submitSingleProfitShare();
  const raw = [...storage.values()][0];
  assert.deepEqual(Object.keys(JSON.parse(raw)).sort(), ['fingerprint', 'outRequestNo', 'status']);
  assert.doesNotMatch(raw, /private-test-token|secret-test-value|receiver@example/);
});

test('reload preserves unresolved identity and a matching form can retry it', async () => {
  const original = setup({ request: timeout });
  await rejection(original.ctx.submitSingleProfitShare(), /请求超时/);
  const reloaded = setup({ storage: original.storage });
  await rejection(reloaded.ctx.submitSingleProfitShare(), /结果未确认/);
  await reloaded.ctx.sendSingleProfitShare(true);
  assert.equal(reloaded.calls[0].body, original.calls[0].body);
});

test('reload refuses changed parameters and accepts semantic JSON key reordering', async () => {
  const original = setup({ request: timeout });
  original.elements.profitShareExtra.value = '{"a":1,"b":2}';
  await rejection(original.ctx.submitSingleProfitShare(), /请求超时/);
  const reloaded = setup({ storage: original.storage });
  reloaded.elements.profitShareAmount.value = '25';
  await rejection(reloaded.ctx.sendSingleProfitShare(true), /当前参数与原请求不一致/);
  assert.equal(reloaded.calls.length, 0);
  reloaded.elements.profitShareAmount.value = '20';
  reloaded.elements.profitShareExtra.value = '{"b":2,"a":1}';
  await reloaded.ctx.sendSingleProfitShare(true);
  assert.equal(payload(reloaded.calls[0]).outRequestNo, payload(original.calls[0]).outRequestNo);
});

test('storage read, corruption or write failure prevents sending a transfer', async () => {
  for (const options of [{ failRead: true }, { failWrite: true }]) {
    const { ctx, calls } = setup(options);
    await rejection(ctx.submitSingleProfitShare(), /无法.*分账请求/);
    assert.equal(calls.length, 0);
  }
  const { ctx, calls, storage } = setup();
  storage.set(ctx.singleProfitShareKey(), '{broken');
  await rejection(ctx.submitSingleProfitShare(), /无法读取/);
  assert.equal(calls.length, 0);
});

test('lack of secure-context hashing stops before sending a transfer', async () => {
  const { ctx, calls } = setup({ crypto: { getRandomValues: array => webcrypto.getRandomValues(array) } });
  await rejection(ctx.submitSingleProfitShare(), /HTTPS/);
  assert.equal(calls.length, 0);
});

test('accepted request is not retransmitted and list refresh failure is reported separately', async () => {
  const { ctx, calls, messages } = setup({ failRefresh: true });
  await ctx.submitSingleProfitShare();
  assert.match(messages.at(-1).message, /订单列表刷新失败/);
  await rejection(ctx.sendSingleProfitShare(true), /已被接口受理/);
  assert.equal(calls.length, 1);
});

test('pending, failed, system-error and malformed responses never unlock automatic new requests', async () => {
  for (const response of [{ status: 'PENDING' }, { status: 'FAILED', code: 'ACQ.SYSTEM_ERROR' }, { status: 'UNKNOWN' }, 'bad response']) {
    const { ctx, calls } = setup({ request: async () => response });
    await ctx.submitSingleProfitShare().catch(() => {});
    await rejection(ctx.submitSingleProfitShare(), /结果未确认/);
    assert.equal(calls.length, 1);
  }
});

test('manual reconciliation only unlocks explicit new allocation; it makes no API request', async () => {
  const { ctx, calls, confirmations } = setup({ request: timeout });
  await rejection(ctx.submitSingleProfitShare(), /请求超时/);
  await ctx.reviewSingleProfitShare();
  assert.equal(calls.length, 1);
  assert.match(confirmations.at(-1), /不查询、不撤销/);
  await rejection(ctx.submitSingleProfitShare(), /请求超时/);
  assert.notEqual(payload(calls[0]).outRequestNo, payload(calls[1]).outRequestNo);
});

test('cancelled manual reconciliation leaves unresolved request blocked', async () => {
  const original = setup({ request: timeout });
  await rejection(original.ctx.submitSingleProfitShare(), /请求超时/);
  const reload = setup({ storage: original.storage, confirm: false });
  await reload.ctx.reviewSingleProfitShare();
  await rejection(reload.ctx.submitSingleProfitShare(), /结果未确认/);
});

test('retry of a different order cannot reuse another order request', async () => {
  const { ctx, calls, elements } = setup({ request: timeout });
  await rejection(ctx.submitSingleProfitShare(), /请求超时/);
  elements.profitShareOutTradeNo.value = 'ORDER-2';
  await rejection(ctx.sendSingleProfitShare(true), /没有该订单/);
  assert.equal(calls.length, 1);
});

test('Douyin keeps amount and unfreeze options while gaining stable retry identity', async () => {
  const { ctx, calls, elements } = setup({ provider: 'DOUYIN', request: timeout });
  elements.profitShareUnfreezeUnsplit.value = 'true';
  await rejection(ctx.submitSingleProfitShare(), /请求超时/);
  await rejection(ctx.sendSingleProfitShare(true), /请求超时/);
  assert.equal(payload(calls[0]).royaltyParameters[0].amount, 20);
  assert.equal(payload(calls[0]).extra.unfreeze_unsplit, true);
  assert.equal(calls[0].body, calls[1].body);
});

test('new and retry controls are separate and retry handler uses the retained attempt', () => {
  assert.match(page, /id="shareSingleBtn"[^>]*>新增单笔分账/);
  assert.match(page, /id="retrySingleProfitShareBtn"[^>]*>重试原单笔分账/);
  assert.match(script, /\$\("retrySingleProfitShareBtn"\)\.addEventListener\("click", async \(\) => \{\s*try \{ await sendSingleProfitShare\(true\)/);
});

test('custom confirmation waits for approval and freezes the displayed allocation', async () => {
  let approve;
  const { ctx, elements, calls, confirmations } = setup({ confirmation: () => new Promise(resolve => { approve = resolve; }) });
  const pending = ctx.submitSingleProfitShare();
  assert.equal(calls.length, 0);
  assert.equal(elements.shareSingleBtn.disabled, true);
  await ctx.submitSingleProfitShare();
  assert.equal(confirmations.length, 1);
  elements.profitShareAmount.value = '90';
  elements.receiver.value = 'changed@example.com';
  approve(true);
  await pending;
  assert.equal(payload(calls[0]).royaltyParameters[0].amount_percentage, 20);
  assert.equal(payload(calls[0]).royaltyParameters[0].trans_in, 'receiver@example.com');
  assert.equal(JSON.parse(confirmations[0]).request, payload(calls[0]).outRequestNo);
});

test('manual review remains locked while the custom confirmation is open', async () => {
  const { ctx, calls } = setup({ request: timeout });
  await rejection(ctx.submitSingleProfitShare(), /请求超时/);
  let cancel, shown = 0;
  ctx.openProfitShareConfirm = () => { shown++; return new Promise(resolve => { cancel = resolve; }); };
  const pending = ctx.reviewSingleProfitShare();
  await ctx.reviewSingleProfitShare();
  await ctx.submitSingleProfitShare();
  assert.equal(shown, 1);
  assert.equal(calls.length, 1);
  cancel(false);
  await pending;
  assert.equal(ctx.state.singleProfitShareBusy, false);
  await rejection(ctx.submitSingleProfitShare(), /结果未确认/);
});

test('amount confirmation never rounds away part of the submitted value', async () => {
  const { ctx, elements, confirmations } = setup({ confirm: false });
  elements.profitShareMode.value = 'AMOUNT';
  elements.profitShareAmount.value = '1.234';
  await ctx.submitSingleProfitShare();
  assert.equal(JSON.parse(confirmations[0]).value, '¥ 1.234');
});

function setupModal() {
  const elements = new Proxy({}, {
    get(target, id) {
      return target[id] ||= {
        textContent: '', dataset: {}, checked: false, disabled: false, open: false,
        classList: { toggle() {} }, focus() { this.focused = true; },
        showModal() { this.open = true; }, close() { this.open = false; }
      };
    }
  });
  const ctx = vm.createContext({ state: { profitShareConfirmResolve: null }, $: id => elements[id] });
  vm.runInContext(['openProfitShareConfirm', 'closeProfitShareConfirm'].map(pageFunction).join('\n'), ctx);
  return { ctx, elements };
}

test('custom modal displays values as text and puts initial focus on cancel', async () => {
  const { ctx, elements } = setupModal();
  const pending = ctx.openProfitShareConfirm({ kind: 'new', title: '确认新增分账', receiver: '<img src=x onerror=alert(1)>', value: '20%', request: 'PS_test' });
  assert.equal(elements.profitShareConfirmModal.open, true);
  assert.equal(elements.profitShareConfirmReceiver.textContent, '<img src=x onerror=alert(1)>');
  assert.equal(elements.profitShareConfirmValue.textContent, '20%');
  assert.equal(elements.profitShareConfirmRequest.textContent, 'PS_test');
  assert.equal(elements.cancelProfitShareConfirmBtn.focused, true);
  ctx.closeProfitShareConfirm(false);
  assert.equal(await pending, false);
  assert.equal(elements.profitShareConfirmModal.open, false);
  assert.equal(ctx.state.profitShareConfirmResolve, null);
  ctx.closeProfitShareConfirm(true); // Duplicate close events must be harmless.
});

test('manual review requires acknowledgement and resets it on each opening', async () => {
  const { ctx, elements } = setupModal();
  const pending = ctx.openProfitShareConfirm({ kind: 'review' });
  assert.equal(elements.acceptProfitShareConfirmBtn.disabled, true);
  ctx.closeProfitShareConfirm(true);
  assert.equal(elements.profitShareConfirmModal.open, true);
  elements.profitShareConfirmReviewed.checked = true;
  ctx.closeProfitShareConfirm(true);
  assert.equal(await pending, true);
  const next = ctx.openProfitShareConfirm({ kind: 'review' });
  assert.equal(elements.profitShareConfirmReviewed.checked, false);
  ctx.closeProfitShareConfirm(false);
  assert.equal(await next, false);
});

test('a second modal cannot replace the pending confirmation', async () => {
  const { ctx } = setupModal();
  const pending = ctx.openProfitShareConfirm({ kind: 'new' });
  assert.throws(() => ctx.openProfitShareConfirm({ kind: 'retry' }), error => /当前分账确认/.test(error.message));
  ctx.closeProfitShareConfirm(true);
  assert.equal(await pending, true);
});

test('unsupported or failed modal opening fails closed and releases pending state', async () => {
  const { ctx, elements } = setupModal();
  elements.profitShareConfirmModal.showModal = () => { throw Error('not active'); };
  await rejection(ctx.openProfitShareConfirm({ kind: 'new' }), /未提交分账/);
  assert.equal(ctx.state.profitShareConfirmResolve, null);
  elements.profitShareConfirmModal.showModal = undefined;
  assert.throws(() => ctx.openProfitShareConfirm({ kind: 'new' }), error => /不支持/.test(error.message));
});

test('profit-sharing flows use custom dialogs with named actions and Escape cancellation', () => {
  assert.doesNotMatch(pageFunction('sendSingleProfitShare') + pageFunction('reviewSingleProfitShare'), /window\.confirm/);
  assert.match(page, /<dialog id="profitShareConfirmModal"[^>]*aria-labelledby="profitShareConfirmTitle"/);
  assert.match(script, /\$\("profitShareConfirmModal"\)\.addEventListener\("cancel", \(event\) => \{\s*event.preventDefault\(\);\s*closeProfitShareConfirm\(false\)/);
  assert.match(script, /\$\("cancelProfitShareConfirmBtn"\)\.addEventListener\("click", \(\) => closeProfitShareConfirm\(false\)\)/);
});
