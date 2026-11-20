# 「轻量打开」可行性报告

> 目标：在 WheelPilot（轻窗）里实现截图中的 7 个场景开关 —— 通知中心、通知横幅、控制中心、应用跳转、登录授权、应用分享、复制链接，使这些场景下的应用启动以自由浮窗（小窗）形态出现，而不是全屏。
>
> 代码基线：`versionName 3.9.1` / `versionCode 69`（`app/build.gradle.kts:45-46`），APK 3.89 MB。
> 本文只做可行性判定与方案设计，不改代码。

---

## 0. 结论先行

| 判定 | 内容 |
|---|---|
| **能做** | 7 项里 **6 项可以实现**，1 项（复制链接）在未 root 的 AOSP 环境**无法自动完成**，只能降级为「复制后手动一步打开」 |
| **但都不是"拦截"** | 这 6 项统一是**事后转窗**：目标应用先以全屏出现约 200–400 ms，随后被掰成浮窗。原因是这些启动动作由发起方（SystemUI / 源应用 / 分享面板）直接投递 Intent 或 PendingIntent，第三方没有 hook 点 |
| **判据精度分三档** | 通知中心 / 通知横幅：**高**（有 SystemUI 窗口信号 + 通知包名）<br>控制中心：**中**（只能靠 SystemUI 窗口 + 时间窗推断）<br>应用跳转 / 登录授权 / 应用分享：**低**（系统层面三者不可区分，只能共用一条"无触摸前台切换"判据） |
| **建议收敛** | 把 7 个开关收敛为 **4 个功能开关**：通知中心、通知横幅、控制中心、应用跳转（同时覆盖登录授权与应用分享）。若必须保留截图的 7 行视觉，后三项可共用同一判据，但 UI 需注明"同一判据、三处生效" |
| **最大不确定性** | 「把已经在前台的全屏 task 改成浮窗」这条命令链，需要在 **Pixel 3（AOSP）** 与 **小米 11 Pro（HyperOS）** 上各验证一次。这是整个方案的技术单点，其余部分都是常规工作量 |
| **底座已有** | 小窗启动通道、窗口矩形计算、task 定位与 resize、无障碍窗口事件、通知监听，**项目里都已存在**（第 2 节），不需要重写启动层 |

一句话：**页面和开关是好做的，判定是能做的，把全屏窗口变成浮窗这一步是要靠真机验证的。**

---

## 1. 需求还原

截图是"轻量打开"设置页，副标题「在以下场景使用自由浮窗打开」。7 个开关对应的真实技术动作是同一个：

> 检测到「某应用因某个场景被拉起」→ 不要它全屏 → 换成自由浮窗。

差异只在**如何识别"这次启动属于哪个场景"**，以及**用户希望哪类场景生效**。所以这不是 7 个功能，而是 **1 个转窗引擎 + 7 个场景判据**。方案的成败在判据精度，不在开关数量。

---

## 2. 现状盘点：项目已有的资产

| 资产 | 位置 | 能支撑什么 |
|---|---|---|
| 带窗口矩形的小窗启动 | `AppLauncher.tryWindowLaunch()`（`ActivityOptions.setLaunchBounds` + `windowingMode` 写入 bundle） | 浮窗形态与几何，已真机验证过（含荣耀 MagicOS 尺寸钳制问题） |
| 窗口模式候选表 | `core/WindowModes.kt`（FREEFORM=5 / VENDOR=100 / FULLSCREEN=0，荣耀与 ColorOS 顺序不同） | 跨 ROM 的模式选择 |
| 特权 shell 通道 | `AppLauncher.exec()` → `priv-kit Privilege.startCommand`（uid 2000） | 执行 `am start` / `am task resize` 等命令 |
| task 定位 | `core/StackListParser.findTaskId()` 解析 `am stack list` | 找到目标应用的 task id |
| task 强制 resize | `AppLauncher.resizeTaskToBounds()`（`am task resizeable` + `am task resize`） | 转窗后的几何修正，可复用为转窗兜底路径 |
| 自由窗口开关预置 | `AppLauncher.ensureFreeformEnabled()`（`enable_freeform_support` / `force_resizable_activities`） | 让 freeform 在部分 ROM 上可用 |
| 无障碍窗口事件 | `PilotService.onAccessibilityEvent()`（`accessibility_service_config.xml` 订阅 `typeWindowStateChanged`，`notificationTimeout=100`） | **实时**拿到前台应用包名，延迟 50–150 ms，是全部判据的基础 |
| 通知监听 | `PilotNotificationListener`（`onNotificationPosted`，已存 `contentIntent`、标题） | 通知场景的包名与时间锚点 |
| 前台应用兜底检测 | `ForegroundCheck.currentForeground()`（UsageStats） | 无障碍事件缺失时的二次确认 |
| 场景暂停状态机 | `PilotService.updateScenePause()` / `applyScenePause()` | 可复用的"场景 → 生效/失效"模式 |
| 配置与备份 | `Prefs` + `core/BackupCodec`（版本化行格式） | 新开关自动进入备份，无需另写序列化 |
| 单元测试底座 | 7 个测试类 41 例，CI 已跑 `testDebugUnitTest` | 判据纯函数可直接进 CI |

