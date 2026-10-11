# Market Sentinel（行情哨兵）Android 0.4.0

本项目是一个本地优先的 Android 跨市场行情与策略告警应用。只生成观察信号，不会自动下单，也不需要交易所交易权限。

## 功能

- **预置行情源**：加密货币默认使用 Binance 公共现货 REST 行情与 15 分钟 K 线；美股、A 股、港股及主要指数使用 Yahoo Finance Chart 公共接口。除企业微信 Webhook 外，不要求用户填写数据源 API Key。
- **默认观察列表**：BTCUSDT、ETHUSDT、SOLUSDT、^GSPC、^IXIC、^HSI、AAPL、MSFT、NVDA、600519.SS、399001.SZ、0700.HK。可在行情页编辑，列表保存在本机。
- **技术策略**：15 分钟 EMA9/21/50 趋势、RSI(14)、MACD 柱动能转换、布林带、前 20 根 K 线突破/跌破、成交量放大、ATR 波动风险及涨跌幅阈值。\n- **市场全景**：统计当前成功获取的自选资产数量、涨跌家数、平均涨跌幅、领涨/领跌标的；可按加密货币、股票、上涨、下跌筛选，并按中文名或代码搜索。\n- **仓位风险计算器**：输入示例账户资金、风险比例、计划入场价与止损参考价，估算最大计划亏损、理论数量上限与名义仓位；不连接交易账户、不自动下单。
- **风险参考**：偏多/偏空观察信号可附 ATR 参考止损和目标价。未纳入手续费、滑点、跳空、流动性和个人风险承受能力；不是交易指令。
- **企业微信告警**：支持配置、加密保存、发送测试消息；后台任务对同一标的/规则去重 4 小时，每轮最多推送 5 条。
- **后台监控**：使用 WorkManager，目标间隔 15 分钟；Android 可能延迟后台任务，因此不是逐 tick 实时监控。
- **隐私**：不读取联系人、短信或位置，不收集交易账户密钥；Webhook 只保存在本机加密偏好设置中。

## 数据源限制

- Binance 公共市场数据端点无需 API Key，参考官方文档：<https://github.com/binance/binance-spot-api-docs/blob/master/faqs/market_data_only.md>。
- Yahoo Finance Chart 是非官方接口，覆盖、数据延迟、地区可用性及限流由服务方决定；不保证所有股票/指数都可用，也不等同于交易所授权的实时行情。
- 指标基于最近获取到的 15 分钟 K 线。策略尚未经过目标市场的历史回测或模拟盘验证，可能产生假突破、滞后或重复信号，不保证盈利。

## 构建 APK

项目使用 Kotlin、Jetpack Compose、Room、WorkManager、OkHttp；JDK 17、Android SDK API 35、Gradle 8.9、AGP 8.7.3。

1. 在 Android Studio 打开仓库根目录，等待 Gradle Sync。
2. 或在 GitHub 的 **Actions → Build Market Sentinel APK → Run workflow** 手动触发构建。
3. 构建成功后，在运行详情底部的 Artifacts 下载 `market-sentinel-debug-apk`。
4. 预期 APK 路径：`app/build/outputs/apk/debug/app-debug.apk`。

首次运行需联网。企业微信 Webhook 需你自行填写，这是唯一必须由你提供的配置。当前 APK 需由 GitHub Actions 构建成功后下载；策略只用于辅助观察，不构成投资建议。
