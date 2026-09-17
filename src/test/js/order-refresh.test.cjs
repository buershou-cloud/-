const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const page = fs.readFileSync(path.join(__dirname, '../../main/resources/static/index.html'), 'utf8');
const script = page.match(/<script>([\s\S]*?)<\/script>/)[1];

function pageFunction(name) {
  const start = script.search(new RegExp(`^  (?:async )?function ${name}\\(`, 'm'));
  assert.notEqual(start, -1, `Missing page function ${name}`);
  const end = script.indexOf('\n  }', start);
  assert.notEqual(end, -1);
  return script.slice(start, end + 4);
}

function setup(request) {
  const fields = ['orderBeginTime', 'orderEndTime', 'orderOutTradeNo', 'orderTradeNo', 'orderChannelFilter'];
  const elements = Object.fromEntries(fields.map(id => [id, { value: '' }]));
  elements.queryOrdersBtn = { disabled: false, textContent: '查询订单' };
  elements.resetOrderFilterBtn = { disabled: false };
  elements.orderFilterForm = { reset: () => fields.forEach(id => { elements[id].value = ''; }) };
  const context = vm.createContext({
    URLSearchParams, request,
    state: { orders: [], orderPage: 3, orderLoadSequence: 0 },
    $: id => elements[id], elements, messages: [], renders: 0
  });
  vm.runInContext(`
    function renderOrders() { renders++; }
    function renderProfitShareOrders() {}
    function refreshSummaryViews() {}
    function setDashboardResult(result) { messages.push(result.message); }
    ${pageFunction('loadOrders')}
    ${pageFunction('queryOrders')}
    ${pageFunction('orderQueryString')}
  `, context);
  return context;
}

const oldOrder = { outTradeNo: 'OLD', createdAt: '2026-09-17 10:00:00' };
const newOrder = { outTradeNo: 'NEW', createdAt: '2026-09-17 11:00:00' };

test('entire console script parses', () => {
  new vm.Script(script);
});

test('click query loads newly created orders without waiting on payment reconciliation', async () => {
  const paths = [];
  let orders = [oldOrder];
  const ctx = setup(async url => { paths.push(url); return orders; });
  await ctx.loadOrders();
  orders = [oldOrder, newOrder]; // Unsorted, like the in-memory backend.
  await ctx.queryOrders();
  assert.equal(ctx.state.orders[0].outTradeNo, 'NEW');
  assert.equal(ctx.state.orders.length, 2);
  assert.equal(ctx.state.orderPage, 1);
  assert.equal(ctx.renders, 2);
  assert.match(ctx.messages.at(-1), /已查询到 2 笔订单/);
  for (const url of paths) {
    const params = new URL(url, 'http://local.test').searchParams;
    assert.equal(params.has('refreshStatuses'), false);
    assert.ok(params.get('_'));
  }
  assert.notEqual(paths[0], paths[1]);
});

test('a late old response cannot remove a new order or reset the current page', async () => {
  const pending = [];
  const ctx = setup(() => new Promise(resolve => pending.push(resolve)));
  const olderLoad = ctx.loadOrders(true);
  const newerLoad = ctx.loadOrders(false);
  pending[1]([newOrder, oldOrder]);
  assert.equal(await newerLoad, true);
  pending[0]([oldOrder]);
  assert.equal(await olderLoad, false);
  assert.equal(ctx.state.orders[0].outTradeNo, 'NEW');
  assert.equal(ctx.state.orderPage, 3);
  assert.equal(ctx.renders, 1);
});

test('failed query shows an error, preserves the list and allows another click', async () => {
  let fail = true;
  const ctx = setup(async () => {
    if (fail) throw { message: '模拟网络失败' };
    return [newOrder];
  });
  ctx.state.orders = [oldOrder];
  await ctx.queryOrders();
  assert.equal(ctx.state.orders[0].outTradeNo, 'OLD');
  assert.match(ctx.messages.at(-1), /订单查询失败：模拟网络失败/);
  assert.equal(ctx.elements.queryOrdersBtn.disabled, false);
  assert.equal(ctx.elements.resetOrderFilterBtn.disabled, false);
  fail = false;
  await ctx.queryOrders();
  assert.equal(ctx.state.orders[0].outTradeNo, 'NEW');
  assert.equal(ctx.elements.queryOrdersBtn.textContent, '查询订单');
});

test('query retains filters, reset clears them and both fetch new results', async () => {
  const paths = [];
  const ctx = setup(async url => { paths.push(url); return []; });
  ctx.elements.orderOutTradeNo.value = ' ORDER+1001 ';
  ctx.elements.orderChannelFilter.value = 'ali-main';
  ctx.elements.orderBeginTime.value = '2026-09-17T00:00';
  await ctx.queryOrders();
  let params = new URL(paths[0], 'http://local.test').searchParams;
  assert.equal(params.get('outTradeNo'), 'ORDER+1001');
  assert.equal(params.get('channelId'), 'ali-main');
  assert.equal(params.get('beginTime'), '2026-09-17T00:00');
  await ctx.queryOrders(true);
  params = new URL(paths[1], 'http://local.test').searchParams;
  assert.equal(params.has('outTradeNo'), false);
  assert.equal(params.has('channelId'), false);
  assert.equal(params.has('beginTime'), false);
  assert.match(ctx.messages.at(-1), /筛选已重置/);
});

test('explicit status refresh after an order operation remains supported', async () => {
  let requestedUrl;
  const ctx = setup(async url => { requestedUrl = url; return []; });
  await ctx.loadOrders(false, true);
  assert.equal(new URL(requestedUrl, 'http://local.test').searchParams.get('refreshStatuses'), 'true');
});

test('a failed obsolete request cannot replace a newer query result with an error', async () => {
  const pending = [];
  const ctx = setup(() => new Promise((resolve, reject) => pending.push({ resolve, reject })));
  const olderLoad = ctx.loadOrders();
  const newerLoad = ctx.loadOrders();
  pending[1].resolve([newOrder]);
  await newerLoad;
  pending[0].reject({ message: 'old timeout' });
  assert.equal(await olderLoad, false);
  assert.equal(ctx.state.orders[0].outTradeNo, 'NEW');
});

test('unexpected server payload produces a visible error and keeps the previous list', async () => {
  const ctx = setup(async () => ({ message: 'not an order list' }));
  ctx.state.orders = [oldOrder];
  await ctx.queryOrders();
  assert.equal(ctx.state.orders[0].outTradeNo, 'OLD');
  assert.match(ctx.messages.at(-1), /订单列表返回格式异常/);
  assert.equal(ctx.elements.queryOrdersBtn.disabled, false);
});