### 缺口（需要新建）

1. **触摸信号缺失**：`accessibilityEventTypes` 目前只有 `typeWindowStateChanged`，判断不了"这次前台切换前用户有没有摸屏幕"。这是区分"用户点图标"和"程序拉起"的关键，需要加 `typeTouchInteractionStart|typeTouchInteractionEnd`。
2. **没有"已在前台的 task 转浮窗"函数**：现有 `AppLauncher.launch()` 只做冷启动（`getLaunchIntentForPackage` + 新任务），对"已经在全屏跑着的应用"没有处理路径。
3. **没有场景状态机**：现在只有"呼出前查黑名单"（`showWheel` 前 `ForegroundCheck`），没有"事件 → 判定 → 转窗"的流水线。
4. **没有对应设置页与配置键**：`Prefs` 里没有任何 `light_open` 相关键。

---

## 3. 逐场景可行性

先给判定，再逐个说明为什么。

| # | 场景 | 可用的系统信号 | 能真正拦截？ | 实现路径 | 需要权限 | 成功率预估 | 成本 |
|---|---|---|---|---|---|---|---|
| 1 | 通知中心 | `com.android.systemui` 的 shade 窗口事件 + 最近通知包名 + 前台变化 | ❌ 不能 | 事后转窗 | 无障碍 + 通知使用权 | 90–95% | 中 |
| 2 | 通知横幅 | heads-up 窗口事件（无 shade）+ 通知包名 + 前台变化 | ❌ 不能 | 事后转窗 | 无障碍 + 通知使用权 | 85–90% | 中 |
| 3 | 控制中心 | QS 面板窗口事件 + 前台变化 | ❌ 不能 | 事后转窗（启发式） | 无障碍 | 70–85% | 中高 |
| 4 | 应用跳转 | 前台 A→B 切换 + 切换前无触摸 + B 非桌面 | ❌ 不能 | 事后转窗 | 无障碍 + shell | 85–90% | 中 |
| 5 | 登录授权 | 同 4（无法与 4 区分） | ❌ 不能 | 与 4 共用判据 | 同 4 | 同 4 | 低（复用） |
| 6 | 应用分享 | 同 4；另可注册 `ACTION_SEND` 接收器做"分享到轻窗" | ❌ 不能拦截分享目标启动 | 共用 4 的判据；可选加显式分享入口 | 无障碍 + shell | 80–85% | 中 |
| 7 | 复制链接 | 剪贴板变更回调可收到，但 **Android 10+ 后台读取内容返回 null** | ❌ 不能（读不到内容） | 降级：感知到复制 + 用户手动一步读取 | 无（但读不到） | 自动 < 20% | 低 |

### 3.1 通知中心（可做，精度高）

**能拿到什么**：`PilotNotificationListener.onNotificationPosted()` 给出通知所属包名与时间；无障碍能收到 `typeWindowStateChanged`，`NotificationShade`/`StatusBar` 展开时会出现 `com.android.systemui` 的窗口事件；随后目标应用 RESUMED。

