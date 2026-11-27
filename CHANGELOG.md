# Changelog

## v1.6.0
- **新增「虎伴 Server」纯 Go 云端网页版（无需 Root / 跨平台自托管）**：
  - 零外部依赖纯 Go 实现完整虎牙 JCE / UniPacket v3 / WUP 协议，单静态二进制内嵌响应式 Web 管理面板，支持 **Linux / macOS / Windows（`amd64` & `arm64`）** 与 **Docker（~15 MB 双架构镜像）** 部署。
  - **5 种登录方式 & 多账号独立托管**：支持手机短信（推荐，支持长期自动续期不掉线）、微信扫码（含虎牙官方 SafeAuth 安全验证）、QQ 扫码、虎牙 App 扫码与网页 Cookie 导入，且 5 种登录方式均完整支持看广告领虎粮、打卡、每日福利与自动/手动送粮。
  - **完整自动化任务与智能囤粮**：每日定时看广告领虎粮（含冷却自动预约补领）、全粉丝牌打卡、每日粉丝团 10 个虎粮 / 超粉虎粮 / 每日签到领取、超粉日 4 倍亲密度识别与临期 36h/30min 防过期抢救、多目标 4 种分配策略与单主播手动送粮。
  - **3× 高清真实粉丝牌与三级原生守护盾徽**：直连虎牙官方资源动态渲染普通牌、超粉（`V` / `V年` / `V+`）、主播定制粉丝牌、熄灭态与 `初爱守护` / `超级守护` / `至尊守护` 原生盾徽。
  - **桌面 & 手机端深度适配 + 多渠道结构化推送**：支持浅色/深色双主题、手机底部导航栏/顶栏账号快切/底部抽屉弹窗，以及 **Bark（iOS）/ Telegram / Server酱 / 钉钉 / 飞书 / 自定义 Webhook** 分项清单式通知推送。
- **修复「超粉虎粮」自动领取与赠送（Android & Server 双端）**：
  - 修复 `wupui.getPackageGift` 未传入超粉主播 PID 导致 `超粉虎粮（itemType=20699）` 不返回的问题，并在 Android 端解耦超粉虎粮发送开关与普通虎粮开关、增加备选超粉主播回退，确保每日超粉虎粮稳定送出。
- **CI/CD 全平台自动编译与联合发布**：
  - GitHub Actions 自动编译并联合发布正式签名 Android Release APK、三端 6 架构 `HuyaPure-server` 压缩包、`docker-compose.yml`（配套 GHCR `amd64/arm64` 镜像）与 `SHA256SUMS.txt`。

## v1.5.0
- **品牌升级为「虎伴 (HuyaPure)」与全新应用图标**：
  - 应用显示名称升级为**虎伴**（保持包名 `com.huya.pure` 与仓库 `UCKETX/HuyaPure` 兼容），换装全新暖陶质感小虎桌面图标。
- **四页签现代化 UI、全自绘弹窗与动效重构**：
  - 全面重构为 `任务` / `粉丝牌` / `净化` / `关于` 四页签底部导航架构，支持空心→实心动态切换图标、丝滑页面/卡片过渡动效与浅色/深色双主题。
  - 新增自绘组件库 `HubanDialogs`，全量替换原生 `AlertDialog` 与 `TimePickerDialog`，提供自绘底部时间选择器、单选/多选底部菜单、比例权重步进器、圆角输入弹窗与自绘进度条弹窗。
  - 优化任务页顶部三列实时统计（今日领虎粮 / 粉丝牌打卡 / 免费虎粮，支持超粉虎粮与 24h 临期提示，虎粮单位统一为「个」）与结构化执行状态面板。
- **每日定时后台自动领虎粮 + 全粉丝牌打卡 + 每日福利领取 + 智能自动送免费虎粮（免开虎牙）**：
  - 支持每天定时自动唤醒虎牙领取当日全部包裹「看广告赚虎粮」、给账号下**所有粉丝牌**打卡、自动领取**粉丝团专属每日福利（10 个虎粮）**、**超粉/超级虎粮与粉丝团通用福利**、**虎牙每日签到**及已完成任务中心宝箱。
  - **超粉日 4 倍亲密度智能囤粮 + 临期防过期双兜底**：
    - 通过 `wupui.getLiveRoomProfileForAudience` 实时拉取每位主播的 `SuperFansConfig` 超粉日配置，自动识别超粉日日期与 4 倍亲密度倍率，支持任务页横幅、粉丝牌标签与状态栏通知提醒；
    - 结合 `GetPackageGiftRsp` 每批次免费虎粮精确过期时间戳，平时仅送出 1 天有效期超粉虎粮与 36 小时内临期批次，将长效免费虎粮自动囤至超粉日集中送出吃满 4 倍亲密度；
    - 临期虎粮自动突破单次上限避免截断，并新增「临期抢救闹钟」（`ACTION_EXPIRY_RESCUE_ALARM`），若有虎粮早于下次定时到期则自动在过期前 30 分钟额外唤醒送出。
  - **多选赠送目标 + 4 种分配方式**：
    - 支持任意多选粉丝牌主播（含 `全选 / 仅超粉 / 清空 / + 自定义 UID` 快捷操作），并提供**平均分配**、**优先补满今日亲密度上限**、**优先送离升级最近的主播**、**按自定义比例分配** 4 种分配策略；
    - 严格锁定 `SendGiftPayPloyUtil.PACKAGE_PAY_PLOY` 包裹免费通道（仅 `iIsPay=0`），绝不消耗金豆与虎牙币。
- **「粉丝牌」独立页签（原生/定制牌 · 守护 · 超粉日 · 手动送粮）**：
  - 完整呈现虎牙原生与主播**定制粉丝牌**、守护徽章（`初爱守护` / `超级守护` / `至尊守护`）、纯图标超粉 / Plus 标识、超粉日倒计时标签与熄灭态。
  - 点击任意主播可查看详情、复制 UID，并支持直接手动向该主播赠送**免费虎粮**或**超粉虎粮**（`huya_pure_manual_send_v1`）。
- **「净化」差值感知悬浮重启条与「关于」诊断/缓存管理**：
  - `净化` 页签修改开关且存在净变动时在底部弹出悬浮重启卡片（关上再开回原状态自动收起），Root 环境一键静默强停重启虎牙，无 Root 自动跳转应用详情页。
  - `关于` 页签集成模块激活状态、环境信息一键复制、粉丝牌与定制牌缓存清理、GitHub 仓库/反馈入口。
- **检查更新与应用内更新**：
  - 新增 `AppUpdater` 与内置零依赖 `UpdateFileProvider`，支持从 GitHub Releases 自动/手动检查新版本、查看更新说明、应用内带自绘进度条下载 APK 并直接调起系统安装器（含未知来源安装权限引导与浏览器兜底）。

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
