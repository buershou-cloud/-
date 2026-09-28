const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const page = fs.readFileSync(path.join(__dirname, '../../main/resources/static/index.html'), 'utf8');
const script = page.match(/<script>([\s\S]*?)<\/script>/)[1];
const cashier = fs.readFileSync(path.join(__dirname, '../../main/resources/static/cashier.html'), 'utf8');
const cashierScript = cashier.match(/<script>([\s\S]*?)<\/script>/)[1];
const unfreezeSelect = page.match(/<select id="profitShareUnfreezeUnsplit">([\s\S]*?)<\/select>/)[1];
const defaultUnfreeze = unfreezeSelect.match(/<option value="([^"]+)"[^>]*\bselected\b/)?.[1]
  || unfreezeSelect.match(/<option value="([^"]+)"/)[1];
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
    profitShareUnfreezeUnsplit: defaultUnfreeze, profitRelationExtra: '{}', profitRelationType: 'MERCHANT_ID',
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
    'douyinProfitQueryPayload', 'queryProfitShare', 'queryProfitShareRemaining',
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

test('the visible Douyin default releases remaining funds and an explicit keep-frozen choice is preserved', () => {
  assert.equal(defaultUnfreeze, 'true');
  const form = page.slice(page.indexOf('id="profitShareFormFields"'), page.indexOf('id="profitShareLastRequestNo"'));
  assert.ok(form.indexOf('id="profitShareUnfreezeUnsplit"') < form.indexOf('<details'));
  assert.match(form, /解冻后，该订单不能再次分账/);
  const { ctx, elements } = setup();
  elements.profitShareChannel.value = 'dy';
  elements.profitShareOutTradeNo.value = 'DY-ORDER';
  elements.receiver.value = 'DY-MERCHANT';
  elements.receiverType.value = 'MERCHANT_ID';
  assert.equal(ctx.profitShareSinglePayload().extra.unfreeze_unsplit, true);
  elements.profitShareUnfreezeUnsplit.value = 'false';
  elements.profitShareExtra.value = '{"unfreeze_unsplit":true}';
  assert.equal(ctx.profitShareSinglePayload().extra.unfreeze_unsplit, false);
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

test('Douyin remaining amount can be queried before any allocation request exists', async () => {
  const { ctx, elements, calls } = setup();
  elements.profitShareChannel.value = 'dy';
  elements.profitShareTradeNo.value = 'DY-TRADE';
  await ctx.queryProfitShareRemaining();
  assert.equal(calls[0].url, '/api/v1/payments/profit-sharing/remaining');
  assert.equal(JSON.parse(calls[0].body).tradeNo, 'DY-TRADE');
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

function setupCashier(provider, product) {
  const elements = { profitSharingPanel: { hidden: true }, profitSharingEnabled: { checked: true, disabled: false } };
  const ctx = vm.createContext({ currentChannel: provider ? { id: provider, provider } : null, currentMerchant: null,
    currentProduct: product, cashierProfitSharingChoice: true,
    $: id => elements[id] });
  vm.runInContext(['isDouyinH5Product', 'isDouyinNativeProduct', 'syncCashierProfitSharing',
    'rememberCashierProfitSharingChoice', 'cashierPaymentExtra'].map(name => pageFunction(name, cashierScript)).join('\n'), ctx);
  return { ctx, elements };
}

test('cashier defaults Douyin desktop Native and mobile H5 orders to profit sharing', () => {
  new vm.Script(cashierScript);
  assert.match(cashier, /id="profitSharingEnabled"[^>]*checked/);
  assert.match(cashierScript, /let cashierProfitSharingChoice = true;/);
  for (const product of ['DOUYIN_NATIVE', 'DOUYIN_H5']) {
    const { ctx, elements } = setupCashier('DOUYIN', product);
    ctx.syncCashierProfitSharing();
    assert.equal(elements.profitSharingPanel.hidden, false);
    assert.equal(elements.profitSharingEnabled.checked, true);
    assert.equal(elements.profitSharingEnabled.disabled, false);
    assert.equal(ctx.cashierPaymentExtra().settle_info.profit_sharing, true);
  }
  assert.match(cashierScript, /const extra = cashierPaymentExtra\(\);/);
});

test('merchant QR routing includes Douyin sharing without a preselected channel', () => {
  for (const product of ['DOUYIN_NATIVE', 'DOUYIN_H5']) {
    const { ctx, elements } = setupCashier(null, product);
    ctx.currentMerchant = { merchantId: 'M1', name: 'Merchant' };
    ctx.currentAvailableChannels = [
      { id: 'ali', provider: 'ALIPAY', products: ['ALIPAY_F2F'] },
      { id: 'dy1', provider: 'DOUYIN', products: [product] },
      { id: 'dy2', provider: 'DOUYIN', products: [product] }
    ];
    ctx.syncCashierProfitSharing();
    assert.equal(elements.profitSharingPanel.hidden, false);
    const extra = ctx.cashierPaymentExtra();
    assert.equal(extra.settle_info.profit_sharing, true);
    assert.equal(extra.merchantId, 'M1');
    assert.equal(extra.merchantName, 'Merchant');
  }
});

test('Douyin opt-out sends explicit false and survives repeated UI updates', () => {
  assert.match(cashierScript, /\$\("profitSharingEnabled"\)\.addEventListener\("change", rememberCashierProfitSharingChoice\)/);
  for (const provider of ['DOUYIN', null]) {
    for (const product of ['DOUYIN_NATIVE', 'DOUYIN_H5']) {
      const { ctx, elements } = setupCashier(provider, product);
      ctx.syncCashierProfitSharing();
      elements.profitSharingEnabled.checked = false;
      ctx.rememberCashierProfitSharingChoice();
      ctx.syncCashierProfitSharing();
      assert.equal(elements.profitSharingEnabled.checked, false);
      assert.equal(ctx.cashierPaymentExtra().settle_info.profit_sharing, false);
      ctx.currentProduct = 'ALIPAY_F2F';
      ctx.syncCashierProfitSharing();
      assert.equal(ctx.cashierPaymentExtra().settle_info, undefined);
      ctx.currentProduct = product;
      ctx.syncCashierProfitSharing();
      assert.equal(ctx.cashierPaymentExtra().settle_info.profit_sharing, false);
      elements.profitSharingEnabled.checked = true;
      ctx.rememberCashierProfitSharingChoice();
      ctx.syncCashierProfitSharing();
      assert.equal(ctx.cashierPaymentExtra().settle_info.profit_sharing, true);
    }
  }
});

test('Alipay and mixed merchant routing keep original settlement even with a stale checkbox', () => {
  for (const [provider, product] of [['ALIPAY', 'ALIPAY_F2F'], ['ALIPAY_DIRECT', 'ALIPAY_DIRECT_ORDER_CODE'],
    [null, 'ALIPAY_ORDER_CODE'], [null, 'ALIPAY_WAP'], [null, undefined]]) {
    const { ctx, elements } = setupCashier(provider, product);
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

async function openCashierPage({ userAgent, search, channels }) {
  const elements = Object.fromEntries([...cashier.matchAll(/\bid="([^"]+)"/g)].map(match => [match[1], {
    value: '', checked: match[1] === 'profitSharingEnabled', hidden: false, disabled: false, style: {}, listeners: {},
    classList: { add() {}, remove() {} }, removeAttribute() {},
    addEventListener(event, callback) { this.listeners[event] = callback; }
  }]));
  const submissions = [];
  const ctx = vm.createContext({ URLSearchParams, URL, navigator: { userAgent },
    location: { search, origin: 'https://pay.example', pathname: '/cashier.html' },
    document: { getElementById: id => elements[id] },
    fetch: async (url, options = {}) => {
      let result;
      if (url === '/api/v1/channels') result = channels;
      else if (url === '/api/v1/merchants/M1/cashier-info') {
        result = { merchantId: 'M1', name: 'Merchant', channelIds: channels.map(channel => channel.id) };
      } else if (url === '/api/v1/payments/pay') {
        submissions.push(JSON.parse(options.body));
        result = { status: 'FAILED', message: 'Test ends after capturing request' };
      } else throw Error(`Unexpected request: ${url}`);
      return { ok: true, text: async () => JSON.stringify(result) };
    }
  });
  vm.runInContext(cashierScript, ctx);
  await new Promise(resolve => setImmediate(resolve));
  return { elements, submissions };
}

test('real cashier load and submit preserve default and opt-out for channel and merchant QR routes', async () => {
  const channels = [
    { id: 'dy1', provider: 'DOUYIN', enabled: true, products: ['DOUYIN_H5', 'DOUYIN_NATIVE'] },
    { id: 'dy2', provider: 'DOUYIN', enabled: true, products: ['DOUYIN_H5', 'DOUYIN_NATIVE'] }
  ];
  for (const [userAgent, expectedProduct] of [['Desktop', 'DOUYIN_NATIVE'], ['iPhone', 'DOUYIN_H5']]) {
    for (const search of ['?channelId=dy1', '?merchantId=M1']) {
      const { elements, submissions } = await openCashierPage({ userAgent, search, channels });
      assert.equal(elements.profitSharingPanel.hidden, false);
      assert.equal(elements.profitSharingEnabled.checked, true);
      elements.amount.value = '1.00';
      elements.subject.value = 'Test payment';
      await elements.cashierForm.listeners.submit({ preventDefault() {} });
      assert.equal(submissions.length, 1);
      assert.equal(submissions[0].product, expectedProduct);
      assert.equal(submissions[0].extra.settle_info.profit_sharing, true);
      elements.profitSharingEnabled.checked = false;
      elements.profitSharingEnabled.listeners.change();
      await elements.cashierForm.listeners.submit({ preventDefault() {} });
      assert.equal(submissions[1].extra.settle_info.profit_sharing, false);
      assert.equal(submissions[1].extra.merchantId, search.includes('merchantId') ? 'M1' : undefined);
    }
  }
});

test('real mixed-channel merchant QR keeps Alipay payment payload unchanged', async () => {
  const channels = [
    { id: 'ali', provider: 'ALIPAY', enabled: true, products: ['ALIPAY_F2F'] },
    { id: 'dy', provider: 'DOUYIN', enabled: true, products: ['DOUYIN_H5', 'DOUYIN_NATIVE'] }
  ];
  for (const userAgent of ['Desktop', 'iPhone']) {
    const { elements, submissions } = await openCashierPage({ userAgent, search: '?merchantId=M1', channels });
    assert.equal(elements.profitSharingPanel.hidden, true);
    assert.equal(elements.profitSharingEnabled.disabled, true);
    elements.amount.value = '1.00';
    await elements.cashierForm.listeners.submit({ preventDefault() {} });
    assert.equal(submissions[0].product, 'ALIPAY_F2F');
    assert.deepEqual(submissions[0].extra, { cashier: true, merchantId: 'M1', merchantName: 'Merchant' });
  }
});