**拿不到什么**：用户到底点了哪条通知。`Notification.contentIntent` 是 `PendingIntent`，没有公开 API 读出其中的目标 `Intent`；`onNotificationRemoved(reason=REASON_CLICK)` 在部分 ROM 上不回调，且点击后通知不一定被移除，不能作为唯一信号。

**判据**：`shade 展开` → 3 s 内 `收到/存在该包名通知` → `前台切到该包名` → `切换前 800 ms 内无触摸事件` → 转窗。四条同时成立才动手，避免用户从通知栏点"设置"这类系统入口时被误转。

**风险**：国产 ROM 的通知栏窗口类名不统一（MIUI 是 `com.android.systemui.recents`/`ShadeActivity` 之类），需要用"包名 = com.android.systemui 且 窗口类型包含 shade/statusbar/qs"这类宽松匹配，并在真机上把实际类名记进白名单常量。

### 3.2 通知横幅（可做，精度中高）

heads-up 不展开 shade，判据缺少"shade 展开"这一条，改为"横幅出现（`onNotificationPosted` 且 `notification.headsUpContentView != null` 或 ranking 里 `isAmbient`）+ 无触摸前台切换"。灵敏度更高、误判也略高，所以成功率给 85–90%。

### 3.3 控制中心（可做，精度中）

**没有信号**：QS 磁贴点击后往往直接 `startActivity`，甚至只是切换系统开关（蓝牙、手电筒）根本不启动应用。

**唯一可行的判据**：QS 面板展开（SystemUI 窗口事件）→ 2 s 内前台切到某个非桌面应用 → 转窗。这会误伤"展开控制中心后点了个通知/点了设置"，所以：默认只对**用户白名单内的应用**生效（默认 = 轮盘里已配置的应用），且排除系统设置、桌面、本应用。

**成本高在**：各 ROM 的 QS 窗口类名差异大，需要机型适配表。

### 3.4 / 3.5 / 3.6 应用跳转 / 登录授权 / 应用分享（可做，但三者不可区分）

**为什么不可区分**：三者最终都是"源应用通过 `startActivity` 或 `PendingIntent` 拉起目标应用"。第三方没有任何 API 能读到"这次启动是分享、还是 OAuth 回调、还是普通深链"。要区分只能靠包名启发式（例如"源是浏览器 + 目标是刚呼出过浏览器的应用"猜授权），准确率不足以支撑三个独立开关。

**判据（三者共用）**：前台 `A → B` 切换，且
- 切换前 800 ms 内**没有** `TYPE_TOUCH_INTERACTION_START`（排除用户手动点图标）；
- `B` 不是桌面、不是 SystemUI、不在排除表；
- `B` 在生效范围内（白名单）；
- 距上次转窗 > 3 s（冷却，防连环转）。

**为什么这个判据够用**：用户从桌面点图标一定会产生一次触摸；从通知、分享面板、授权页跳回来通常不会在同一位置产生触摸。这是本方案里性价比最高的一条启发式，也直接决定了 3.4/3.5/3.6 能不能落地。

