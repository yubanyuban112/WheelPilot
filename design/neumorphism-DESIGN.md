# 新拟物派 (Neumorphism) — 设计规范（微调版）

> 来源：StyleKit `neumorphism`（modern / minimal），stylekit-generator 提取。
> 用途：轻窗 app「新拟物」主题 token 来源 —— **微调版**，与现有「经典拟物」主题拉开差异：
> 基底换为 StyleKit 原始数据 #E0E5EC（经典版用 #E6E9EF），强调色换为 StyleKit 紫 #6D5DFC（经典版用蓝紫 #2F6BFF），文字用灰阶 #33333D（经典版用蓝灰 #253047）。

## 设计哲学

介于扁平与拟物之间：通过柔和双重阴影创造元素从背景中「挤压/凹陷」的触感。同色系统一、凸起=可交互、凹陷=已激活/输入区、克制装饰。

## 色彩系统

| 角色 | 色值 | 说明 |
|------|------|------|
| 主背景/表面 | `#E0E5EC` | StyleKit 原始基底（同色系核心） |
| 浮层 | `#E8EDF4` | 比基底亮一档 |
| 暗阴影 | `#B8BCC2` | 右下 +X/+Y |
| 亮阴影 | `#FFFFFF` | 左上 -X/-Y |
| 强调色 | `#6D5DFC` | StyleKit 紫（唯一交互色） |
| 文字主色 | `#33333D` | 灰阶（StyleKit gray-800） |
| 次要文字 | `#6B7280` | |
| 分割线 | `#D1D9E6` | |

## 阴影参数（Fixed Illuminant：光源固定左上）

- 凸起（卡片）：`16px blur / 8px offset` → 双色（#B8BCC2 右下、#FFFFFF 左上）
- 凸起（行/按钮）：`12px blur / 6px offset`
- 凹陷（输入/激活）：inset 双色，模糊为偏移 1.5-2 倍
- 全强度会显脏：阴影 alpha 控制在 232/246（「一层薄薄的光」）

## 排版规范

- 文字灰阶体系（#33333D / #6B7280），标题负字距
- 字号阶梯沿用系统；标题字重 SemiBold+

## 组件规范

- **Button**：同色本体 + 双色凸影；hover 阴影缩小（Hover Shadowing）；active 凸→凹（Extrude to Intrude），禁 translate；主操作 = 实心紫 #6D5DFC + 同色柔光晕
- **Card**：圆角 24dp、凸影 20px/10px
- **Input**：凹陷 + focus 时内阴影深度减小（通道开放感），禁增大
- **Toggle**：凹槽轨道（开=注满紫+柔光），旋钮实体圆钮带顶光
- **Slider**：凹槽轨道 + 紫色已选段 + 白色浮起旋钮
- **Chip**：选中=凹陷，未选=凸起，选中文字染紫

## Do ✅

- 浅色基底 #E0E5EC，元素与背景同色系
- 双色阴影：左上白、右下 #B8BCC2，方向永不破坏
- 按下凸→凹，300ms ease-in-out（Smooth Molding）
- focus 内阴影减小（通道开放）
- 圆角 12-24dp

## Don't ❌

- 禁纯黑/纯白背景、渐变背景、粗边框（border-2+）
- 禁硬边缘阴影、直角
- 禁按钮 translate 位移（元素长在背景上）
- 禁 hover 增大阴影（手指靠近应遮光缩小）
- 禁高对比配色

## AI Rules 交互四原则

1. **Extrude to Intrude**：active 必须外凸转内凹，禁止位移
2. **Hover Shadowing**：hover 阴影缩小不扩大
3. **Smooth Molding**：全部过渡 300ms ease-in-out
4. **Fixed Illuminant**：光源锁定左上，亮影 -X/-Y、暗影 +X/+Y
