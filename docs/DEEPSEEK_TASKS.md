# 可交给 DeepSeek 的独立后续任务

> 目的：拆分机械、独立、容易验收的工作。核心拼写、语音、混合训练、AI 翻译和计划书架由主任务实现；不要在未同步最新代码前重写这些核心文件。
>
> 状态（2026-11-19）：任务 A/B/C/D 已由主任务实现并完成设备验收。本清单保留为任务拆分记录；实际结果见 `docs/deepseek-qa-report.md`，复现命令见 `docs/qa-environment.md`。

## 使用方式

1. 先获取主任务完成后的最新仓库，再逐项实施，每项独立修改、独立测试。
2. 遵循 `AGENTS.md`、`docs/ui-ux-redesign.md`；使用 `Theme`、`WordUi`、`Motion`、`Feedback`，不要引入新主题或大型依赖。
3. 每项完成后运行 `./gradlew assembleDebug` 和 `tests/CoreTest.java`；UI 项还需在 `IntervalReminder_API_35` 截图。
4. 不生成发布 APK，不写入真实 API Key，不批量下载图片或录音。

## 任务 A：连接图轻量展示与手绘（P2，独立）

- [x] 为单词 JSON 增加可选 `connectionImage`（应用私有目录相对路径），旧数据无字段时完全不占空白区域。
- [x] 在词卡编辑页提供“添加连接图 / 手绘 / 删除”，使用 SAF 或简单 Canvas 保存 PNG。
- [x] 复习卡只有存在有效图片时显示“查看连接图”次级按钮，点击打开全屏可缩放查看页。
- [x] 图片加载失败显示 Toast 并回退普通卡片，不影响复习提交。
- [x] 验收：无图、损坏路径、超长单词、横竖屏切换均可复习；所有按钮有 `Motion.press`。

建议新增 `ConnectionImageActivity.java`、`DrawingView.java`，避免改动语音与 AI 网络代码。

## 任务 B：本地 JSON 备份增强（P2，独立）

- [x] 设计版本化导出 JSON：version、exportedAt、tasks、wrongStates、spellingSessions、translationExercises、voiceProfiles（不含任何 Key）。
- [x] 参考音频默认不导出；用户明确勾选时才单独导出，并提示隐私与授权。
- [x] 恢复先解析到临时对象并校验，再让用户选择“合并 / 替换”；替换必须二次确认，失败不得清空现有数据。
- [x] 稳定单词 ID 冲突时合并历史，不重复叠加同 eventId 的 Attempt。
- [x] 密钥字段（apiKey、apiKeyEnc、Authorization 等）即使出现在导入文件中也必须忽略。

建议新增 `BackupManager.java`、`BackupActivity.java` 和纯解析测试，避免修改 MiMo 请求实现。

## 任务 C：补充机械化边界测试（可立即做）

- [x] `SpellingSession`：空队列、单词、100 词、错误后最小间隔、快照损坏时安全失败。
- [x] 选择快照：同词同义跨组去重、同词不同义保留、随机前后 ID 集合一致。
- [x] WrongWordState：旧 wrongCount 迁移后未掌握；已掌握再次错误归零；eventId 重放不重复计数。
- [x] AI JSON：代码块、首尾解释、缺字段、空 items、超长 items 的容错。
- [x] 语音缓存键：音色、文本、语言、语速、样本版本任一变化均不得命中旧缓存。

测试不得发起真实网络请求；使用本地假数据和假响应。

## 任务 D：无障碍与小屏回归（机械检查）

- [x] 所有图标按钮补 `contentDescription`，触摸目标至少 48dp。
- [x] 360dp 宽、系统字体 1.3 倍时检查首页、混合设置、计划详情、音色管理、AI 翻译。
- [x] 验证系统动画时长为 0 时无卡死、无重复提交。
- [x] 长计划名、长英文、长中文、空例句、无历史、无错词均无截断关键操作。
- [x] 将截图和结果写入 `docs/deepseek-qa-report.md`，只报告实际执行项。