**可选增强（真能做的"正向"实现）**：注册 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` 的 `<intent-filter>`，让"轻窗"出现在分享面板里。用户主动选"轻窗 → 微信"，就一定走浮窗，100% 准确、零误判。代价是分享时多一步选择。这条建议作为"应用分享"的默认实现方式，事后转窗作为补充。

### 3.7 复制链接（不能自动，必须降级）

**硬限制**：Android 10 起，非前台应用读 `ClipboardManager.getPrimaryClip()` 返回 `null`。`OnPrimaryClipChangedListener` 的**回调仍会触发**（能知道"发生了一次复制"），但**读不到内容**，所以无法判断"复制的是不是链接"。

**可行的降级**（三选一，建议 B）：
- A. 只在 WheelPilot 自己处于前台时读取 —— 实用价值低。
- B. **感知到复制后，在热区把手/轮盘上加一个「打开剪贴板链接」项**：用户点它时 WheelPilot 已在前台，能正常读到剪贴板并解析 URL → 浮窗打开。从"自动"变成"一次点击"，但 100% 可靠。
- C. root/Shizuku 下尝试 `service call clipboard`（版本碎片化严重，不建议作为主路径）。

**结论**：截图里"复制链接"这个开关如果做成"自动"，在真机上会长期表现为"有时生效有时不生效"，这是最伤口碑的一类缺陷。建议在 UI 上把它改成"复制链接后在轮盘提供打开入口"，并如实标注限制。

---

## 4. 方案架构

```
事件源（Watcher）                     判据（纯函数，可单测）              执行（Action）
┌──────────────────────────┐        ┌─────────────────────────┐      ┌──────────────────────┐
│ 无障碍 windowStateChanged │──┐     │ LightOpenRules.decide() │      │ LightOpenController  │
│ 无障碍 touchInteraction   │──┤     │  · 场景标签             │      │  · 冷却 / 白名单      │
│ 通知 posted / removed     │──┼───▶ │  · 触摸抑制             │────▶ │  · 转窗命令链         │
│ SystemUI shade / QS 窗口  │──┤     │  · 排除表               │      │  · 校验 + 回滚        │
│ 剪贴板变更（仅计数）       │──┘     │  · 命中/放弃 + 原因      │      │  · 统计与日志         │
└──────────────────────────┘        └─────────────────────────┘      └──────────────────────┘
                                               │                              │
                                          Prefs 7 个开关                 AppLauncher 复用
                                          + 冷却时长 + 范围               WindowModes / 命令通道
```

### 4.1 状态机

| 状态 | 进入条件 | 转出 |
|---|---|---|
| `IDLE` | 初始 / 冷却结束 | 收到"场景锚点"事件（shade 展开、QS 展开、heads-up、通知包名更新）→ `ARMED` |
| `ARMED` | 有锚点，TTL 3 s | 前台包名变化 → 判定；TTL 到期 → `IDLE` |
| `JUDGE` | 前台切换 | 判据全过 → `CONVERT`；任一不过 → `IDLE`（记一次 reject 原因） |
| `CONVERT` | 判据通过 | 执行转窗命令 → `VERIFY` |
| `VERIFY` | 命令已发 | 800 ms 内前台=目标且窗口非全屏 → `DONE`；否则 → `ROLLBACK` |
| `ROLLBACK` | 转窗失败 | 保持全屏原样（不反复重试），记日志 + 计数 → `IDLE` |

**关键设计**：`ROLLBACK` 只记录不重试。转窗失败时用户看到的就是"正常全屏打开"，体验等同没有这个功能，不会出现"窗口反复抽搐"。

### 4.2 转窗实现（本方案唯一的技术单点）

三步链，逐级降级，全部走已有的 `priv-kit` shell 通道：

| 顺序 | 命令 | 说明 | 预期 |
|---|---|---|---|
| 主路径 | `am start -n pkg/act --windowingMode 5` | 带 NEW_TASK 复用已有 task，把 windowingMode 改成 freeform。**需验证"已在全屏前台的 task"是否被就地改模式** | AOSP 通常可行；MIUI 可能被自家浮窗栈接管 |
| 兜底 1 | `am task resizeable <id> 2` + `am task resize <id> l,t,r,b` | 已在项目里用于荣耀尺寸修正，复用 `StackListParser` 找 task | 只改几何，不改模式，freeform 未启用时无效 |
| 兜底 2 | `am stack move-task`（Android 11 已移除）/ 无障碍点"小窗"按钮 | ROM 相关，默认关闭，仅作为用户手动开关 | 不承诺 |

**验证方式：脚本已就绪** —— `docs/verify-windowing.sh <包名> [Activity]`。它按顺序跑完「全屏启动 → 读 windowingMode → `am start --windowingMode 5` → 读结果 → `am task resizeable` + `am task resize` 兜底 → 读结果」，直接给出四种结论之一：

| 结论 | 含义 | 对方案的影响 |
|---|---|---|
| `PASS-RELAUNCH` | relaunch 就能把全屏 task 改成浮窗 | 引擎主路径成立，按本文方案实现 |
| `FALLBACK-RESIZE` | relaunch 只改几何、模式没变，但 resize 有效 | 引擎必须带 resize 兜底，延迟会 +150 ms 左右 |
| `FAIL` | 两条命令都不动这个 task | 该 ROM 只能走无障碍点系统小窗按钮，成功率下调 |
| `MANUAL` | 输出里取不到 windowingMode | 需人工看 dumpsys 片段，并把该 ROM 的字段格式加入解析 |

脚本已通过 `bash -n` 语法校验，无设备时退出码 2 并给出明确提示。需要在 **Pixel 3（AOSP）** 与 **小米 11 Pro（HyperOS）** 上各跑一次：两者结论可能不同（荣耀已有"尺寸被钳制"的先例），因此引擎的转窗分支设计成三档可配置（`relaunch / resize / a11y-click`），按机型取默认值。

### 4.3 数据与配置

```
Prefs 新增（全部进 BackupCodec，无需改备份格式）：
  lo_enabled        Boolean  总开关（默认 false）
  lo_notif_shade    Boolean  通知中心
  lo_notif_heads_up Boolean  通知横幅
  lo_quick_settings Boolean  控制中心
  lo_app_jump       Boolean  应用跳转（含登录授权、应用分享）
  lo_clip_link      Boolean  复制链接（降级为"轮盘入口"）
  lo_scope          String   生效范围：wheel（仅轮盘内应用）/ all（除排除表外全部）
  lo_cooldown_ms    Int      冷却，默认 3000
  lo_share_target   Boolean  是否在分享面板注册"轻窗"入口（默认 false）
