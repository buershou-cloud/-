const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const page = fs.readFileSync(path.join(__dirname, '../../main/resources/static/index.html'), 'utf8');
const script = page.match(/<script>([\s\S]*?)<\/script>/)[1];
const endpoint = '/api/v1/admin-notifications/telegram';
const saved = { enabled: true, tokenConfigured: true, chatId: '-1001234567890' };
const secret = '123456789:AbCdEfGhIjKlMnOpQrStUvWxYz012345678';

function pageFunction(name) {
  const match = new RegExp(`^( +)(?:async )?function ${name}\\(`, 'm').exec(script);
  assert.ok(match, `Missing ${name}`);
  return script.slice(match.index, script.indexOf(`\n${match[1]}}`, match.index) + match[1].length + 2);
}

function deferred() {
  let resolve;
  const promise = new Promise(yes => { resolve = yes; });
  return { promise, resolve };
}

function setup(options = {}) {
  const elements = new Proxy({}, { get(target, id) {
    return target[id] ||= {
      value: '', checked: false, disabled: true, textContent: '', innerHTML: '', placeholder: '',
      classes: new Set(), attributes: {}, listeners: {},
      classList: { toggle(name, active) { if (active) elements[id].classes.add(name); else elements[id].classes.delete(name); } },
      setAttribute(name, value) { this.attributes[name] = value; },
      addEventListener(type, listener) { this.listeners[type] = listener; }
    };
  }});
  const state = { superAdministrator: options.superAdministrator ?? true, telegramNotifySettings: null, telegramNotifyBusy: '' };
  const calls = [];
  const ctx = vm.createContext({ state, $: id => elements[id], request: async (url, config) => {
    calls.push({ url, config });
    if (options.request) return options.request(url, config);
    return url.endsWith('/test') ? { success: true, message: '发送成功' } : { ...saved, ...options.settings };
  }});
  const names = ['telegramNotifyMessage', 'setTelegramNotifyResult', 'renderTelegramNotifyControls',
    'applyTelegramNotifySettings', 'loadTelegramNotifySettings', 'saveTelegramNotifySettings', 'testTelegramNotifySettings'];
  vm.runInContext(names.map(pageFunction).join('\n'), ctx);
  vm.runInContext(script.match(/  \$\("telegramNotifyForm"\)\.addEventListener\("submit", async \(event\) => \{[\s\S]*?\n  \}\);/)[0], ctx);
  vm.runInContext(script.match(/  \$\("testTelegramNotifyBtn"\)\.addEventListener\("click", testTelegramNotifySettings\);/)[0], ctx);
  const initialLoad = script.match(/loadTelegramNotifySettings\(\)\.catch\(\(error\) => setTelegramNotifyResult\(telegramNotifyMessage\(error\)\)\)/)[0];
  return { ctx, state, elements, calls, load: () => vm.runInContext(initialLoad, ctx) };
}

test('configuration loads without sending a notification or displaying a returned token', async () => {
  const { load, state, elements, calls } = setup({ settings: { botToken: secret } });
  assert.match(page, /id="telegramNotifyToken" type="password" autocomplete="new-password"/);
  assert.match(page, /id="telegramNotifyResult"[^>]*aria-live="polite"/);
  elements.telegramNotifyToken.value = 'stale input';
  await load();
  assert.deepEqual(calls.map(call => call.url), [endpoint]);
  assert.equal(elements.telegramNotifyToken.value, '');
  assert.equal(elements.telegramNotifyTokenStatus.textContent, '已设置');
  assert.equal(elements.telegramNotifyToken.placeholder, '已设置，留空不修改');
  assert.equal(state.telegramNotifySettings.botToken, undefined);
  assert.doesNotMatch(JSON.stringify(state), new RegExp(secret));
  assert.equal(elements.telegramNotifyResult.textContent, '');
});

test('saving a new token writes it once, clears the field, and retains only safe configuration', async () => {
  const { load, elements, calls, state } = setup({ request: (url, config) => config?.method === 'PUT'
    ? { enabled: true, tokenConfigured: true, chatId: '-100999', botToken: secret }
    : { enabled: false, tokenConfigured: false, chatId: '' } });
  await load();
  elements.telegramNotifyEnabled.checked = true;
  elements.telegramNotifyChatId.value = ' -100999 ';
  elements.telegramNotifyToken.value = secret;
  let prevented = false;
  const saving = elements.telegramNotifyForm.listeners.submit({ preventDefault() { prevented = true; } });
  assert.equal(elements.telegramNotifyToken.value, '');
  await saving;
  assert.equal(prevented, true);
  assert.deepEqual(JSON.parse(calls[1].config.body), { enabled: true, chatId: '-100999', botToken: secret });
  assert.equal(calls[1].config.method, 'PUT');
  assert.equal(elements.telegramNotifyResult.textContent, '已保存');
  assert.equal(state.telegramNotifySettings.botToken, undefined);
  assert.equal(elements.telegramNotifyToken.value, '');
});

