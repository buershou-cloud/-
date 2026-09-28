(function (root) {
  'use strict';

  function apiRoot(fallback, href) {
    const page = new URL(href);
    const directory = page.pathname.slice(0, page.pathname.lastIndexOf('/'));
    let base;
    try {
      base = new URL(fallback || `${directory}/api/v1`, page);
      if (!['http:', 'https:'].includes(base.protocol)) throw new Error('Invalid API URL');
    } catch (_) {
      base = new URL(`${directory}/api/v1`, page);
    }
    base.search = '';
    base.hash = '';
    base.pathname = base.pathname.replace(/\/+$/, '').replace(/\/merchant-api$/, '');
    if (base.pathname === '/api/v1' && directory && base.origin === page.origin) {
      base.pathname = `${directory}/api/v1`;
    }
    return base.href.replace(/\/+$/, '');
  }

  function assetUrl(base, path) {
    return `${base.replace(/\/api\/v1(?:\/merchant-api)?\/?$/, '')}/${path.replace(/^\/+/, '')}`;
  }

  function endpoints(base) {
    const merchantBase = `${base.replace(/\/merchant-api\/?$/, '').replace(/\/+$/, '')}/merchant-api`;
    return [
      ['check', '接入检查', '验证签名、商户和通道配置；不创建订单、不扣款'],
      ['pay', '发起支付', '创建支付订单；订单状态以查询或通知为准'],
      ['query', '查询订单', '提供商户订单号 outTradeNo，核对支付结果'],
      ['cancel', '关闭订单', '关闭尚未支付的订单'],
      ['refund', '申请退款', '指定退款金额及唯一 outRequestNo']
    ].map(([operation, name, description]) => ({ operation, name, description, method: 'POST', url: `${merchantBase}/${operation}` }));
  }

  function availableProducts(merchant, channels) {
    const bound = Array.isArray(merchant.channelIds) ? merchant.channelIds : [];
    if (!bound.length) return [];
    const allowed = (Array.isArray(channels) ? channels : []).filter(channel => channel.enabled === true
      && channel.dailyEnabled === true && bound.includes(channel.id) && gatewayCallbackReady(channel));
    const defaults = {
      ALIPAY: ['ALIPAY_WAP', 'ALIPAY_APP', 'ALIPAY_F2F', 'ALIPAY_PAYMENT_CODE', 'ALIPAY_PREAUTH',
        'ALIPAY_PREAUTH_H5', 'ALIPAY_PAGE', 'ALIPAY_ORDER_CODE', 'ALIPAY_JSAPI'],
      ALIPAY_DIRECT: ['ALIPAY_DIRECT', 'ALIPAY_DIRECT_WAP', 'ALIPAY_DIRECT_APP', 'ALIPAY_DIRECT_F2F',
        'ALIPAY_DIRECT_PAGE', 'ALIPAY_DIRECT_ORDER_CODE', 'ALIPAY_DIRECT_JSAPI'],
      DOUYIN: ['DOUYIN_H5', 'DOUYIN_NATIVE']
    };
    const alipayProducts = [...defaults.ALIPAY, ...defaults.ALIPAY_DIRECT];
    return [...new Set(allowed.flatMap(channel => {
      const supported = ['ALIPAY', 'ALIPAY_DIRECT'].includes(channel.provider)
        ? alipayProducts : defaults[channel.provider] || [];
      return Array.isArray(channel.products) && channel.products.length
        ? channel.products.filter(product => supported.includes(product)) : supported;
    }))].sort();
  }

  function gatewayCallbackReady(channel) {
    try {
      const value = channel.provider === 'DOUYIN' ? channel.douyinNotifyUrl : channel.notifyUrl;
      const url = new URL(value);
      return ['http:', 'https:'].includes(url.protocol) && !!url.hostname && !url.username && !url.password && !url.hash;
    } catch (_) {
      return false;
    }
  }

  function signType(merchant) {
    return merchant.signMode === 'RSA2' ? 'RSA2' : 'MD5';
  }

  function checklist(merchant, base, products) {
    return [
      '商户网站接入清单',
      '协议：POST JSON / UTF-8；服务端签名调用',
      `商户API根地址：${base}/merchant-api`,
      `商户号：${merchant.merchantId}`,
      `已开启签名方式：${merchant.signMode || 'MD5_RSA2'}`,
      ...endpoints(base).map(item => `${item.name}：POST ${item.url}`),
      `当前配置可用产品：${products.length ? products.join('、') : '尚未确认；先运行check或联系平台核对通道'}`,
      `SDK：${assetUrl(base, 'sdk/merchant-client.mjs')}`,
      `运行示例：${assetUrl(base, 'sdk/merchant-example.mjs')}`,
      `完整文档：${assetUrl(base, 'docs/merchant-api.md')}`,
      '先运行 check 验证接入，再在自己的服务器调用 pay。',
      'notifyUrl 填写自己网站的服务器通知地址。验签并校验商户、订单、金额后，幂等处理并返回 HTTP 2xx + success。',
      '支付结果读取 data.status；外层 code=SUCCESS 只表示请求处理成功。',
      '此清单不包含 MD5 密钥或商户私钥。凭据由商户在自己的服务器单独配置。'
    ].join('\n');
  }

  function examples(merchant, product, now = new Date()) {
    const identity = { merchantId: merchant.merchantId, signType: signType(merchant), timestamp: now.toISOString() };
    const signed = (body, suffix) => ({ ...body, ...identity, nonce: `由SDK每次生成唯一随机串_${suffix}`, sign: '由SDK计算，勿直接发送此占位文本' });
    return {
      check: signed({}, 'check'),
      pay: signed({ product: product || '请先选择已配置产品', outTradeNo: '替换为自己网站的唯一订单号', subject: '网站订单',
        totalAmount: '1.00', notifyUrl: 'https://merchant.example/payments/notify', returnUrl: 'https://merchant.example/orders/result' }, 'pay'),
      query: signed({ outTradeNo: '与支付请求相同的商户订单号' }, 'query'),
      cancel: signed({ outTradeNo: '待关闭的商户订单号' }, 'cancel'),
      refund: signed({ outTradeNo: '原商户订单号', refundAmount: '1.00', outRequestNo: '同一笔退款固定使用此唯一请求号', refundReason: '用户申请退款' }, 'refund')
    };
  }

  const api = { apiRoot, assetUrl, endpoints, availableProducts, signType, checklist, examples };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.MerchantIntegration = api;
})(typeof window !== 'undefined' ? window : this);
