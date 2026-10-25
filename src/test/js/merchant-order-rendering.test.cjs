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

function setupOrders() {
  const ids = [...page.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
  const elements = Object.fromEntries(ids.map(id => [id, {
    value: id === 'merchantOrderPageSize' ? '10' : '', innerHTML: '', textContent: '', hidden: false, disabled: false,
    setAttribute() {}
  }]));
  const ctx = vm.createContext({ $: id => elements[id], merchantOrders: [], merchantOrdersPage: 1 });
  vm.runInContext(['escapeHtml', 'statusClass', 'formatMerchantAmount', 'merchantOrderPage', 'renderOrders', 'renderMerchantOrders', 'showMerchantOrderError'].map(pageFunction).join('\n'), ctx);
  return { ctx, container: elements.portalOrders };
}

test('merchant order fields render as text even when stored values contain HTML', () => {
  const { ctx, container } = setupOrders();
  const attack = '<img src=x onerror="window.attacked=true">';
  const fields = ['outTradeNo', 'tradeNo', 'channelId', 'productName', 'amountText', 'statusText', 'createdAt'];
  ctx.renderOrders([Object.fromEntries([...fields.map(field => [field, attack]), ['status', 'COMPLETED']])]);
  assert.doesNotMatch(container.innerHTML, /<img|<script|onerror="/i);
  // The order number appears once as text and once in the copy button's data attribute.
  assert.equal((container.innerHTML.match(/&lt;img src=x onerror=&quot;window\.attacked=true&quot;&gt;/g) || []).length, fields.length + 1);
  assert.match(container.innerHTML, /data-copy-order="&lt;img src=x onerror=&quot;window\.attacked=true&quot;&gt;"/);
  assert.match(container.innerHTML, /<span class="status">/);
});

test('ordinary order identifiers and missing optional fields remain readable', () => {
  const { ctx, container } = setupOrders();
  ctx.renderOrders([{ outTradeNo: 'ORDER_A&B', productName: '支付宝', amountText: '¥1.00', status: 'UNPAID', statusText: '未支付', createdAt: '2026-10-25' }]);
  assert.match(container.innerHTML, /ORDER_A&amp;B/);
  assert.equal((container.innerHTML.match(/<td\b[^>]*>-<\/td>/g) || []).length, 2);
  assert.match(container.innerHTML, /class="amount-cell">¥1\.00<\/td>/);
  assert.match(container.innerHTML, /status pending/);
});