test('blank token preserves the saved secret and is omitted from the update body', async () => {
  const { load, ctx, elements, calls } = setup();
  await load();
  elements.telegramNotifyToken.value = '  ';
  elements.telegramNotifyEnabled.checked = false;
  await ctx.saveTelegramNotifySettings();
  assert.deepEqual(JSON.parse(calls[1].config.body), { enabled: false, chatId: saved.chatId });
  assert.equal(calls.length, 2);
});

test('only an explicit test click sends a test with saved settings, even while reminders are disabled', async () => {
  const { load, elements, calls } = setup({ settings: { enabled: false } });
  await load();
  assert.equal(calls.length, 1);
  assert.equal(elements.testTelegramNotifyBtn.disabled, false);
  await elements.testTelegramNotifyBtn.listeners.click();
  assert.deepEqual(JSON.parse(JSON.stringify(calls[1])), { url: `${endpoint}/test`, config: { method: 'POST', body: '{}' } });
  assert.equal(elements.telegramNotifyResult.textContent, '测试提醒已发送');
  assert.equal(elements.testTelegramNotifyBtn.textContent, '发送测试提醒');
});

test('unsaved changes to the group, switch, or token prevent a test request', async () => {
  for (const edit of [elements => { elements.telegramNotifyChatId.value = '-100other'; },
    elements => { elements.telegramNotifyEnabled.checked = false; },
    elements => { elements.telegramNotifyToken.value = secret; }]) {
    const { load, elements, calls } = setup();
    await load();
    edit(elements);
    await elements.testTelegramNotifyBtn.listeners.click();
    assert.equal(calls.length, 1);
    assert.equal(elements.telegramNotifyResult.textContent, '请先保存修改，再发送测试提醒');
  }
});

test('saving is mutually exclusive with another save, test, and reload', async () => {
  const pending = deferred();
  const { load, ctx, elements, calls, state } = setup({ request: (url, config) => config?.method === 'PUT' ? pending.promise : saved });
  await load();
  const first = ctx.saveTelegramNotifySettings();
  assert.equal(elements.saveTelegramNotifyBtn.textContent, '保存中…');
  assert.equal(elements.testTelegramNotifyBtn.disabled, true);
  assert.equal(elements.telegramNotifyChatId.disabled, true);
  await Promise.all([ctx.saveTelegramNotifySettings(), ctx.testTelegramNotifySettings(), ctx.loadTelegramNotifySettings()]);
  assert.equal(calls.length, 2);
  pending.resolve(saved);
  await first;
  assert.equal(state.telegramNotifyBusy, '');
  assert.equal(elements.saveTelegramNotifyBtn.disabled, false);
});

test('sending a test prevents double clicks and concurrent configuration writes', async () => {
  const pending = deferred();
  const { load, ctx, elements, calls } = setup({ request: url => url.endsWith('/test') ? pending.promise : saved });
  await load();
  const first = elements.testTelegramNotifyBtn.listeners.click();
  assert.equal(elements.testTelegramNotifyBtn.textContent, '发送中…');
  assert.equal(elements.saveTelegramNotifyBtn.disabled, true);
  assert.equal(elements.telegramNotifyResult.attributes['aria-busy'], 'true');
  await ctx.testTelegramNotifySettings();
  await ctx.saveTelegramNotifySettings();
  assert.equal(calls.length, 2);
  pending.resolve({ success: true });
  await first;
  assert.equal(elements.testTelegramNotifyBtn.disabled, false);
  assert.equal(elements.telegramNotifyResult.attributes['aria-busy'], 'false');
});

