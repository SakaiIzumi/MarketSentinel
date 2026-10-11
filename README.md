# Market Sentinel（行情哨兵）Android MVP

一个以本地运行优先的 Android 行情监控原型。当前包含：

- Kotlin + Jetpack Compose 原生 Android UI
- Binance 公共 REST 现货 24 小时行情查询（例如 BTCUSDT、ETHUSDT）
- 基础价格波动告警规则
- Room 本地数据库基础结构（自选列表、告警历史）
- WorkManager 周期任务示例（Android 会延迟执行，不是实时调度）
- 企业微信群机器人 Webhook 配置、加密偏好保存与测试消息
- 不包含自动下单，不要求交易所交易权限

## 构建 APK

环境要求：Android Studio（建议支持 AGP 8.7.3 的版本）、JDK 17、Android SDK Platform 35。

1. 在 Android Studio 中打开本目录。
2. 等待 Gradle Sync 完成。
3. 连接安卓设备并开启 USB 调试，或创建模拟器。
4. 运行 `app`，或在 Android Studio 的 Gradle 工具窗口执行 `app > Tasks > build > assembleDebug`。
5. 本压缩包未包含 Gradle Wrapper；如需命令行构建，请先在本机安装 Gradle 并运行 `gradle wrapper`，再执行 `./gradlew assembleDebug`（Windows 使用 `gradlew.bat assembleDebug`）。

APK 预期输出位置：`app/build/outputs/apk/debug/app-debug.apk`。

## 重要限制

- 本项目交付的是源码原型，尚未在此环境执行 Android SDK 编译或真机测试，因此不能声称 APK 已编译验证。
- 当前真实行情适配器仅覆盖 Binance 公共加密货币现货行情。美股、A 股、港股需要选择合法且覆盖对应市场的数据供应商后实现适配器。
- 当前策略只是简单波动告警，并非完整的 EMA/RSI/MACD/ATR 多周期量化系统；默认阈值需通过历史回测和模拟交易验证。
- WorkManager 是面向可延迟后台任务的机制，不保证每分钟甚至每个行情 tick 执行。需要更可靠的持续监控时，应由服务器运行采集任务。
- 企业微信 Webhook 是高敏感凭证。此原型使用 AndroidX EncryptedSharedPreferences；请勿将其写入源代码、日志、截图或版本库。生产版本还应验证备份策略、应用完整性与密钥生命周期。
- 公开 API 的可用性可能因地区、网络、供应商策略或限流而变化。

## 推荐下一步

1. 增加股票行情供应商配置页（API Key 加密存储）。
2. 增加 WebSocket 实时行情、K 线历史缓存与数据过期检测。
3. 实现完整指标库：EMA、RSI、MACD、ATR、布林带、多周期趋势和突破检测。
4. 使用 Room DAO 持久化策略和告警去重状态。
5. 增加通知权限申请、前台服务选项、电池优化说明与重启恢复测试。
6. 完成单元测试、历史回测、模拟交易和正式 APK 签名。
