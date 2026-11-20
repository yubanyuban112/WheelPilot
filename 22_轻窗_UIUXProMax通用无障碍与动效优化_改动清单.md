# 22 · 轻窗全页面按 UI UX Pro Max 通用质量纪律优化（3.17.1 / 86）

> 规范源：`ui-ux-pro-max` 技能（CRITICAL/HIGH 通用纪律：Accessibility、Touch & Interaction、Performance、reduced-motion、color-contrast）。
> 工程：`D:\08_roms\WorkBuddy\2026-11-16-19-43-59\pilot-app`
> 版本：3.17.0 / 85 → **3.17.1 / 86**
> 备份：`D_src_backup_before_uxpromax_20261118`（7 个 .kt）
> 产物：`WheelPilot-3.17.1-86-signed-qingchuang.apk`（签名证书 SHA-256 与前版一致，可覆盖安装）

---

## 〇、边界（本轮刻意不做，避免破坏既有成果）

UI UX Pro Max 的推荐里有大量 **Web 专属建议**，照搬会破坏本项目已建立的设计系统与多轮修复过的布局，本轮明确排除：

| 排除项 | 原因 |
|---|---|
| Flat Design 色板 / #3B82F6 主色 / 无阴影 | 与四套主题（经典/新拟物/液态玻璃/Apple）冲突；新拟物双色阴影是其自身设计语言 |
| Inter 字体 / 375-1024px 断点 / max-w-6xl / 浮动 navbar / cursor-pointer / hover | 全部是 Web/CSS 概念，Android Jetpack Compose 不适用 |
| 文本符号图标（× / ✓ / +）替换为 SVG Icon | 属视觉改动，需真机确认；本轮只补语义标签，符号本身保留 |
| 26dp「加入轮盘」等小热区强行扩高 | 与 neuPressable 按压手感/药丸几何冲突，属手感范畴，交真机验收 |
| 拖拽排序的 TalkBack 无障碍替代操作（上移/下移按钮） | 属新增交互功能，改动面大，列入后续项 |

本轮只做 **平台无关、客观可判定、可编译验证** 的通用质量纪律：无障碍语义、reduced-motion、对比度。

---

## 一、改动清单（7 个文件）

### 1. `UiStyle.kt` — 共享组件层（一次改，覆盖全部调用点）

| 位置 | 改动 | 依据 |
|---|---|---|
| 新增 `rememberMotionEnabled()` | 全应用单源读取 `ANIMATOR_DURATION_SCALE`（系统"减弱动效"开关），`cascadeEnter` 改为复用它 | Accessibility / reduced-motion |
| `neuPressable` | 按压 morph 动画在减弱动效下改用 `snap()` | reduced-motion |
| `NeuSwitch` | 补 `role=Switch` + `stateDescription`（已开/已关闭）；fill/knob/knobScale 三处动画响应减弱动效 | Accessibility + reduced-motion |
| `NeuSlider` | 补 `role=ValuePicker` + `progressBarRangeInfo` + `setProgress("调整数值"){}`，TalkBack 可读当前值并滑动调整；动画响应减弱动效 | Accessibility + reduced-motion |
| `NeuChip` | 补 `role=RadioButton` + `selected`（仅语义，不改 32dp 高度 —— 主题 chip 此前专门修过 FlowRow 换行重叠） | Accessibility |
| `NeuButton` | primary 按压 scale 动画响应减弱动效 | reduced-motion |

> 版本适配：Compose 1.12.1 无 `checked` 语义属性、无 `Role.Slider`、`setProgress` 为带 label 的函数，已按实际 API 实现并经编译确认。

### 2. `MainActivity.kt`
- 页面切换 `AnimatedContent`：减弱动效开启时退化为 `snap()` 瞬时切换；否则保留原方向感滑动+淡入。
- 底栏药丸 `animateDpAsState`、选中色 `animateColorAsState`：减弱动效下用 `snap()`。
- 底栏三个页签：补 `role=Tab` + `selected`。

