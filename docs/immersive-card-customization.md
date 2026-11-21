# 沉浸式复习卡片自定义设计说明

> 延续「纸与墨 · Paper & Ink」（[design-language.md](design-language.md)）克制气质，为 `ReviewActivity` 全屏背词卡增加离线外观与底部装饰定制，同时 100% 保留现有手势、离线体验与数据兼容。

## 1. 现状核查

- **卡片结构**（`ReviewActivity.java`）：无按钮全屏白卡，核心内容为正面词头、发丝分隔线、背面释义、可点朗读例句、类别标签、掌握度圆点与相关词入口。
- **手势契约（必须保留）**：轻点翻面 → 再次轻点标「记得」；下滑（翻面前后均可）标「忘记」；长按编辑单词卡；点例句朗读；点相关词打开关系图。
- **渲染约束**：`AndroidManifest.xml` 中设置了 `android:hardwareAccelerated="false"`。软件渲染下 `View.setClipToOutline(true)` 不会裁剪圆角内的自定义绘制，因此背景图片、渐变、遮罩与底纹必须由自定义容器在 `Canvas.clipPath(roundRectPath)` 内绘制。
- **存储与备份**：轻量设置存在 `LocalStore` 的 `pref` 表（`Store.prefs` 门面）；私有图片按 `ConnectionImages` 的原子临时文件模式存于 `files/` 私有目录；`BackupManager` 负责版本化 JSON 导出与事务性恢复，不携带任何密钥。

## 2. 数据模型（`CardStyle`）

纯 Java、无 Android 依赖，便于 JVM 单元测试与严格校验。序列化为小体积 JSON 字符串存于 `Store.prefs` 的 `cardStyle` 键，**绝不把图片 Base64 写入 SharedPreferences**。

| 字段 | 可选值 / 约束 | 默认值 |
|---|---|---|
| `backgroundMode` | `default` / `solid` / `gradient` / `image` | `default` |
| `backgroundPreset` | 克制纯色（暖麻纸、浅松雾、素石灰、暮青石、夜墨纸）或克制渐变（晨纸、松岚、暖砂、深林夜、暮色墨） | `""` |
| `backgroundImage` | 仅存私有文件名（如 `bg_<uuid>.jpg`，拒绝路径穿越） | `""` |
| `imageCrop` | `center` / `top` / `bottom`（等比 center-crop + 垂直锚点） | `center` |
| `overlayAlpha` | 单个可读性遮罩控制：`0`–`80`（百分比，暗色墨底遮罩） | `45` |
| `bottomMode` | `none` / `quote` / `pattern` / `image` | `none` |
| `quoteText` | 可编辑短句（≤ 120 字），内置 5 条原创学习短句（无虚构名人归属） | `""` |
| `quoteCredit` | 可编辑署名/备注（≤ 36 字，默认留空） | `""` |
| `bottomPattern` | `dots`（墨点）/ `linen`（纸格）/ `waves`（远山）/ `botanical`（松枝） | `dots` |
| `bottomImage` | 仅存私有文件名（如 `bt_<uuid>.jpg`） | `""` |

## 3. 可读性、暗色模式与响应式布局

- **暗色模式**：检测系统 `UI_MODE_NIGHT_YES`，`default` 背景自动切换为暖墨深面（`#1E2422`）及浅色纸墨文字；深色纯色/渐变或图片模式（暗色可读遮罩 + 浅色正文）统一由 `CardSurfaceView.Palette` 计算主文、次文、发丝线、标签、掌握度与相关词颜色，保证高对比度。
- **长文本 / 小屏 / 横屏 / 大字号**：
  - 卡片最小高度按可视窗口高度动态收紧（横屏或矮屏不再强撑 `420dp`），内边距与底部装饰区自动紧凑。
  - 词头、释义、例句优先占用中央空间并按字符长度自动阶梯缩字；系统大字号（`fontScale >= 1.25`）或横屏矮屏时，底部短句限行截断、底部纹样/图片压缩高度，确保不遮挡释义且不破坏下滑/轻点手势。
  - 卡片内部不嵌套垂直 `ScrollView`，避免与下拉「忘记」手势冲突。

## 4. 离线私有图片、选择取消与备份兼容

- **零宽泛存储权限**：选图优先使用系统照片选择器（API 33+ `MediaStore.ACTION_PICK_IMAGES`，低版本回退 `Intent.ACTION_OPEN_DOCUMENT` + `image/*`），不申请 `READ_EXTERNAL_STORAGE` / `READ_MEDIA_IMAGES`。
- **下采样与方向修正**：`CardStyleImages` 先读尺寸与 EXIF 旋转，按 `inSampleSize` 下采样（背景最大边 `1440px`，底部图 `960px`），纠正方向后以 JPEG 84 质量原子写入 `files/card-style/`。
- **取消、移除与清理**：系统选图取消时保持原草稿与原图不变；移除图片或取消未保存草稿时安全清理未引用临时文件；保存或重置后自动回收孤立旧图。缺失或损坏的私有图片自动降级为默认背景 / 无底部装饰，不阻塞复习。
- **备份与数据库兼容**：`BackupManager` 升级至 `VERSION = 5`（向下兼容 v1–v5），导出 `cardStyle` 配置；勾选包含图片时一并附带 `cardStyleImages`（仅存于备份 JSON 文件中，事务提交前原子落盘、失败回滚）。不含图片的备份恢复时若本机无对应图片文件，自动安全回退。

## 5. 设置入口与交互流程

- 入口：`我的 → 复习与拼写 → 复习卡片外观` 及 `复习页右上角 ⋯ → 卡片外观`。
- `CardStyleActivity` 顶部提供实时交互预览（支持切换「正面 / 背面 / 长词 / 暗色预览」），下方分区配置背景与底部区域，支持 **预览 / 保存 / 取消 / 恢复默认**，所有按钮与选块统一使用 `WordUi` / `Sheet` + `Motion.press`。
