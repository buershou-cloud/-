Page({
  data: {
    baseUrl: "",
    channelId: "",
    miniAppId: "",
    product: "ALIPAY_JSAPI",
    amount: "1",
    subject: "扫码收银台支付",
    loading: false,
    message: "",
    messageTone: ""
  },

  onLoad(query) {
    this.setData({
      baseUrl: decodeURIComponent(query.baseUrl || ""),
      channelId: query.channelId || "",
      miniAppId: query.miniAppId || "",
      product: query.product || "ALIPAY_JSAPI"
    });
  },

  onAmountInput(event) {
    this.setData({ amount: event.detail.value });
  },

  onSubjectInput(event) {
    this.setData({ subject: event.detail.value });
  },

  pay() {
    if (!this.data.baseUrl || !this.data.channelId) {
      this.setMessage("收银台参数缺失，请重新扫码。", "bad");
      return;
    }
    if (!Number(this.data.amount)) {
      this.setMessage("请输入有效金额。", "bad");
      return;
    }
    this.setData({ loading: true, message: "正在获取支付宝授权...", messageTone: "" });
    my.getAuthCode({
      scopes: ["auth_base"],
      success: (authResult) => this.createOrder(authResult.authCode || authResult.auth_code),
      fail: (error) => {
        this.setData({ loading: false });
        this.setMessage(`授权失败: ${this.errorText(error)}`, "bad");
      }
    });
  },

  createOrder(authCode) {
    if (!authCode) {
      this.setData({ loading: false });
      this.setMessage("支付宝未返回 authCode。", "bad");
      return;
    }
    my.request({
      url: `${this.data.baseUrl}/api/v1/payments/pay`,
      method: "POST",
      headers: { "Content-Type": "application/json" },
      data: {
        product: this.data.product,
        outTradeNo: this.orderNo(),
        subject: this.data.subject,
        totalAmount: this.data.amount,
        authCode,
        timeoutExpress: "10m",
        channelIds: [this.data.channelId],
        notifyUrl: `${this.data.baseUrl}/api/v1/alipay/notify/${encodeURIComponent(this.data.channelId)}`,
        extra: this.orderExtra()
      },
      success: (response) => {
        const data = response.data || {};
        if (data.status === "FAILED") {
          this.setData({ loading: false });
          this.setMessage(`下单失败: ${data.message || data.code || JSON.stringify(data)}`, "bad");
          return;
        }
        this.tradePay(data.tradeNo || data.trade_no || this.responseTradeNo(data));
      },
      fail: (error) => {
        this.setData({ loading: false });
        this.setMessage(`下单请求失败: ${this.errorText(error)}`, "bad");
      }
    });
  },

  tradePay(tradeNo) {
    if (!tradeNo) {
      this.setData({ loading: false });
      this.setMessage("后端未返回 tradeNo，无法调起 JSAPI 支付。", "bad");
      return;
    }
    this.setMessage("正在调起支付宝付款...", "");
    my.tradePay({
      tradeNO: tradeNo,
      success: (result) => {
        this.setData({ loading: false });
        const code = String(result.resultCode || "");
        if (code === "9000") {
          this.setMessage("支付成功。", "ok");
          return;
        }
        this.setMessage(`支付未完成: ${this.errorText(result)}`, "bad");
      },
      fail: (error) => {
        this.setData({ loading: false });
        this.setMessage(`支付失败: ${this.errorText(error)}`, "bad");
      }
    });
  },

  responseTradeNo(data) {
    const raw = data.raw || {};
    const key = Object.keys(raw).find((item) => item.endsWith("_response"));
    return key && raw[key] ? raw[key].trade_no : "";
  },

  orderNo() {
    return `MP${Date.now()}${Math.floor(Math.random() * 1000)}`;
  },

  orderExtra() {
    const extra = { cashier: true, source: "alipay-miniapp" };
    if (this.data.miniAppId) {
      extra.op_app_id = this.data.miniAppId;
    }
    return extra;
  },

  setMessage(message, tone) {
    this.setData({ message, messageTone: tone || "" });
  },

  errorText(error) {
    if (!error) return "无错误信息";
    return error.errorMessage || error.errorMessageKey || error.error || error.message || JSON.stringify(error);
  }
});
