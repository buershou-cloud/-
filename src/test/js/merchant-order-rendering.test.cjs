const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const page = fs.readFileSync(path.join(__dirname, '../../main/resources/static/merchant.html'), 'utf8');
function pageFunction(name) {
  const start = page.search(new RegExp(`^  function ${name}\\(`, 'm'));
  assert.notEqual(start, -1, `Missing ${name}`);
  return page.slice(start, page.indexOf('\n  }', start) + 4);
}

test('merchant order fields render as text even when stored values contain HTML', () => {
  const container = { innerHTML: '' };
  const ctx = vm.createContext({ $: () => container });
  vm.runInContext(['escapeHtml', 'statusClass', 'renderOrders'].map(pageFunction).join('\n'), ctx);
  const attack = '<img src=x onerror="window.attacked=true">';
  const fields = ['outTradeNo', 'tradeNo', 'channelId', 'productName', 'amountText', 'statusText', 'createdAt'];
  ctx.renderOrders([Object.fromEntries([...fields.map(field => [field, attack]), ['status', 'COMPLETED']])]);
  assert.doesNotMatch(container.innerHTML, /<img|<script|onerror="/i);
  assert.equal((container.innerHTML.match(/&lt;img src=x onerror=&quot;window\.attacked=true&quot;&gt;/g) || []).length, fields.length);
  assert.match(container.innerHTML, /<span class="status">/);
});

test('ordinary order identifiers and missing optional fields remain readable', () => {
  const container = { innerHTML: '' };
  const ctx = vm.createContext({ $: () => container });
  vm.runInContext(['escapeHtml', 'statusClass', 'renderOrders'].map(pageFunction).join('\n'), ctx);
  ctx.renderOrders([{ outTradeNo: 'ORDER_A&B', productName: '支付宝', amountText: '¥1.00', status: 'UNPAID', statusText: '未支付', createdAt: '2026-10-25' }]);
  assert.match(container.innerHTML, /ORDER_A&amp;B/);
  assert.equal((container.innerHTML.match(/<td>-<\/td>/g) || []).length, 2);
  assert.match(container.innerHTML, /status pending/);
});