test('a forbidden settings load remains local and does not reject other startup work', async () => {
  const { load, state, elements, calls } = setup({ request: () => { throw { code: 'FORBIDDEN', message: '仅超级管理员可操作' }; } });
  let ordersLoaded = false;
  await Promise.all([load(), Promise.resolve().then(() => { ordersLoaded = true; })]);
  assert.equal(ordersLoaded, true);
  assert.equal(state.telegramNotifySettings, null);
  assert.equal(elements.telegramNotifyResult.textContent, '仅超级管理员可操作');
  assert.equal(elements.saveTelegramNotifyBtn.disabled, true);
  assert.equal(elements.testTelegramNotifyBtn.disabled, true);
  assert.equal(calls.length, 1);
});

test('save errors do not leak the entered token and restore controls without sending a test', async () => {
  const { load, ctx, elements, calls } = setup({ request: (url, config) => {
    if (config?.method === 'PUT') throw { code: 'FORBIDDEN', message: `保存失败 ${secret}` };
    return saved;
  }});
  await load();
  elements.telegramNotifyToken.value = secret;
  await ctx.saveTelegramNotifySettings();
  assert.equal(elements.telegramNotifyToken.value, '');
  assert.equal(elements.telegramNotifyResult.textContent, '保存失败 [已隐藏]');
  assert.equal(elements.saveTelegramNotifyBtn.disabled, false);
  assert.equal(calls.length, 2);
});

test('HTTP and business test failures stay failures and render diagnostic text safely', async () => {
  for (const fail of [() => { throw { message: '<b>机器人不在群里</b>' }; }, () => ({ success: false, message: '<b>机器人不在群里</b>' })]) {
    const { load, elements } = setup({ request: url => url.endsWith('/test') ? fail() : saved });
    await load();
    await elements.testTelegramNotifyBtn.listeners.click();
    assert.equal(elements.telegramNotifyResult.textContent, '<b>机器人不在群里</b>');
    assert.equal(elements.telegramNotifyResult.innerHTML, '');
    assert.equal(elements.testTelegramNotifyBtn.disabled, false);
    assert.notEqual(elements.telegramNotifyResult.textContent, '测试提醒已发送');
  }
});

test('ordinary administrators never request settings and cannot submit or test', async () => {
  const { load, ctx, elements, calls } = setup({ superAdministrator: false });
  ctx.renderTelegramNotifyControls();
  await load();
  await ctx.saveTelegramNotifySettings();
  await ctx.testTelegramNotifySettings();
  assert.equal(elements.telegramNotifyPanel.classes.has('hidden'), true);
  assert.equal(elements.saveTelegramNotifyBtn.disabled, true);
  assert.equal(calls.length, 0);
});

test('enabling without required saved or entered credentials cannot save or send', async () => {
  const { load, ctx, elements, calls } = setup({ settings: { enabled: false, tokenConfigured: false, chatId: '' } });
  await load();
  elements.telegramNotifyEnabled.checked = true;
  await ctx.saveTelegramNotifySettings();
  assert.equal(elements.telegramNotifyResult.textContent, '请填写机器人密钥和接收群 ID');
  assert.equal(elements.testTelegramNotifyBtn.disabled, true);
  assert.equal(calls.length, 1);
});

test('malformed configuration cannot enable operations or expose unexpected credentials', async () => {
  const { load, ctx, elements, calls } = setup({ request: () => ({ botToken: secret }) });
  await load();
  await ctx.saveTelegramNotifySettings();
  await ctx.testTelegramNotifySettings();
  assert.equal(elements.telegramNotifyToken.value, '');
  assert.equal(elements.saveTelegramNotifyBtn.disabled, true);
  assert.equal(elements.telegramNotifyResult.textContent, '收款提醒设置读取失败，请刷新页面重试');
  assert.equal(calls.length, 1);
});

test('saved notification errors display only sanitized text; other types are ignored and saving clears the warning', async () => {
  for (const lastError of [`<img src=x onerror=alert(1)> 提醒失败 ${secret}`, { message: secret }, null, 403, '  ']) {
    const { load, ctx, elements, calls } = setup({ settings: { lastError } });
    await load();
    assert.equal(elements.telegramNotifyResult.textContent,
      typeof lastError === 'string' && lastError.trim() ? '<img src=x onerror=alert(1)> 提醒失败 [已隐藏]' : '');
    assert.equal(elements.telegramNotifyResult.innerHTML, '');
    assert.equal(elements.telegramNotifyToken.value, '');
    assert.equal(calls.length, 1);
    await ctx.saveTelegramNotifySettings();
    assert.equal(elements.telegramNotifyResult.textContent, '已保存');
  }
});