### 3. `AppSheetActivity.kt`（更多面板）
- 面板进出场 `AnimatedVisibility`、页签药丸滑动：减弱动效响应。
- 关闭按钮：补 `role=Button` + `contentDescription="关闭"`（TalkBack 不再读成"乘号"）。
- 分页圆点：补 `role=Tab` + `selected` + "第 N 页，共 2 页"。
- SheetTab 页签：补 `role=Tab` + `selected`。
- 入口选择器列表项：补 `role=Button` + `heightIn(min=44dp)` 热区。
- 对比度：两处 labelSmall 说明文字 `FieldHint` → `InkSub`（浅色主题 2.5-3.5:1 → 达标）。

### 4. `LightOpenActivity.kt`（轻量打开页）
- 返回按钮、总开关、两个生效范围开关、场景开关行、健康状态行、刷新/清空：共 8 处补 role + contentDescription + 已开/已关状态朗读。
- 对比度：dimmed 场景图标/标题 `FieldHint` → `InkSub`。

### 5. `ui/HomeTab.kt`
- 轮盘计数次要数字 `Primary.copy(0.72)` → `InkSub`（12sp 小字对比 2.4:1 → 达标）。
- 状态行"去开启/去连接"动作 pill：补 `role=Button`。

### 6. `ui/AppsTab.kt`
- 应用格、工具格：补 `role=Checkbox` + `selected`（TalkBack 读"已勾选/未勾选"，此前只有 ✓ 视觉符号）。

### 7. `ui/SettingsTab.kt`
- 开关行：补 `role=Switch` + 状态朗读。
- 取值行、说明行、可折叠分组标题：补 `role=Button`（分组另含"已折叠/已展开"状态）。
- 对比度：BETA 警示卡标题 `#B26A00` → `#7A4A00`（3.87:1 → 约 6.6:1）。

---

## 二、验证证据（确定性，已执行）

| 项 | 结果 |
|---|---|
| `assembleDebug` + `testDebugUnitTest` | **BUILD SUCCESSFUL in 38s** |
| 单测用例 | **130 个，失败 0**（与基线一致，无回归） |
| `assembleRelease`（R8 压缩 + lintVital） | **BUILD SUCCESSFUL in 1m 51s** |
| 无障碍 role 语义 | 23 处，覆盖全部 7 个文件（静态扫描确认） |
| 减弱动效检测 | 7 个调用点（UiStyle 4 + MainActivity 2 + AppSheet 1） |
| 对比度修正 | 4 处硬编码/token 替换落地（静态扫描确认） |
| 签名证书 SHA-256 | `27c3888f0c0e3613090ebb35bcd32a81f39a78cfa8b919d9ec171de4af0d1cf4`（与前版一致） |
| 编译告警 | 仅既有 Icons deprecated / 既有未使用表达式，无 error |

---

## 三、真机视觉/手感验收清单（需用户执行）

按测试协作规则，UI 视觉/排版/动效/手感属主观判断，以下项代码层已改但**未自行宣称通过**，需真机确认：

1. **减弱动效**：系统开启"移除动画/减弱动效"后，切页签、开关面板、切换主题 chip 应无滑动/缩放补间（直接呈现）；关闭该开关后动画恢复。
2. **无障碍语义**（开启 TalkBack）：底栏页签读出"标签、已选中"；关闭按钮读"关闭 按钮"；分页圆点读"第 N 页，共 2 页"；开关读"已开启/已关闭"；应用格读"已勾选/未勾选"；滑块读当前值并可左右滑动调整。
3. **对比度**：Apple/经典/新拟物主题下，工具页说明小字、BETA 卡标题、Home 页计数次要数字、轻量打开页 dimmed 场景文字可读性无下降。
4. **布局无回归**：确认本轮语义改动未引起任何控件错位/重叠（尤其主题 chip 换行、底栏药丸、分页圆点）。

## 四、已知限制
- 文本符号图标（×/✓/+）本轮只补语义、未替换为矢量图标 —— 属视觉改动，留待真机确认后决定。
- 26dp「加入轮盘」小按钮、拖拽排序的无障碍替代操作未改 —— 前者涉及按压手感，后者属新增交互，均需专项处理。
- 无障碍语义使用 `stateDescription` 而非 `checked`（Compose 1.12.1 无 checked 属性），TalkBack 朗读措辞以真机实际为准。
