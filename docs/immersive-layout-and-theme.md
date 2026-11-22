# 沉浸式复习布局、进度语义与全局外观设计说明

> 调研与访问日期：**2026-11-21**（全部依据公开官方文档实地检索；未引用未访问的第三方 App、不照搬品牌图形、未使用付费设计服务、不引入重型第三方依赖）。

---

## 1. 公开设计规范调研与取舍

| 官方来源（已实查 URL） | 访问日期 | 关键设计原则 | 本项目采用 / 不采用 |
|---|---|---|---|
| **Material Design 3 — Progress Indicators**<br>https://m3.material.io/components/progress-indicators<br>**Material Design 3 — Color Overview**<br>https://m3.material.io/styles/color | 2026-11-21 | 1. 语义色角色分离：Active Indicator 使用 `Primary`，Track 使用低强调表面色。<br>2. 非文本对比度要求 ≥ 3:1；若线性轨道与底面对比度不足 3:1，必须在轨道末端显示 **stop indicator** 明确终点。<br>3. 进度类型需与语义匹配（确定进度用 determinate，不混用无标签进度条）。 | **采用**：默认使用克制细线 `line` 并在轨道末端绘制 M3 stop dot；提供 `segments`（分段刻度，超 12 项自动压缩到 12 段避免过密）与 `ring`（紧邻 `本批 x / y` 的 20dp 环形）共 3 种有意义变体；严格区分「本批进度」与「单词熟练度 0–5」。<br>**不采用**：不引入 Material 3 组件库（保持零第三方依赖与低包体），不采用浮夸波浪/不确定动画以免干扰专注记忆。 |
| **Android Views — Edge-to-Edge & Window Insets**<br>https://developer.android.com/develop/ui/views/layout/edge-to-edge<br>https://developer.android.com/develop/ui/views/layout/edge-to-edge-manually | 2026-11-21 | 1. 全屏内容可延展到状态栏与导航栏背后，但所有交互控件与正文必须通过 `WindowInsets`（含 statusBar、navigationBar、displayCutout）避让安全区。<br>2. 若页面自定义深/浅表面，不可依赖系统自动猜测状态栏/导航栏图标颜色，必须按实际底面亮度显式切换深/浅系统栏图标。 | **采用**：`ReviewActivity` 的「全屏沉浸」模式启用透明系统栏与安全区 `OnApplyWindowInsetsListener` 内边距避让，并依据卡片实际亮度（含照片/深色预设/应用深色模式）动态切换系统栏图标颜色；底部 `Sheet` 亦同步避让手势导航栏。<br>**不采用**：不引入 AndroidX `enableEdgeToEdge()` 依赖；在默认「经典留边」模式下保留既有的稳重顶栏与实色系统栏视觉。 |
| **Android Views — Dark Theme & Touch Targets**<br>https://developer.android.com/develop/ui/views/theming/darktheme | 2026-11-21 | 1. 真正的应用级深色模式应覆盖全部页面、系统控件、对话框/弹窗、状态栏与导航栏，并支持「跟随系统 / 浅色 / 深色」。<br>2. 深色界面不能用纯黑刺眼反差，需采用分层深灰表面建立层级；可点击目标热区至少 `48dp × 48dp`（图标可更小，通过 padding 补足），并提供清晰按压状态反馈。 | **采用**：建立集中式 `AppTheme` + `Theme.apply(Context)` + `AppTheme.Dark` / `SheetDialog.Dark`，使主页、复习、设置、弹窗/Sheet、按钮图标、状态栏与导航栏全部实现真正的应用级深色模式；卡片内置的发音与更多按钮维持 `48dp × 48dp` 热区与按压波纹反馈。<br>**不采用**：不将「深色卡片预设」误标为系统或全局夜间模式。 |
| **W3C WCAG 2.2 — Contrast (Minimum) 1.4.3**<br>https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html<br>**W3C WCAG 2.2 — Target Size (Minimum) 2.5.8**<br>https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html | 2026-11-21 | 1. Level AA 正文文本对比度 ≥ 4.5:1，大号文本与 UI 组件 ≥ 3:1。<br>2. Level AA 指针目标最小 `24×24 CSS px`（触控屏优先取 Android 48dp 规范）。 | **采用**：全部 3 套视觉风格 × 浅/深 6 套完整调色板均经相对亮度公式逐项验证满足 AA（正文 ≥ 4.5:1、按钮白字 ≥ 4.8:1、主色控件 ≥ 3.1:1），移动端触控目标统一 ≥ 48dp。<br>**不采用**：不追求 AAA 7:1 高反差纯黑白（避免在护眼/夜间模式下产生眩光）。 |

