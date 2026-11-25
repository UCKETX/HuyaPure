# 虎伴 (HuyaPure)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84.svg)](app/build.gradle.kts)
[![Xposed API](https://img.shields.io/badge/LSPosed%20%2F%20Xposed-API%2082-orange.svg)](app/src/main/AndroidManifest.xml)
[![Target](https://img.shields.io/badge/Target-com.duowan.kiwi-brightgreen.svg)](app/src/main/res/values/arrays.xml)

**虎伴**（仓库：`UCKETX/HuyaPure`，包名：`com.huya.pure`）是一款轻量级 **LSPosed / Xposed** 模块，用于自动化虎牙每日任务与粉丝牌管理、屏蔽虎牙直播开屏与直播间广告、跳过激励视频广告直接领取虎粮，并彻底关闭虎牙后台及直播间 P2P / MiniPCDN / FileP2P 偷跑上传（不影响正常直播播放与弹幕）。

---

## 主要功能

1. **四页签现代化界面、全自绘弹窗 & 全新暖陶小虎图标**
   - 全新四页签架构（`任务` / `粉丝牌` / `净化` / `关于`），搭配空心→实心动态底部导航图标、丝滑页面与卡片过渡动效、全套自绘底部菜单/时间选择器/模态弹窗及深色/浅色双主题，换装全新暖陶质感小虎应用图标。
2. **每日定时后台自动领虎粮 + 全粉丝牌打卡 + 每日福利领取 + 超粉日智能送免费虎粮（免开虎牙）**
   - 支持设置每日自动执行时间（默认 `08:00`）、独立勾选「粉丝牌自动打卡」「领取每日专属福利」「自动赠送免费虎粮（含超粉虎粮）」「超粉日智能囤粮」、状态栏结果通知与一键手动触发。
   - 无需打开虎牙界面：通过 `ContentProvider.call()` 静默唤醒虎牙主进程复用当前登录态，依次完成：
     - 包裹免费虎粮领取（`GetCommAdPannel` + `HyLotteryAdAwardManager.onAwardCommonAdTask`）；
     - 账号下全部粉丝牌打卡（`liveui.queryBadgeInfoList` + `wupui.setFansSign`）；
     - 每日免费福利自动领取：粉丝团专属每日福利 `10` 个虎粮（`wupui.queryFansGroupTaskInfo` + `wupui.operateFansBox`）、超粉/超级虎粮与粉丝团通用福利（`wupui.queryFGCommonWelfare`）、虎牙每日签到（`OldUserBackSign`）及已完成任务中心宝箱（`GetLiveBoxPage` + `AwardLiveBox`）；
     - **超粉日智能囤粮 + 临期防过期兜底**：实时查询每位主播的 `SuperFansConfig` 超粉日与 4 倍亲密度配置，结合背包逐批次免费虎粮过期时间戳（`lExpireTime`），平时仅送 1 天超粉虎粮与临期批次（临期自动突破单次上限，必要时额外预约过期前 30 分钟「临期抢救闹钟」），其余留到超粉日当天集中送出吃满 4 倍亲密度；
     - **多选目标 + 4 种分配方式**：支持自由多选多位主播（含全选/仅超粉/自定义 UID），支持**平均分配**、**优先补满今日亲密度上限**、**优先送离升级最近**、**按自定义比例分配**；严格使用 `SendGiftPayPloyUtil.PACKAGE_PAY_PLOY` 包裹免费通道（`iIsPay=0`），绝不消耗金豆与虎牙币。
   - `任务` 页签内置三列实时统计（今日领虎粮 / 粉丝牌打卡 / 免费虎粮，支持超粉虎粮与临期提醒）、当日超粉日提醒横幅与结构化执行状态面板。
3. **「粉丝牌」独立页签与手动送虎粮**
   - 完整还原虎牙原生与主播**定制粉丝牌**样式，支持守护徽章（`初爱守护` / `超级守护` / `至尊守护`）、纯图标超粉 / Plus 标识、超粉日日期/倒计时标签与熄灭态，直观展示等级、总经验、升级进度条、今日亲密度与打卡状态。
   - **点击主播快捷操作**：轻点任意粉丝牌即可查看详细数据、复制 UID，或直接手动向该主播赠送免费虎粮 / 超粉虎粮。
4. **「净化」页签独立开关与差值感知悬浮重启条**
   - 基于 LSPosed `XSharedPreferences` 按需独立开关每一项去广告、防 P2P 及调试日志能力，支持一键恢复默认。
   - 修改净化开关且存在净变动时自动在底部浮出重启提示条（还原后自动收起），拥有 Root 权限时静默重启虎牙进程即刻生效，无 Root 时自动跳转系统应用详情页兜底。
5. **「关于」页签诊断、缓存清理与应用内更新**
   - 集中展示模块激活状态与运行诊断信息，支持一键清除粉丝牌本地缓存。
   - 支持自动/手动检测 `UCKETX/HuyaPure` GitHub Releases 新版本，查看更新日志、应用内带进度条下载 APK 并直接调起系统安装器更新。
6. **激励视频跳广告领虎粮**
   - 支持包裹页「看广告赚虎粮」、膨胀弹窗「看广告立即膨胀」、宝箱及任务中心激励视频广告。
   - 覆盖穿山甲（CSJ / Pangle）、优量汇（AMS / GDT）与虎牙自建（KA）三大广告渠道，跳过视频播放并自动完成奖励校验向服务端领奖。
7. **屏蔽开屏与直播间广告（不影响领虎粮）**
   - 拦截冷启动开屏、热启动开屏、延迟开屏、后台开屏资源预拉取及开屏联动横幅，启动直达首页。
   - 屏蔽角标浮窗、大浮窗、视频浮窗、推荐浮窗、超级动画浮窗、底部/顶部横幅、商业小风车/插件、主播推广、看播赏金 RTB 及商业弹幕广告。
8. **彻底关闭后台与直播间 P2P / MiniPCDN / FileP2P 上传**
   - 关闭 MiniPCDN 边缘节点与 HTTP/3 QUIC / WebRTC 上传服务（实测可消除后台约 2 MB/s 偷跑上传）。
   - 关闭 FileP2P 文件上传与后台 HeatList 热点预下载做种，消除服务端 `ignoreUploadDisable=2` 绕过。
   - 强制直播间拉流走纯 HTTP-FLV CDN，关闭直播 P2P / XP2P / IPv6 打洞、局域网 mDNS 发现、下载 PCDN 改写与弹幕 P2P 中继（保留正常 WebSocket 弹幕）。
9. **适配热更新**
   - 自动监听虎牙 Tinker / Qigsaw 热更新切换 `SplitDelegateClassloader`，避免热补丁激活后模块失效。

---

## 兼容性说明

| 项目 | 说明 |
| :--- | :--- |
| 系统要求 | Android 8.0+（API 26+），已安装 LSPosed（推荐 Zygisk / JingMatrix LSPosed） |
| 目标应用 | 虎牙直播国内正式版（`com.duowan.kiwi`） |
| 已验证版本 | `13.5.62`（含 Tinker 热更新补丁） |
| 其他版本 | 模块未写死版本号，且核心 Hook 点以未混淆类/方法为主，相近 `13.x` 版本通常可直接兼容；不支持极速版、TV/HD 版、国际版等非 `com.duowan.kiwi` 包名客户端 |

---

## 安装与使用

1. 下载或自行编译生成 APK 并安装到手机。
2. 打开 **LSPosed Manager → 模块 → 虎伴（HuyaPure / `com.huya.pure`）**：
   - 开启模块开关；
   - 确认勾选作用域 **虎牙直播 (`com.duowan.kiwi`)**。
3. **强行停止**虎牙直播并重新启动即可生效（亦可在「净化」页签点击一键重启虎牙）。
4. 打开**虎伴** App 可在「关于」页签查看激活状态与运行诊断；在 LSPosed 日志中搜索 `HuyaPure` 可确认各组件挂载与拦截记录。

---

## 实现原理

模块核心逻辑位于 [`HookEntry.java`](app/src/main/java/com/huya/pure/HookEntry.java)，主要包含以下模块：

### 1. 激励视频跳过并领取虎粮
- **穿山甲（CSJ / Pangle）**：Hook `HyAdManagerInner.loadPangleRewardAd` / `loadPangleRewardShopMallAd`，阻止拉起广告页并依次回调 `IPangleRewardAdCallBack` 的 `onShowRewardVideo` → `onAdPlay` → `onVideoRewardVerify(true)` → `onAdClose`。
- **优量汇（AMS / GDT）**：Hook `HyAdManagerInner.loadAmsRewardAd`，保留元数据拉取与真实 `passThroughData`，在 `IAmsAdRewardADListener.onADLoad` 中替换展示回调 `Function0`，跳过 `TangramRewardAD.showAD()` 并回调完整发奖序列。
- **虎牙自建（KA）**：Hook `VideoAdPlayFragmentDialog.showAd`，隐藏视频容器并自动触发 `setPlayFinish(true)` → `onAdPlayComplete()` → `dismissAd()`。

### 2. 屏蔽开屏与直播间广告
- **开屏广告**：Hook `AdRouter`、`SplashShowStrategy`、`HyAdSplashManager`、`AdSplashModule`，并以 `AdSplashFragment` 跳转首页作为兜底。
- **直播间广告**：开启 `HyAdModule.isNeedShieldAdInLivingRoom` 全局开关，并拦截 `RevenueModule`、`PresenterAdModule`、`AdxModule`、`RewardAdModule`、`AdBannerModule` 与 `LiveRoomFloatSuperAd`（与 `HyVideoAdModule` / `HyLotteryAdAwardManager` 严格隔离，不影响领虎粮）。

### 3. 关闭后台及直播间 P2P / MiniPCDN / FileP2P 上传
1. **热更新 ClassLoader 适配**：监听 `Application.attach`、`Application.attachBaseContext` 与 `Instrumentation.callApplicationOnCreate`，在切换为 `SplitDelegateClassloader` 后自动补装全部 Hook。
2. **`SignalClient` 原生动态配置清洗与 RPC 拦截**：
   - Hook `SignalClient` 纯 Java 桥接（`access$800` / `access$900`、`SignalClient$5`），并在生命周期关键节点主动通过 `nativeOnParamsConfigResult` 注入清洗后的动态配置（保留 `paramsprefs-hysdk` 中正常音视频参数，覆写 110+ 项 P2P / MiniPCDN / FileP2P / HeatList / IPv6 打洞 / mDNS 开关）。
   - 在 `SignalClient.request` 与 `access$000` 中拦截 `/mediaui/getMediaConfig`（防止服务端未清洗配置覆盖本地开关）以及 FileP2P / HeatList / STUN / SeedProxy / MiniPCDN / PCDN 调度 RPC，放行正常登录与直播拉流鉴权。
3. **强制直播间走纯 HTTP-FLV CDN**：
   - Hook `HYStreamConfig`、`HYLiveStreamConfig`、`HYLivePlayerImp`、`HyStreamMessageGenerator`、`ChannelSession`、`CDNLine`、`MultiLineConfig`、`ABSLine`、`P2PMonitor`、`CloudSdkDynamicConfigModule`，将 `P2P_STREAM` / `TX_XP2P_STREAM` 强制转为 `FLV_STREAM`，强制 `isForceFlv=true`、`isSupportP2P=0`，清空 `HyStreamMessage` 的 P2P 字段并关闭原生 `ENABLE_UPLOAD` 与 `CCK_ENABLE_P2P_MODE`。
4. **下载与弹幕 P2P 屏蔽**：Hook `KiwiDownloadComponent` 禁止 PCDN 改写；Hook `HySignalWrapper` 与 `HalImpl` 禁用弹幕 P2P 中继（保留正常 WebSocket 弹幕）。

---

## 从源码编译

### 环境要求
- JDK 17+
- Android SDK（项目默认 `compileSdk = 37`、`buildToolsVersion = 36.0.0`，可在 [`app/build.gradle.kts`](app/build.gradle.kts) 按需调整）

### 编译与安装
```bash
# 编译 Debug APK
./gradlew assembleDebug

# 安装到已连接的 Android 设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 更新日志

详见 [CHANGELOG.md](CHANGELOG.md)。

---

## 免责声明

- 本项目仅供 Android 逆向技术学习、网络流量研究与个人体验优化使用。
- 请勿将本项目用于任何商业用途、违法违规场景或恶意刷量行为，使用者需自行承担因使用本项目产生的一切风险与后果。

## 开源协议

本项目基于 [GNU General Public License v3.0 (GPL-3.0)](LICENSE) 开源。
