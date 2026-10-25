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

function setup(status = 'PENDING', remaining = 80) {
  const order = { outTradeNo: 'FREEZE1', status: 'FROZEN', preAuthorization: true,
    amount: 100, preauthCapturedAmount: 20, preauthUnfreezeRemainingAmount: remaining, channelId: 'ali' };
  const requests = [], merged = [], refreshed = [], messages = [];
  const ctx = vm.createContext({ state: { orders: [order] },
    request: async (url, options) => {
      requests.push({ url, body: JSON.parse(options.body) });
      return { gateway: { status, outTradeNo: 'CAPTURE1' }, order,
        captureOrder: { outTradeNo: 'CAPTURE1', preAuthorization: false, amount: remaining,
          status: status === 'SUCCESS' ? 'COMPLETED' : 'UNPAID' } };
    },
    mergeOrderIntoState: value => merged.push(value),
    setDashboardResult: value => messages.push(value.message),
    refreshOrdersAfterMutation: async (id, isFresh) => { refreshed.push({ id, isFresh }); },
    gatewayFailure: () => Error('upstream failed')
  });
  vm.runInContext(['capturePreauthOrder', 'preauthCaptureSubject', 'preauthUnfreezeRemainingAmountValue',
    'orderAmountValue', 'preauthUnfrozenAmountValue', 'money', 'compact', 'gatewayFailed', 'paidOrder'].map(pageFunction).join('\n'), ctx);
  return { ctx, order, requests, merged, refreshed, messages };
}

test('pending preauthorization capture tracks its child order without claiming payment completed', async () => {
  const { ctx, requests, merged, refreshed, messages } = setup();
  await ctx.capturePreauthOrder('FREEZE1');
  assert.equal(requests[0].body.totalAmount, '80.00');
  assert.equal(merged[0].outTradeNo, 'FREEZE1');
  assert.equal(merged[0].preAuthorization, true);
  assert.equal(merged[1].outTradeNo, 'CAPTURE1');
  assert.match(messages[0], /确认中.*CAPTURE1/);
  assert.doesNotMatch(messages[0], /已完成/);
  assert.equal(refreshed[0].id, 'CAPTURE1');
  assert.equal(refreshed[0].isFresh({ status: 'UNPAID' }), false);
  assert.equal(refreshed[0].isFresh({ status: 'COMPLETED' }), true);
});

test('confirmed capture feedback identifies the distinct child receipt', async () => {
  const { ctx, messages } = setup('SUCCESS');
  await ctx.capturePreauthOrder('FREEZE1');
  assert.match(messages[0], /扣款已完成：CAPTURE1/);
  assert.match(messages[0], /原冻结单 FREEZE1 保留/);
});

test('fully consumed authorization cannot fall back to capturing the original total', async () => {
  const { ctx, requests } = setup('PENDING', 0);
  await assert.rejects(ctx.capturePreauthOrder('FREEZE1'), error => /无可转支付金额/.test(error.message));
  assert.equal(requests.length, 0);
});

test('legacy remaining-amount fallback subtracts both prior captures and unfreezes', () => {
  const { ctx } = setup();
  assert.equal(ctx.preauthUnfreezeRemainingAmountValue({ amount: 100, preauthCapturedAmount: 30, preauthUnfrozenAmount: 20 }), 50);
});
