# Apple 流动玻璃 (Apple Liquid Glass) — 设计规范

> 来源：StyleKit `liquid-glass`（modern / brand-inspired），stylekit-generator 提取。
> 用途：轻窗 app「液态玻璃」主题 token 来源。

## 设计哲学

Liquid Glass 是 Apple WWDC 2025 发布的设计语言，超越传统毛玻璃：通过光线折射、彩虹边缘和流体动画创造有机、流动的视觉体验。多层玻璃堆叠产生空间层次，高饱和度背景衬托半透明面板。

## 色彩系统

| 角色 | 色值 | 说明 |
|------|------|------|
| 画布底 | `#1A1A2E → #16213E → #0F0F23` | 深色渐变（对角） |
| 面板 | `rgba(255,255,255,0.10)` | 主面板 white/10 |
| 面板（浮层） | `rgba(255,255,255,0.15)` | hover/高层 white/15 |
| 彩虹描边 | `#FF6B6B → #4ECDC4 → #A855F7` | 光线折射模拟 |
| 顶部高光 | `rgba(255,255,255,0.20) → transparent` | 玻璃倒角 |
| 强调色 | `#007AFF`（Apple 蓝）/ `#FF2D55`（Apple 粉） | 激活/主操作 |
| 正文 | `white 90%` | 高对比可读 |
| 次要文字 | `white 60-70%` | |
| 装饰光斑 | `#FF2D55` / `#A855F7` / `#007AFF` 各 20-25% + blur | 画布流体折射 |

## 模糊与饱和

- 模糊：`blur(40px)` 级别（Compose：RenderEffect 31+，API 30 降级为半透明叠层）
- 饱和：`saturate(1.8)` —— 半透明面板下的背景色彩依然鲜艳

## 排版规范

- 标题：白色粗体，不使用描边/发光（app 场景可读性优先；网页级霓虹描边仅用于 landing）
- 正文：white/80–white/90
- 字号阶梯沿用系统，标题负字距

## 组件规范（app 落地参数）

- **Panel（GlassPanel/glassRow）**：fill white/10 + blur + 描边 white/25 1dp + 内顶高光白/40 1dp + 阴影 black/18 24dp；圆角 24dp（卡片）/14dp（行）
- **Button**：white/10 fill + rainbow 描边（主按钮实心 `#007AFF` + 同色光晕阴影）；hover white/15、边框亮度 /20→/40；active 缩放 0.95–0.98（硅胶压缩）；过渡 ≥500ms `cubic-bezier(0.25,1,0.5,1)`
- **Input/SearchField**：white/10 fill、white/20 描边、16dp 圆角；focus：white/15 + 紫 #A855F7 光晕环 2dp
- **Toggle（NeuSwitch）**：轨道 white/12（开：#007AFF/70 注满+发光）；旋钮白色 90%，柔影
- **Slider（NeuSlider）**：轨道 white/12；已选段 #007AFF/85；旋钮 white/90 圆形带发光柔影
- **Chip（NeuChip）**：未选 white/8 描边 white/15；选中 white/18 描边 #4ECDC4/60 + 文字 #4ECDC4
- **IconBadge（NeuIconBadge）**：white/10 底座 + white/25 描边 + 顶部高光，图标浮其上
- **画布（GlassCanvas）**：深色渐变底 + 2-3 个大彩色光斑（品红/紫/蓝，25% alpha，blur 60+），API 31+ 可缓慢漂移

## Do ✅

- 半透明面板 + 高模糊 + 高饱和背景
- 彩虹渐变描边模拟折射
- 顶部高光渐变条
- 超大圆角（≥24dp 卡片 / 16dp 输入框）
- 流体过渡 ≥500ms ease-out
- active 柔软压缩 0.95–0.98
- 多层堆叠：底渐变 → 玻璃面板 → 描边/高光层 → 内容

## Don't ❌

- 禁纯色不透明背景面板（bg-white / bg-black）
- 禁浅色背景作容器底层（必须深色渐变）
- 禁低模糊（<20px 级）与省略饱和度增强
- 禁直角/小圆角
- 禁快速过渡（<300ms）
- 禁单一颜色边框（主面板应用彩虹渐变或至少 white 渐变）
- 禁 emoji 图标、紫蓝渐变滥用

## AI Rules 摘录（app 落地相关）

- 层级结构：底层深色渐变 → 中层玻璃容器 → 边框层（1dp 渐变）→ 高光层 → 内容层
- Spatial Push：hover 轻微前推 + 阴影同步扩散
- Specular Edges：交互时边框亮度 white/20 → white/40
- Silky Squeeze：active 柔软压缩，避免硬切缩放
- 自检：深色渐变底 ✓ / 半透明面板 ✓ / 描边+高光 ✓ / 大圆角 ✓ / 长过渡 ✓ / 文字可读 ✓
