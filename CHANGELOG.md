# Changelog

## v1.4.0
- **独立功能开关界面**：在模块 App（`MainActivity`）中新增 6 项可视化开关（支持浅色/深色模式与一键恢复默认），通过 LSPosed `XSharedPreferences`（`xposedsharedprefs=true`）实时同步给虎牙进程：
  - 激励视频跳广告领虎粮
  - 屏蔽开屏广告
  - 屏蔽直播间广告
  - 关闭后台 P2P / MiniPCDN 上传
  - 强制直播间走纯 HTTP-FLV CDN（禁直播 P2P）
  - 详细调试日志

## v1.3.0
- **适配 Tinker / Qigsaw 热更新**：新增 `Application.attach`、`Application.attachBaseContext` 与 `Instrumentation.callApplicationOnCreate` 监听，在虎牙加载热补丁切换为 `SplitDelegateClassloader` 后自动补装全部 Hook。
- **彻底关闭后台与直播间 P2P / MiniPCDN / FileP2P 上传**：
  - Hook `SignalClient` 纯 Java 桥接与生命周期，保留正常音视频参数的同时覆写 110+ 项 P2P / MiniPCDN（MPS / H3Server / WebRTCServer）/ FileP2P / HeatList / IPv6 打洞 / 局域网 mDNS 开关。
  - 在 `SignalClient.request` 与 `access$000` 中拦截 `/mediaui/getMediaConfig` 及 FileP2P / HeatList / STUN / SeedProxy / MiniPCDN / PCDN 调度 RPC，防止服务端下发配置覆盖本地关闭开关。
  - 新增 `HYStreamConfig`、`HYLiveStreamConfig`、`HYLivePlayerImp`、`HyStreamMessageGenerator`、`MultiLineConfig` 拦截，将直播间 `P2P_STREAM` / `TX_XP2P_STREAM` 强制转为纯 HTTP-FLV CDN（`isForceFlv=true`、`isSupportP2P=0`）。
  - 真机抓包验证：首页、直播间观播及退后台场景出站 UDP 上传均降至 `0.00 KB`，直播 HTTP-FLV 播放与 WebSocket 弹幕正常。

## v1.2.0
- 新增基础 P2P 上传与弹幕 P2P 中继屏蔽（`ChannelSession`、`CDNLine`、`ABSLine`、`P2PMonitor`、`CloudSdkDynamicConfigModule`、`KiwiDownloadComponent`、`HySignalWrapper`、`HalImpl`）。

## v1.1.0
- 新增开屏广告屏蔽（冷启动、热启动、延迟开屏、开屏横幅）与直播间广告屏蔽（角标浮窗、超级浮窗、横幅、小风车/插件、主播推广、看播赏金 RTB、商业弹幕），同时保持激励广告领虎粮可用。

## v1.0.0
- 初始版本：支持跳过穿山甲（CSJ / Pangle）、优量汇（AMS / GDT）与虎牙自建（KA）激励视频广告，并自动完成奖励校验领取虎粮。
