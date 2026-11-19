# 间隔提醒功能升级验证记录

日期：2026-11-18

> 历史记录：本文对应 P0/P1 阶段的 2026-11-18 构建。2026-11-19 的 P2 连接图、版本化备份、360dp/1.3 字体与模拟器验收见 `docs/deepseek-qa-report.md`；测试环境与复现命令见 `docs/qa-environment.md`。

## 本轮实现范围

- 全量拼写队列：普通词独立正确一次；错词连续独立正确两次；提示、答错、跳过、查看答案后继续重练；支持快照恢复。
- 全局错词状态：以稳定单词 ID 合并不同计划中的同词同义，保存 Attempt、连续正确次数和已掌握状态；旧错词历史保留。
- 统一朗读：系统英文 TTS、MiMo 预置音色、授权参考音频克隆音色；包含缓存、取消、生命周期失效和失败回退。
- 多组混合训练：多选计划、全部/错词/未掌握筛选、原顺序/随机、卡片复习/全量拼写/错词强化。
- 计划书架与详情：状态筛选、单词目录、复习记录、掌握度、归档和自由学习入口。
- AI 翻译：英译中/中译英、题量和难度、节省模式自评、按需 AI 批改、历史与再次练习。
- 页面沿用现有 Theme、WordUi、Motion、Feedback 和紧凑卡片/按钮风格。

## 已执行的验证

### JVM 回归

- `CoreTest: 98 checks passed`
- `ScheduleTest: 8 checks passed`
- 覆盖普通/错词完成次数、辅助答案不计成功、错误后重排、10 词全量完成、已掌握错词、快照恢复和坏快照拒绝。

### Android 构建

- `gradlew.bat assembleDebug`：成功。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`
- 大小：658666 bytes
- SHA-256：`88DA4DB33E2F61288EC278BAAD41245CD944E0BC11C7EF3438BE5DD86034D4D7`

### 静态检查

- `git diff --check`：无空白错误，仅有既存 LF/CRLF 提示。
- 密钥模式搜索：未发现硬编码 Key。
- `gradlew.bat lintDebug`：**未通过**，整体为 42 errors / 17 warnings。
- 首个存量错误是 `MainActivity.java:92` 的动态 View ID；其余主要包括旧代码 WrongConstant、target SDK 28 等。
- 本轮新增的 `Base64Compat`、拼写会话、语音、混合训练、计划书架、翻译等独立类在最终 lint 报告中没有 Error/Warning 条目。
- 未创建 lint baseline，未用忽略规则掩盖存量问题。

## 尚未验证 / 阻塞项

1. **模拟器与真实 UI 未验收。** `adb devices -l` 没有连接设备；`C:\Android\Sdk` 未找到 `emulator.exe` 或系统镜像，因此无法部署到 `IntervalReminder_API_35`，也没有首页、拼写、混合训练、音色管理、计划书架和翻译页截图。
2. **真实 MiMo/AI 请求未执行。** 未使用用户 Key，未上传参考音频，未产生云端调用费用；实际音色、克隆效果、配额、网络回退与真实 AI 出题/批改仍需设备测试。
3. **旧数据升级与原生 QA 套件未在 Android 运行时执行。** 迁移与恢复已有代码和 JVM 纯逻辑证据，但 `NativeSmokeTest` / `NativeUiQaTest` 未在设备运行。
4. **小屏、超长文本、系统字体放大、减少动画与完整触控流程未验收。**
5. P2 连接图、版本化备份增强等不在本轮完成范围，后续任务见 `docs/DEEPSEEK_TASKS.md`。

## 预览入口

- 首页：`混合训练`、`全部计划`。
- 我的：`AI 翻译训练`、`错题本`、`声音与触感 > 朗读声音与语速`。
- 计划详情：卡片复习、随机复习、全量拼写、AI 翻译、校对、归档。
- 错词详情：查看 Attempt、强化、重新标为未掌握。

准备好 API 35 模拟器后可执行：

```powershell
C:\Android\Sdk\platform-tools\adb.exe install -r C:\cu\interval-reminder-android\app\build\outputs\apk\debug\app-debug.apk
```

未生成或发布 Release APK；需先完成真实页面审阅。
