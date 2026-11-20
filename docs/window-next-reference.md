# 「轻量小窗 Next」能力对照与可移植范围

参照对象：[diting-aviumui](https://github.com/N1-k0-la1/diting-aviumui) · AviumUI 16.2.2（Android 16）
batch59 / 2026-11-08 · 174 补丁 / 37 定制项目 · 适配小米 12T Pro（`diting`）与 Redmi K50 Ultra（`ditingp`）

本文只讨论**能力边界与落点**，不复用其代码。该项目改动位于 `frameworks/base`、`packages/apps/Launcher3`
与 `packages/apps/AviumSystemUIEX`，属 ROM 级；WheelPilot 是普通应用，可用手段限于
`ActivityOptions.setLaunchBounds`、`windowingMode`、特权通道的 `am task resize` 与无障碍事件。

## 一、能力逐条对照

| # | ROM 提供的能力 | WheelPilot 能否做到 | 落点 / 原因 |
|---|---|---|---|
| 1 | 底部上划悬停松手进小窗 | 否 | 底部手势区归系统导航，应用无法接管 |
| 2 | 最多三窗，新窗自动错开 | **能** | `AppLauncher.launch(mode)` 已带 `launchBounds`；只需在启动前按已开窗口数算矩形 |
| 3 | 第四个把最早的转后台 | **能** | 记录自己启动的 taskId，`am task move-to-back` 或收起 |
| 4 | 控制条拖动移动窗口 | 否（低成本方案不可行） | 拖动系统窗口装饰需 ROM 改动；自绘悬浮条覆盖在他人窗口上依赖 `SYSTEM_ALERT_WINDOW` 且要跟随 bounds，侵入性高 |
| 5 | 拖角把手改大小 | 否 | 同上，把手属系统装饰 |
| 6 | 控制条上拖缩成小卡片 | **可降级** | 能做「缩到小尺寸」，但拿不到真实缩略图；卡片面只能用应用图标代替 |
| 7 | 控制条下拖回全屏 | **能** | `am task resize` 铺满可用区域 |
| 8 | 单击/双击控制条自定义动作 | 否 | 无控制条，动作入口只能放在轮盘或工具面板 |
| 9 | 卡片拖到顶部关闭 | 否 | 同上。等价操作走轮盘/通知 |
| 10 | 横竖屏各记各的位置 | **能** | 自己维护两套 bounds，旋转后重新 `resize` |
| 11 | 点小窗外面可设「不动 / 缩小全部」 | **可近似** | 无障碍窗口事件可判断自己起的 task 是否失去焦点，据此缩小或不动 |
| 12 | 通知点击直接进小窗 | **已实现** | 3.10.x 的「轻量打开」通知场景即此能力 |
| 13 | 小窗内输入、返回归小窗，点外面不被抢 | 系统行为 | freeform task 焦点自然遵循此规则，无需实现 |
| 14 | 全局系统字体替换 | 否 | 需改系统字体目录与 `system_server`，非应用权限可达 |

结论：14 条里 **6 条可直接实现、1 条可降级、1 条已实现、6 条受架构限制**。
可移植部分的共同点 —— 全是「记状态 + 算矩形 + 发 `am` 命令」，不涉及绘制他人窗口。

## 二、三项值得先做

### 1. 多窗上限与自动错开（能力 2、3）

```
候选矩形 = f(索引, 屏幕可用区, 窗口尺寸)
索引 0/1/2 依次偏移 (24dp, 24dp)，避免完全重叠
超出上限时把最早创建的 task 转后台
```

- 状态：`LinkedHashMap<taskId, LaunchedWindow(pkg, bounds, createdAt, orientation)>`
- taskId 来源：`StackListParser.findTaskId`（已有）与 `WindowStateParser.findTaskId`（已有）
- 纯函数化：`LayerRules.nextBounds(index, screen, size)` / `LayerRules.evictTarget(list, limit)`，可单测
- 验收：连续开 4 个，前 3 个位置互不重叠，第 4 个打开时第 1 个转后台且回到前台可恢复

### 2. 横竖屏位置记忆（能力 10）

- `Prefs` 按方向存两套：`bounds_portrait` / `bounds_landscape`
- 方向判定用宽高比，不读 `Configuration`（避免服务与 Activity 不同步）
- 旋转后对每个存活 task 重发一次 `am task resize`
- 纯函数：`LayerRules.pickBounds(orientation, portrait, landscape, screen)`
- 验收：横屏摆好位置 → 转竖屏 → 再转回横屏，窗口回到横屏时的位置

### 3. 点外部行为（能力 11）

- 事件源：无障碍 `TYPE_WINDOW_STATE_CHANGED` 判据里已有的「前台是否属于自己起的 task」
- 两个取值：`NONE`（不动）/ `SHRINK_ALL`（全部缩到最小尺寸）
- 纯函数：`LayerRules.onFocusLost(mode, windows)` 返回需要 resize 的 task 列表

## 三、明确不做的部分

| 项 | 不做的理由 |
|---|---|
| 接管底部上划悬停 | 系统导航手势区不可被应用抢占，只能由 ROM 实现 |
| 系统窗口的控制条 / 角把手 | 属系统窗口装饰，应用无绘制权限 |
| 真实窗口缩略图 | 需 `MediaProjection` 持续截屏，功耗与隐私成本远超收益，改用应用图标 |
| 卡片拖到顶部关闭 | 无卡片实体可拖，交互入口改用轮盘与通知 |

## 四、落地顺序

1. `core/LayerRules.kt`（纯函数 + 单测）—— 三个算法，不碰系统
2. `Prefs` 增加 `layer_limit` / `layer_offsets` / `bounds_*` / `outside_behavior`
3. `AppLauncher` 启动前接 `nextBounds`；启动后登记 taskId
4. 轮盘或工具面板加「全部收起 / 全部回全屏」两个动作
5. 无障碍侧接「失去焦点」事件，按配置缩小或不动

前提：需要特权通道（已具备）才能 `am task resize`；无特权时第 1、2 项退化为只能错开启动位置，不能改已开窗口几何。

## 五、落地状态（3.11.0 / versionCode 74）

按上面第 1–5 步实现了前四项，第五项做了近似版本：

| 文档步骤 | 实现位置 | 说明 |
|---|---|---|
| 1. 纯函数 + 单测 | `core/LayerRules.kt` + `LayerRulesTest`（16 例） | `nextBounds` / `evictTargets` / `reflowForOrientation` / `shrink` / `outsideAction` |
| 2. Prefs 增配置 | `Prefs.layerLimit/layerOffset/outsideBehavior` | 台数 1–4（默认 3）、错开 0–48 dp（默认 24）、点外部（默认不动） |
| 3. 启动前接 `nextBounds` | `AppLauncher.freeformBounds()` | 索引 = 当前存活窗口数，越界自动夹回屏内 |
| 4. 登记 taskId + 驱逐 | `AppLauncher.resizeTaskToBounds()` → `PilotLayers.register()` | `am task resize` 成功后登记，再 `pruneOverLimit` |
| 4'. 批量动作入口 | `QuickTools` 的「小窗全屏 / 小窗缩小 / 收起小窗」 | ROM 上这些是控制条动作，应用层只能给等价入口 |
| 5. 失焦行为（近似） | `PilotLayers.onForeground()` ← `TYPE_WINDOW_STATE_CHANGED` | 按包名匹配登记窗口，`createdAt` 最新的视为焦点窗口 |

已知退化（与原文档一致，不再重复评估）：

- 无特权通道时只能错开启动位置，改不了已开窗口几何；
- 部分 ROM 没有 `am task move-to-back`，驱逐与收起退化为记日志（不销毁任务）；
- 同一包开两个窗口时，焦点窗口按创建时间猜，可能认错。
