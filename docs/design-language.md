# 纸与墨 · Paper & Ink — 设计语言

> v0.7 起的统一视觉与交互规范。代码唯一来源：`Theme.java`（令牌）、`WordUi.java`（组件）、`Sheet.java`（底部面板）、`Motion.java`（动效）、`Notice.java`（结果提示）、`Segmented` / `StageDots` / `ProgressLine`（小部件）。

## 1. 气质

一本被认真排版的单词本：暖白纸面、近黑墨色、唯一的松绿强调色。层次来自 **字重、字号、留白**，而不是边框、阴影或色块。安静、可信、每天打开不疲劳。

- 每屏只有 **一个** 最突出的主操作（实心松绿）。其它操作降级为 secondary（浅灰填充）、tonal（浅绿）、文字按钮或收进 ⋯ 菜单。
- 不用粗体（bold）。标题用 `Theme.MEDIUM`（500），正文 `Theme.REGULAR`。中文粗体是“粗糙感”的最大来源。
- 英文词头用衬线 `Theme.SERIF`，像词典；释义、界面文字用无衬线。
- 卡片无描边无阴影：白色面放在纸色背景上，自身对比就足够。
- 图标只在有意义处出现（行首类别图标、朗读、相机、⋯、›）。不给每个按钮配图标。

## 2. 令牌

| 角色 | 值 | 用途 |
|---|---|---|
| `BG` paper | `#F6F5F1` | 页面背景 |
| `SURFACE` | `#FFFFFF` | 卡片、分组列表、底部面板 |
| `SUNK` | `#EFEEE9` | 次要按钮、分段控件轨道 |
| `HAIRLINE` | `#E6E4DE` | 分隔线、输入框描边 |
| `INK` / `INK_2` / `INK_3` | `#1B2220` / `#59625E` / `#959B97` | 主文 / 次文 / 辅助与占位 |
| `PRIMARY` pine | `#1F5A4C` | 唯一强调色、主按钮、选中 |
| `PRIMARY_SOFT` | `#E4EEE9` | tonal 按钮、选中底 |
| `DANGER` terracotta | `#B0503A` (+`DANGER_SOFT`) | 删除、已逾期 |
| `WARN` | `#8A5F17` (+`WARN_SOFT`) | 权限缺失提示 |

字号（sp）：Display 30 · Title 24 · Headline 17 · Body 15 · Callout 14 · Caption 12。用 `ui.type(text, Theme.T_*)` 或 `Theme.style(tv, role)`。

间距：4 的倍数；页面左右 20；分组之间 24；行内 12–16。行高 ≥ 56dp；触控目标 ≥ 48dp。

圆角：卡片 18 · 分组 16 · 按钮 14 · 输入框 12 · chip 10 · 面板 24（仅上方）。

## 3. 组件（`WordUi`）

| 组件 | 说明 |
|---|---|
| `start(title)` / `startModal(title)` | 顶栏：返回 ← 或关闭 ✕、标题（Headline）、可选尾部图标（如 ⋯）。内容滚动时顶栏下出现发丝线。 |
| `pin(...)` / `pinPair(secondary, primary)` | 页面底部固定操作区，主操作始终可达。 |
| `largeTitle(parent, title, subtitle)` | 一级页面的大标题（Display 30）。 |
| `section(parent, label, trailing)` | 分组标题（Callout 14 · INK_2）+ 右侧说明。 |
| `group()` + `item(...)` | iOS 式内嵌分组列表；行自带发丝分隔、涟漪、按压缩放与 ›。 |
| `switchRow(group, label, detail, checked)` | 开关行；Switch 自身持有文字（两行：标签 + 说明）。 |
| `primaryButton` / `secondaryButton` / `tonalButton` / `ghostButton` / `dangerButton` | 52dp 高，Medium 16sp。图标可选，与文字一同居中。 |
| `textButton` / `dangerTextButton` / `pill` / `quietPill` | 行内与次级操作。 |
| `iconButton(res, desc, click)` | 48dp 无边界圆形涟漪。 |
| `chip(text, fill, fg)` | 小状态标签。 |
| `banner(icon, msg, warning, click)` | 需要处理的状态（权限未开启等）。 |
| `empty(icon, title, detail, action, run)` | 空状态。 |
| `segmented(labels, selected, onPick)` | 2–4 个短选项，取代 Spinner。 |
| `field(label, value, lines)` / `input(hint, value)` | 白底发丝描边，聚焦时松绿描边。 |
| `toast(msg)` / `error(msg)` | 走 `Notice`：墨色胶囊从底部浮起；Activity 正在结束时自动退回系统 Toast。 |

`Sheet` 取代全部 `AlertDialog`：`title` · `message` · `content(view)` · `item(icon,label,run)` · `choices(...)` · `primary/primaryIf/secondary/danger` · `Sheet.confirm(...)` · `Sheet.pick(...)`。

`StageDots(total, done, overdue)`：一排小圆点表示间隔计划进度（实心=已完成、圆环=下一轮、浅色=未到）。也用于 5 级掌握度。
`ProgressLine`：3dp 进度条，数值变化时缓动。

## 4. 交互与反馈

- **按压**：所有可点控件 `Motion.press`（涟漪 + 3% 缩放，按下 90ms、回弹 210ms）。组件 helper 已内置。
- **页面**：Activity 之间横向 6% 位移 + 淡入（260ms）；底部导航切页内容横向滑动；首次渲染子项 28ms 依次浮起（`Motion.stagger`，最多 10 项）。
- **展开/收起**：`Motion.layout(parent)` + chevron `Motion.rotate`。
- **面板**：从底部滑入 280ms，背景 32% 墨色遮罩。
- **触感**：分段/开关切换 `Feedback.tick`（仅触感）。复习“记得/忘记了/完成本轮”用 `Feedback.respond/confirm`（触感 + 可选音效）。导航不发声。
- **结果**：保存成功、失败、已安排时间都必须用 `ui.toast/ui.error` 显示出来。
- 应用内“界面动态效果”开关或系统“动画时长 0”时，以上动效全部关闭，状态立即呈现。

## 5. 文案

简短、肯定、具体。按钮用动词（“开始复习”“保存”）。说明文字放在分组下方的脚注里，不放在控件旁边抢视线。