```

### 4.4 UI（对齐截图形态）

独立页面 `LightOpenActivity`（截图是带返回箭头和大标题的全屏页，不是设置页里的折叠分组）：

- 顶部返回 + 大标题「轻量打开」+ 副标题「在以下场景使用自由浮窗打开」；
- 7 行开关（沿用 `SwitchRow` 的视觉，绿色开关状态与截图一致）；
- 底部状态区：无障碍/通知使用权/特权通道三项健康状态（任一缺失时把对应行置灰并给一句原因，而不是静默失败）；
- 「应用跳转」行下加一行小字：「登录授权与应用分享共用此判据」；
- 「复制链接」行下加一行小字：「系统限制：复制后需从轮盘点一次『打开链接』」。

入口：设置 → 系统 → 「轻量打开」。

---

## 5. 分阶段实施

| 阶段 | 内容 | 新增/改动 | 预估代码量 | 可独立发版 |
|---|---|---|---|---|
| **S1** | 底座：Watcher + Rules + Controller + Prefs + 页面；先接 **通知中心 / 通知横幅 / 应用跳转** 三个判据最强的场景 | 新增 5 文件，改 4 文件 | ~550 行 + 测试 ~120 行 | 是（3.10.0） |
| **S2** | 控制中心判据（含 ROM 类名适配表）+ 分享面板 `ACTION_SEND` 入口 | 改 3 文件 | ~180 行 | 是（3.10.1） |
| **S3** | 复制链接降级入口 + 诊断面板（每场景命中/放弃计数、失败原因导出） | 新增 1 改 2 | ~150 行 | 是（3.11） |

**建议先做 S1 并真机跑一轮**：S1 结束时就能回答"事后转窗在你这台机器上到底闪不闪、准不准"，后面两阶段的取舍都依赖这个结果。如果 S1 在某一台真机上转窗成功率低于 60%，方案应就地改为"只保留分享面板显式入口 + 剪贴板手动入口"这两条 100% 可靠的路径，而不是继续堆启发式。

---

## 6. 验收指标

| 指标 | 基线 | 目标 | 测法 |
|---|---|---|---|
| 事件到转窗生效 | 未实现（无数据） | P95 ≤ 400 ms | 日志打 `armTs / convertTs / freeformVisibleTs`，跑 30 次 |
| 全屏闪现可见时长 | 未实现 | P95 ≤ 400 ms | 录屏逐帧数（240 fps 或 60 fps 折算） |
| 通知中心命中率 | — | ≥ 90%（20 次 / 机型） | 微信、短信、邮件各 20 条，点通知计数 |
| 通知横幅命中率 | — | ≥ 85% | 同上 |
| 控制中心命中率 | — | ≥ 70%（按机型分别统计） | 用 3 个会拉起应用的磁贴 |
| 应用跳转命中率 | — | ≥ 85% | 浏览器→应用、应用→应用各 20 次 |
| **误转率** | — | **0 / 100** | 从桌面手点图标 100 次，不允许出现一次被转窗 |
| 不支持浮窗的应用 | — | 0 崩溃、0 卡死 | 银行类、游戏类各 5 个，结果应为"保持全屏" |
| 单测 | 41 例 | ≥ 60 例，全绿 | `./gradlew testDebugUnitTest` |
| 包体 | 3.89 MB | ≤ 4.05 MB | `ls -l` APK 字节 |

---

## 7. 风险与不可行边界

| 风险 | 影响 | 应对 |
|---|---|---|
| 三者不可区分 | 无法给出 3 个真正独立的开关 | 合并为「应用跳转」，UI 注明；或保留 3 行但共用判据 |
| 复制链接读不到内容 | 自动方案必然"时灵时不灵" | 降级为轮盘入口，UI 明示 |
| ROM 差异（HyperOS/ColorOS/MagicOS） | 转窗命令成功率不同，QS/shade 类名不同 | 按 `Build.MANUFACTURER` 分叉 + 类名白名单常量；未知 ROM 走保守判定 |
| 无障碍服务被杀 | 全部判据失效（这是该类工具最高频失效原因） | 复用 3.9.1 的自愈思路：健康检查 + 断连广播 + 引导页 |
| 误转破坏支付/银行流程 | 用户体验事故 | 默认范围 = 轮盘内应用（`lo_scope=wheel`）；排除表内置常见银行、支付、游戏包名；冷却 3 s |
| `am start --windowingMode` 就地改模式不生效 | 整个方案退化为"不可行" | **S1 前先做第 4.2 节的两条真机验证**；不生效则改用无障碍点系统小窗按钮，或直接退到显式入口方案 |
| 通知监听需单独授权 | 关闭时通知两行开关无效 | 页面顶部显示授权状态，未授权时该行置灰 |

---

## 8. 改动清单

**新增**
- `core/LightOpenScene.kt` — 场景枚举 + 排除表 + `decide()` 纯函数（可单测）
- `LightOpenWatcher.kt` — 无障碍/通知/SystemUI 窗口/剪贴板事件汇总，输出 `LightOpenSignal`
- `LightOpenController.kt` — 状态机、冷却、转窗命令链、校验与回滚、计数
- `LightOpenActivity.kt` — 截图形态的设置页
- `app/src/test/java/com/pilot/wheel/LightOpenRulesTest.kt` — 判据用例（预计 18 例）

**修改**
- `app/src/main/res/xml/accessibility_service_config.xml` — `eventTypes` 加 `typeTouchInteractionStart|typeTouchInteractionEnd`
- `PilotService.kt` — `onAccessibilityEvent()` 分流：窗口事件既给 `updateScenePause` 也给 `LightOpenWatcher`
- `PilotNotificationListener.kt` — 记录 heads-up 标记与时间锚点，转发给 Watcher（不改现有微信逻辑）
- `Prefs.kt` — 新增 9 个键与读写函数
- `ui/SettingsTab.kt` — 「系统」组加一行入口
- `AndroidManifest.xml` — 注册 `LightOpenActivity`；（S2）`ACTION_SEND` 过滤器
- `app/build.gradle.kts` — `versionCode 70` / `versionName "3.10.0"`
- `README.md` — 能力与限制

**不动的部分**：`AppLauncher` 的现有冷启动逻辑、轮盘/热区、备份格式、既有 41 例测试。

---

## 9. 下一步

按 S1 → S2 → S3 落地。技术单点的验证已做成脚本：`docs/verify-windowing.sh <包名>`，设备接入后一条命令给出 `PASS-RELAUNCH / FALLBACK-RESIZE / FAIL / MANUAL` 四种结论之一（当前 `adb devices` 为空，脚本在无设备时按预期退出码 2）。转窗分支写成三档可配置（`relaunch / resize / a11y-click`），先按 `relaunch` 落地，真机结论出来再定各 ROM 的默认值 —— 这个顺序不改变 S1 的开工条件。

本文只做评估，未改动任何代码，`3.9.1-69` 的 APK 与源码保持原状；新增的只有 `docs/` 下这两份文件。
