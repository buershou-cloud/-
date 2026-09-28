const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const page = fs.readFileSync(path.join(__dirname, '../../main/resources/static/index.html'), 'utf8');
const script = page.match(/<script>([\s\S]*?)<\/script>/)[1];
const cashier = fs.readFileSync(path.join(__dirname, '../../main/resources/static/cashier.html'), 'utf8');
const cashierScript = cashier.match(/<script>([\s\S]*?)<\/script>/)[1];
function pageFunction(name, source = script) {
  const start = source.search(new RegExp(`^  (?:async )?function ${name}\\(`, 'm'));
  assert.notEqual(start, -1, `Missing ${name}`);
  return source.slice(start, source.indexOf('\n  }', start) + 4);
}

const relation = (channelId, account, type) => ({ channelId, receiverAccount: account,
  receiverType: type, receiverName: account, status: 'BOUND' });
function setup(options = {}) {
  const defaults = { profitShareChannel: 'ali', profitRelationChannel: 'dy', profitShareMode: 'PERCENTAGE',
    profitShareOutTradeNo: 'ALI-ORDER', profitShareAmount: '20', profitShareExtra: '{}',
    profitShareUnfreezeUnsplit: 'true', profitRelationExtra: '{}', profitRelationType: 'MERCHANT_ID',
    profitShareQueryOutRequestNo: '', profitShareTradeNo: '', profitRelationRelationType: 'STORE' };
  const elements = new Proxy({}, { get(target, id) {
    if (!target[id]) {
      const classes = new Set();
      target[id] = { value: defaults[id] || '', innerHTML: '', textContent: '', checked: false, hidden: false,
        classList: { toggle(name, enabled) { enabled ? classes.add(name) : classes.delete(name); }, contains(name) { return classes.has(name); } },
        get options() { return [...this.innerHTML.matchAll(/<option value="([^"]*)"/g)].map(match => ({ value: match[1] })); } };
    }
    return target[id];
  } });
  const calls = [];
  const state = { channels: [{ id: 'ali', provider: 'ALIPAY' }, { id: 'dy', provider: 'DOUYIN' }],
    profitSharingRelations: [relation('ali', 'alipay@example.com', 'loginName'), relation('dy', 'DY-MERCHANT', 'MERCHANT_ID')],
    orders: [{ outTradeNo: 'ALI-ORDER', tradeNo: 'ALI-TRADE', channelId: 'ali' },
      { outTradeNo: 'DY-ORDER', tradeNo: 'DY-TRADE', channelId: 'dy' }] };
  const ctx = vm.createContext({ state, $: id => elements[id],
    amountField: id => Number(elements[id].value) || undefined,
    parseJson: id => JSON.parse(elements[id].value),
    selectedProfitShareRelation: () => ({ account: elements.receiver.value || 'alipay@example.com',
      type: elements.receiverType.value || 'loginName', name: 'Receiver' }),
    syncProfitShareRelationType() {}, setProfitShareResult() {}, loadOrders: async () => {},
    gatewayFailed: () => false,
    request: async (url, config) => { calls.push({ url, ...config }); return options.request ? options.request(url) : { status: 'SUCCESS' }; }
  });
  vm.runInContext(['html', 'compact', 'channelById', 'receiverTypeText', 'relationOptionText',
    'renderProfitShareRelationOptions', 'renderProfitRelationTable', 'loadProfitSharingRelations',
    'profitShareRoyaltyParameter', 'profitShareSinglePayload', 'syncProfitShareModeFields',
    'douyinProfitQueryPayload', 'queryProfitShare', 'queryProfitShareRemaining', 'finishProfitShare',
    'returnProfitShare', 'queryProfitShareReturn', 'profitRelationPayload', 'matchingDouyinProfitShareResponse',
    'reconcileDouyinProfitShare'].map(name => pageFunction(name)).join('\n'), ctx);
  return { ctx, state, elements, calls };
}

test('Alipay and Douyin receiver lists stay scoped to each independently selected channel', () => {
  const { ctx, elements } = setup();
  ctx.renderProfitShareRelationOptions();
  ctx.renderProfitRelationTable();
  assert.match(elements.profitShareTransIn.innerHTML, /alipay@example.com/);
  assert.doesNotMatch(elements.profitShareTransIn.innerHTML, /DY-MERCHANT/);
  assert.match(elements.profitRelationTableBody.innerHTML, /DY-MERCHANT/);
  assert.doesNotMatch(elements.profitRelationTableBody.innerHTML, /alipay@example.com/);
  elements.profitShareChannel.value = 'dy';
  elements.profitRelationChannel.value = 'ali';
  ctx.renderProfitShareRelationOptions();
  ctx.renderProfitRelationTable();
  assert.doesNotMatch(elements.profitShareTransIn.innerHTML, /alipay@example.com/);
  assert.doesNotMatch(elements.profitRelationTableBody.innerHTML, /DY-MERCHANT/);
});

test('out-of-order loads for separate providers preserve both channel caches', async () => {
  const pending = {};
  const { ctx, state, elements } = setup({ request: url => new Promise(resolve => { pending[url.split('=')[1]] = resolve; }) });
  const alipay = ctx.loadProfitSharingRelations('ali');
  const douyin = ctx.loadProfitSharingRelations('dy');
  pending.dy([relation('dy', 'NEW-DY', 'MERCHANT_ID')]);
  await douyin;
  pending.ali([relation('ali', 'new@alipay.com', 'loginName')]);
  await alipay;
  assert.equal(state.profitSharingRelations.length, 2);
  assert.match(elements.profitShareTransIn.innerHTML, /new@alipay.com/);
  assert.doesNotMatch(elements.profitShareTransIn.innerHTML, /NEW-DY/);
  assert.match(elements.profitRelationTableBody.innerHTML, /NEW-DY/);
});

test('failed relation refresh clears stale receivers only for that channel', async () => {
  const { ctx, state, elements } = setup({ request: async () => { throw Error('offline'); } });
  await assert.rejects(ctx.loadProfitSharingRelations('ali'), /offline/);
  assert.equal(state.profitSharingRelations.length, 1);
  assert.equal(state.profitSharingRelations[0].channelId, 'dy');
  assert.doesNotMatch(elements.profitShareTransIn.innerHTML, /alipay@example.com/);
});

test('refresh preserves an existing Alipay receiver only when it is still bound', async () => {
  let relations = [relation('ali', 'alipay@example.com', 'loginName')];
  const { ctx, elements } = setup({ request: async () => relations });
  ctx.renderProfitShareRelationOptions();
  elements.profitShareTransIn.value = 'loginName|alipay@example.com';
  await ctx.loadProfitSharingRelations('ali');
  assert.equal(elements.profitShareTransIn.value, 'loginName|alipay@example.com');
  relations = [];
  await ctx.loadProfitSharingRelations('ali');
  assert.equal(elements.profitShareTransIn.value, '');
});

test('cross-channel typed orders or receiver selections cannot produce a new transfer payload', () => {
  const { ctx, elements } = setup();
  elements.profitShareOutTradeNo.value = 'DY-ORDER';
  assert.throws(() => ctx.profitShareSinglePayload(), error => /订单不属于当前支付通道/.test(error.message));
  elements.profitShareChannel.value = 'dy';
  assert.throws(() => ctx.profitShareSinglePayload(), error => /当前通道的已绑定关系/.test(error.message));
  elements.receiver.value = 'DY-MERCHANT';
  elements.receiverType.value = 'MERCHANT_ID';
  const payload = ctx.profitShareSinglePayload();
  assert.equal(payload.channelIds[0], 'dy');
  assert.equal(payload.royaltyParameters[0].trans_in_type, 'MERCHANT_ID');
  assert.equal(payload.royaltyParameters[0].amount, 20);
  assert.equal(payload.royaltyParameters[0].amount_percentage, undefined);
  assert.equal(payload.extra.unfreeze_unsplit, true);
});

test('visiting Douyin preserves the selected Alipay percentage or fixed-amount mode', () => {
  for (const mode of ['PERCENTAGE', 'AMOUNT']) {
    const { ctx, elements } = setup();
    elements.profitShareMode.value = mode;
    elements.profitShareChannel.value = 'dy';
    ctx.syncProfitShareModeFields();
    assert.equal(elements.profitShareModeField.classList.contains('hidden'), true);
    assert.equal(elements.profitShareValueLabel.textContent, '分账金额');
    elements.profitShareChannel.value = 'ali';
    ctx.syncProfitShareModeFields();
    assert.equal(elements.profitShareMode.value, mode);
    const payload = ctx.profitShareSinglePayload();
    assert.equal(payload.royaltyParameters[0][mode === 'AMOUNT' ? 'amount' : 'amount_percentage'], 20);
    assert.equal(payload.extra?.unfreeze_unsplit, undefined);
    assert.equal(elements.douyinProfitAdvanced.classList.contains('hidden'), true);
  }
});

test('Douyin remaining amount and finishing can run before any allocation request exists', async () => {
  const { ctx, elements, calls } = setup();
  elements.profitShareChannel.value = 'dy';
  elements.profitShareTradeNo.value = 'DY-TRADE';
  await ctx.queryProfitShareRemaining();
  await ctx.finishProfitShare();
  assert.equal(calls[0].url, '/api/v1/payments/profit-sharing/remaining');
  assert.equal(JSON.parse(calls[0].body).tradeNo, 'DY-TRADE');
  assert.match(JSON.parse(calls[1].body).outRequestNo, /^PSF_/);
  await assert.rejects(ctx.queryProfitShare(), error => /请填写分账请求号/.test(error.message));
});

test('Douyin return queries need request identity but no unrelated payment transaction number', async () => {
  const { ctx, elements, calls } = setup();
  elements.profitShareChannel.value = 'dy';
  elements.profitShareQueryOutRequestNo.value = 'PS-ORIGINAL';
  elements.profitShareReturnNo.value = 'RETURN-ORIGINAL';
  await ctx.queryProfitShareReturn();
  assert.equal(JSON.parse(calls[0].body).outReturnNo, 'RETURN-ORIGINAL');
  elements.profitShareChannel.value = 'ali';
  await assert.rejects(ctx.queryProfitShareRemaining(), error => /仅适用于抖音/.test(error.message));
  assert.equal(calls.length, 1);
});

test('querying Douyin merchant receivers does not require the fields used to add one', () => {
  const { ctx } = setup();
  const payload = ctx.profitRelationPayload(false, true);
  assert.equal(payload.channelIds[0], 'dy');
  assert.equal(payload.pageNum, 1);
  assert.equal(payload.extra?.relation_type, undefined);
});

function setupCashier(provider) {
  const elements = { profitSharingPanel: { hidden: true }, profitSharingEnabled: { checked: false, disabled: false } };
  const ctx = vm.createContext({ currentChannel: provider ? { id: provider, provider } : null, currentMerchant: null,
    $: id => elements[id] });
  vm.runInContext(['syncCashierProfitSharing', 'cashierPaymentExtra'].map(name => pageFunction(name, cashierScript)).join('\n'), ctx);
  return { ctx, elements };
}

test('cashier script parses and only an explicitly checked Douyin order requests frozen settlement', () => {
  new vm.Script(cashierScript);
  assert.match(cashier, /id="profitSharingEnabled" type="checkbox"/);
  assert.doesNotMatch(cashier, /id="profitSharingEnabled"[^>]*checked/);
  const { ctx, elements } = setupCashier('DOUYIN');
  ctx.syncCashierProfitSharing();
  assert.equal(elements.profitSharingPanel.hidden, false);
  assert.equal(ctx.cashierPaymentExtra().settle_info, undefined);
  elements.profitSharingEnabled.checked = true;
  assert.equal(ctx.cashierPaymentExtra().settle_info.profit_sharing, true);
  assert.match(cashierScript, /const extra = cashierPaymentExtra\(\);/);
});

test('Alipay and automatic merchant routing keep original settlement even with a stale checkbox', () => {
  for (const provider of ['ALIPAY', 'ALIPAY_DIRECT', null]) {
    const { ctx, elements } = setupCashier(provider);
    ctx.currentMerchant = { merchantId: 'M1', name: 'Merchant' };
    elements.profitSharingEnabled.checked = true;
    assert.equal(ctx.cashierPaymentExtra().settle_info, undefined);
    assert.equal(ctx.cashierPaymentExtra().merchantId, 'M1');
    ctx.syncCashierProfitSharing();
    assert.equal(elements.profitSharingPanel.hidden, true);
    assert.equal(elements.profitSharingEnabled.checked, false);
    assert.equal(elements.profitSharingEnabled.disabled, true);
  }
});