---

## 2. 本次架构与实现决策

### 2.1 复习卡片布局（经典留边 vs. 全屏沉浸）
- **经典留边 (`classic`，默认)**：保留原有圆角纸面卡片与页边距，老用户升级零视觉突兀。
- **全屏沉浸 (`immersive`)**：卡片铺满视口（0dp 圆角，延展至状态栏与手势导航栏后），顶栏（`本批` 批次计数 + 进度指示 + `🔊 朗读` + `⋯ 更多`）与底部装饰（短句/纹样/图片）直接合入卡片内，并通过 `OnApplyWindowInsetsListener` 自动避让状态栏、刘海与底部手势条。
- **零手势冲突**：合入卡片顶部的 `🔊`（`48dp × 48dp`）与 `⋯`（`48dp × 48dp`）为独立可点击子视图，点击事件由按钮自身消费，不触发卡片点击翻面、下滑忘记或长按编辑。

### 2.2 进度指示与语义分离
- **批次进度（顶部）**：明确标注「本批」+ `x / y`，默认使用 **细线 (`line`)**（带 M3 终点 stop dot），可选 **分段 (`segments`)** 或 **环形 (`ring`)**，共 3 种克制样式；关闭「界面动态效果」时立即跳变不做动画。
- **单词熟练度（翻面答案区）**：固定使用 5 枚圆点 + `熟练度 L/5 · 标签`，与顶部「本批 x / y」在位置、视觉形态和文案上彻底分离，杜绝两条无解释进度条混淆。

### 2.3 全局外观设置（完整视觉风格 × 明暗策略解耦）
- **视觉风格（3 套）**：
  1. `paper_ink` **纸墨清爽**（默认）：暖白宣纸 + 松墨绿
  2. `soft_eye` **柔和护眼**：温润浅羊皮/淡苔纸 + 深苔绿，降低长时背词眩光
  3. `slate_mist` **静谧青灰**：清透雾灰纸 + 黛青蓝，层次克制
- **明暗策略（3 档）**：`system` **跟随系统**、`light` **始终浅色**、`dark` **始终深色**。
- **WCAG 2.2 AA 理论对比度校核（依据 W3C 相对亮度公式计算）**：

| 调色板 | `INK/BG` | `INK/SURFACE` | `INK_2/SURFACE` | `INK_3/SURFACE` | `ON_PRIMARY/PRIMARY` | `PRIMARY/SURFACE` |
|---|---:|---:|---:|---:|---:|---:|
| `paper_ink` 浅色 | 14.85 | 16.20 | 7.02 | 4.95 | 8.00 | 8.00 |
| `paper_ink` 深色 | 16.48 | 14.77 | 8.93 | 5.46 | 5.13 | 3.19 |
| `soft_eye` 浅色 | 13.39 | 14.39 | 6.79 | 4.94 | 7.59 | 7.09 |
| `soft_eye` 深色 | 15.16 | 13.67 | 8.46 | 5.27 | 4.87 | 3.32 |
| `slate_mist` 浅色 | 14.79 | 16.33 | 7.00 | 4.95 | 7.54 | 7.54 |
| `slate_mist` 深色 | 16.13 | 14.41 | 8.73 | 5.50 | 5.19 | 3.13 |

### 2.4 卡片自定义与全局主题优先级 & 备份/迁移兼容
- **渲染优先级**：用户自定义本地图片 (`BG_IMAGE`) > 渐变预设 (`BG_GRADIENT`) > 纯色预设 (`BG_SOLID`) > 默认纸面 (`BG_DEFAULT`，自动跟随全局 `AppTheme` 的当前风格与明暗表面色)。用户底部短句、纹样、图片在任何全局主题切换下均完整保留并自动匹配对比色。
- **持久化与备份**：`AppTheme` 存为紧凑 JSON（`appThemeJson`）；`CardStyle` 新增 `layoutMode` / `progressStyle` 字段并向后兼容旧版 JSON；`BackupManager` v5 导出/恢复新增可选 `appTheme` 字段，旧备份缺省自动回落至默认值，不影响 MiMo 语音配置与历史 SQLite 数据。
- **Sheet 泄漏修复**：`ScreenActivity.onDestroy()` 统一调用 `Sheet.dismissAllFor(this)`，同时 `MainActivity.onDestroy()` 显式清理 `setupDialog`，消除配置变更（旋转 / 字体缩放 / 主题重建）时的 `WindowLeaked`。
