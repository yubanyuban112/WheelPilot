# Apple DESIGN.md — awesome-design-md

> 来源：VoltAgent/awesome-design-md `design-md/apple/DESIGN.md`（getdesign.md/apple 同步）
> 用途：轻窗 app「Apple 流体」主题的全页面对齐规范源（本轮唯一权威基准）。
> 落地原则：色板/圆角严格套用；字重梯、负字距、触控目标、无 chrome 投影/无装饰渐变等纪律在通用层生效；
> 保持四主题并行架构，LIQUID/NEU/CLASSIC 的既有色板不被 Apple 规范覆盖。

## 1. 设计主题（Visual Theme & Atmosphere）
摄影优先的界面，把营销变成博物馆画廊。通栏产品图块在明暗画布间交替，SF Pro Display 标题配负字距，
唯一交互色 Action Blue #0066cc。UI 装饰退居幕后——无装饰渐变、chrome 无投影，只有产品图落在表面上时的那一道签名投影。

## 2. 色板与角色（Color Palette & Roles）
| token | hex | 角色 |
|---|---|---|
| primary | #0066cc | 唯一交互色（Action Blue，浅色底专用） |
| primary-focus | #0071e3 | 聚焦态 |
| primary-on-dark | #2997ff | 深色底专用（禁用于浅色底） |
| ink / body | #1d1d1f | 近黑主文字 |
| body-on-dark | #ffffff | 深色块上的文字 |
| body-muted | #cccccc | 深色块上次级文字 |
| ink-muted-80 | #333333 | 珍珠按钮上的正文 |
| ink-muted-48 | #7a7a7a | 禁用文字 / 次级说明 |
| divider-soft | #f0f0f0 | 次级按钮的环影（≈ rgba(0,0,0,0.04)） |
| hairline | #e0e0e0 | 发丝线边框 |
| canvas | #ffffff | 主画布 |
| canvas-parchment | #f5f5f7 | 羊皮纸底（分组底） |
| surface-pearl | #fafafc | 珍珠白表面 |
| surface-tile-1/2/3 | #272729 / #2a2a2c / #252527 | 深色图块 |
| surface-black | #000000 | 顶部导航 |
| surface-chip-translucent | #d2d2d7 | 半透明 chip |
| on-primary / on-dark | #ffffff | 反色文字 |

**无装饰渐变**：大气层次来自产品图本身，不定义任何渐变 token。

## 3. 排版（Typography）
字体：Display = SF Pro Display（≥19px），Body/UI = SF Pro Text（<20px），数字启用 numerator。

| token | size | weight | line-height | letter-spacing | 用途 |
|---|---|---|---|---|---|
| hero-display | 56 | 600 | 1.07 | -0.28 | 页面英雄标题（Apple 紧字距签名） |
| display-lg | 40 | 600 | 1.10 | 0 | 图块标题 |
| display-md | 34 | 600 | 1.47 | -0.374 | 分区标题 |
| lead | 28 | 400 | 1.14 | 0.196 | 图块副文案 |
| lead-airy | 24 | 300 | 1.5 | 0 | 环境页引导段（罕见 300） |
| tagline | 21 | 600 | 1.19 | 0.231 | 副标题 |
| body-strong | 17 | 600 | 1.24 | -0.374 | 行内强调 |
| body | 17 | 400 | 1.47 | -0.374 | 默认正文 |
| caption | 14 | 400 | 1.43 | -0.224 | 次级说明 / 按钮文字 |
| caption-strong | 14 | 600 | 1.29 | -0.224 | 强调说明 |
| button-large | 18 | 300 | 1.0 | 0 | 商店 CTA（罕见 300） |
| button-utility | 14 | 400 | 1.29 | -0.224 | 工具按钮 |
| fine-print | 12 | 400 | 1.0 | -0.12 | 细则 |
| micro-legal | 10 | 400 | 1.3 | -0.08 | 法律微字 |
| nav-link | 12 | 400 | 1.0 | -0.12 | 导航项 |

**字重梯：300 / 400 / 600 / 700，500 刻意缺席。** 正文恒 400，行内强调 600，标题 600。
**正文行高不小于 1.47**（编辑式 leading 是品牌的一部分）。

## 4. 圆角（Shapes & Radii）
| token | 值 | 用途 |
|---|---|---|
| none | 0 | 通栏图块（不圆角） |
| xs | 5 | 内联 chip（罕见） |
| sm | 8 | 深色工具按钮、内联卡片图 |
| md | 11 | 珍珠白按钮胶囊 |
| lg | 18 | 工具卡、配件网格卡 |
| pill | 9999 | 主蓝色药丸 CTA / 购买按钮 / 配置 chip / 搜索框（签名药丸） |
| full | 50% | 圆形控制 chip |

**禁混用圆角语法**：compact utility 用 sm(8)，工具卡用 lg(18)，药丸用 pill，中间值禁用（除罕见 md 珍珠按钮）。

## 5. 组件（Components）
- **global-nav**：纯黑顶栏，高 44px，文字 on-dark + nav-link(12/400/-0.12)，链接间距 ~20px。
- **Primary pill CTA**：Action Blue 实心 + 药丸圆角；active 用 scale(0.95) 系统级微交互；**按钮不投影**。
- **Pearl Button**：白珍珠底 #fafafc + hairline 环 + md(11) 圆角 + 近黑文字 #333333。
- **Dark utility button**：深色底 + sm(8) 圆角 + 白字。
- **Chips**：药丸圆角 + hairline 边框；选中态用 primary。
- **Search input**：药丸圆角（signature）。
- **Cards/tiles**：通栏图块不圆角（none）；工具卡 lg(18)；卡片无投影。

## 6. 阴影与层级（Elevation & Depth）
**阴影只保留给「落在表面上的产品图」那一道签名投影。**
**禁止给卡片、按钮、文字加投影。** 无装饰渐变背景。

## 7. Do / Don't
Do：
- 单一强调色 Action Blue；主操作药丸；active 用 scale 微交互。
- 充足留白；大号粗标题 + 负字距。
- 正文 400 / 强调 600 / 标题 600 的字重梯。
- 触控目标 ≥44px。

Don't：
- 禁引入第二个强调色（所有"点我"信号都是 Action Blue）。
- 禁给卡片/按钮/文字加投影（投影只留给产品图）。
- 禁装饰渐变背景。
- 禁正文用 500 字重（梯是 300/400/600/700，500 缺席）。
- 禁圆角通栏图块。
- 禁正文行高低于 1.47。
- 禁混用圆角语法（sm 8 / lg 18 / pill 之间，不用中间值）。
- 禁浅色底用 primary-on-dark（Sky Link Blue）。

## 8. 响应式（Responsive）
触控目标 ≥44px；小屏 hero 标题降到 28–34px；断点 419/640/735/833/1023/1068/1440。

## 9. Agent 落地摘要（本项目映射）
- 唯一交互色 → `UiTokens.Primary`（APPLE 主题 = #0066CC）；禁用第二强调色。
- 字重梯 → `UiType` 全部落 400/600（禁 Medium=500）；标题负字距已在 WheelTheme typography。
- 药丸语法 → `UiTokens.RadiusPill`；芯片/搜索/主 CTA 全药丸。
- 无 chrome 投影 → APPLE 主题下组件不加 elevation 投影；投影仅产品图（本项目无产品图场景，故一律不用装饰投影）。
- 触控目标 ≥44dp → 可点击行/pill/图标 ≥44dp。
- 正文行高 → 正文样式行高 ≥1.47 倍字号。
