# 间隔提醒 Android 0.7.3

[English documentation](README.en.md)

原生 Android 应用：间隔提醒、文本/AI 录词、逐卡校对、快速复习，以及可选的 AI 学习助手（DeepSeek / 小米 MiMo / 自定义 OpenAI 兼容端点）。

## 使用流程

1. 主页进入“录词 / 校对草稿”。可拍照/选图走云端识词（推荐小米 MiMo 看图模型），或粘贴词表后用 AI 整理、本地解析、手动添加；也可打开内置 Lesson 1。
2. 生成单词卡后可选择“快速确认”或“逐项编辑”。校对完成后命名词组并建立复习任务。
3. 点击任务的“开始复习”，先回想再显示答案，选择“记得”或“忘记了”。记得会提升掌握度，忘记会记入错题本并降低掌握度。
4. 本轮结束后可换向再刷（不推进阶段），或确认“完成本轮”推进提醒。
5. 在「我的 > AI」配置 Key 后，复习链路可用补例句、错题分析、情景短文与本轮复盘；造句批改在「我的」入口。照片识词用 MiMo；文本整理也可用 DeepSeek。API Key 经本机密钥库加密保存，可一键清除；默认关闭，未配置时不影响离线复习。
6. 任务详情可开「词性义项练习」：正面英文，从多个词性/释义中选择；词卡支持 `senses[]`，旧释义会自动迁移。

## 设置

默认间隔：`5 分钟、30 分钟、12 小时、1 天、2 天、4 天、7 天`，第 8 轮默认 `14 天`，默认关闭。间隔均从上一轮实际完成时间计算。

- “默认间隔”默认折叠，展开后可改动八个间隔，单位为分钟。
- “自定义间隔”默认折叠，可继续追加或删除轮次，支持分钟、小时、天，最多追加 100 轮。
- 快速复习支持英文优先、中文优先、随机正面，以及可选打乱词序。
- 首页显示待复习任务和可折叠的已完成任务，可按今天、近 7 天、近 14 天或 14 天以前筛选；已完成指整个计划完成。
- 日历按选中日期展示当前下一次计划和实际完成的轮次，不预测尚未生成的后续日期。
- “我的”展示近 12 周复习轮次热力图；统计不等于正确率，删除任务也会删除其历史记录。
- “我的”中的错题本按词组列出错词；复习时选择“忘记了”及拼写答错都会自动记录，显示累计错误次数和出错的复习轮次。同一轮的多个错误会增加错误次数，但轮次只列一次。
- 每轮复习结束后可选择拼写本词组全部单词、已收录到错题本的词，或跳过拼写。首次选择会保存为默认方式；可在“我的 > 复习与拼写”调整默认方式，或开启每轮重新询问。没有可拼写的错词时会直接结束，不会打开空白练习。
- “复习与拼写”还可单独开启“额外拼写本轮忘记的单词”，即使默认选择不拼写也会加入本轮错词；也可开启复习后补例句，只为本轮忘记且还没有例句的词逐个填写，未填写的词可以跳过。
- 可分别设置单词自动朗读、复习确认音效、触感和到期通知声音。中文先显示时，英文只在揭晓后朗读；设备须有可用的英文 TTS 引擎。
- “朗读声音与语速”可选择系统提供的英文声音并试听，速度分五档，默认 0.85 倍。声音可用性由系统引擎决定；部分声音需要联网，不可用时回退默认声音。更改系统语音后请重新打开该设置。
- 复习反馈使用 Kenney CC0 短音效，“记得”和“忘记了”可在声音设置中试听；忘记时使用更明显的振动反馈，所有触感与音效均可在设置中关闭，静音模式下不播放声音。
- 到期声音是系统通知声音，不是持续响铃的闹钟。通知渠道、系统静音/免打扰和电池策略可能影响声音或到达时间；可在系统通知设置中进一步调整。
- 提醒时间改为开始/结束时间选择器，可添加多个时段并支持跨午夜；关闭“只在选择的时段内提醒”即全天提醒。
- 首次启动会询问方便复习的时间、时段外到期时的处理方式，并检查通知及精确提醒权限。
- 权限状态也可在设置页查看。当前 target SDK 28 版本不要求精确闹钟特殊授权；Android 13 及以上仍需在系统通知设置中开启通知。
- 间隔和时间窗的修改只影响新建任务，已有任务保留其快照；声音、朗读和交互偏好对之后的操作生效。
- 设置按类别进入详情；离开类别或切换底部页面时自动保存。返回设置目录后再退出应用会显示确认提示，可选择不再提示。

## 动效

使用 Android 原生属性动画、RippleDrawable 和 Transition，不引入网页或网络动画运行时。

| 交互 | 实现 |
| --- | --- |
| 按钮 | 涟漪、轻压缩放，按下 70 ms、回弹 110 ms |
| 页面进入及返回 | 短距离平移和透明度，160-180 ms |
| 底部标签切换 | 内容退出 80 ms、进入 160 ms |
| 词卡切换 | 短距离水平退出、下一卡进入，防止重复点击 |
| 揭晓答案 | 预留布局中的原位显现，操作按钮不被挤动 |
| 折叠间隔及增删行 | ChangeBounds 和 Fade 同时运行，170 ms |

这些时间是本应用的设计取值，不是所谓“科学最佳速度”。应用内“界面动态效果”可关闭上述动画，也尊重系统动画时长缩放为零。主要操作目标至少 48-52 dp，底部导航每项约 80 x 60 dp。

参考：
- Android Views 动画指南：https://developer.android.com/develop/ui/views/animations/reveal-or-hide-view
- Material Motion：https://m3.material.io/styles/motion/overview
- Android 触控目标：https://developer.android.com/guide/topics/ui/accessibility/apps

## 数据与权限

应用声明 `INTERNET` 权限，仅在你开启 AI 并主动触发请求时联网。API Key、任务、词卡及复习记录保存在应用私有目录，卸载或清除应用数据会删除它们。签名沿用 `build/release.keystore`，升级时不要卸载旧应用。

## 下载 APK

Android APK 和 SHA-256 校验值见 [GitHub Releases](https://github.com/Cu-debug178/interval-reminder-android/releases)，当前应用版本为 0.7.3。APK 使用本地签名密钥签名；请勿将它与其他来源的同包名应用混装，后续升级需使用相同签名密钥。

## 本机构建

建议在 Android Studio 中打开项目根目录 `~/AndroidStudioProjects/IntervalReminder`。本项目使用 Java 代码构建界面，布局预览窗口不会渲染完整页面；请在 Device Manager 中启动 `IntervalReminder_API_35` 模拟器并使用 Run 查看真实界面和交互。

本机 Android Studio 使用 JBR 25，而当前 Android Gradle Plugin 8.6.1 配套 Gradle 8.7 需要较旧的运行时。本机可在 Android Studio 的 `Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK` 中选择 JDK 21：`/home/cudebug/.local/share/JetBrains/Toolbox/apps/intellij-idea-2/jbr`。其他开发机选择 JDK 17 或 21。

```bash
./gradlew assembleDebug
```

Android Studio 的 Run 会构建并部署调试版本以供预览；这不等于生成发布 APK。先检查模拟器中的页面和流程，得到确认后再单独制作、签名或发布 APK。

兼容旧离线环境的脚本仍保留：

```bash
./build_local.sh
```

产物：`build/interval-reminder-debug.apk`。脚本使用本机 Android Studio JBR、R8/D8，以及 `~/Android/local-tools` 中的 API 23/aapt/apksigner。

最低 Android 6.0，当前 target SDK 28，为本机离线构建和侧载使用，不代表符合 Google Play 当前发布要求。这个 Waydroid 系统镜像的硬件渲染会触发 SurfaceFlinger 物理显示查询崩溃，因此仅对设备名含 `waydroid` 的运行环境保留软件渲染；其他设备在窗口创建时开启硬件加速。

## 测试

- `tests/ScheduleTest.java`：提醒间隔与跨午夜时间窗。
- `tests/CoreTest.java`：间隔验证、42 张卡、文本解析、打乱与有限次数重试。
- `tests/native/`：独立 QA 包，不读取或覆盖正式应用的数据。

```bash
BUILD_DIR="$PWD/build-qa" MANIFEST_FILE="$PWD/tests/native/AndroidManifest.xml" \
  TEST_JAVA_DIR="$PWD/tests/native/java" ./build_local.sh
adb install --no-incremental -r build-qa/interval-reminder-debug.apk
adb shell am instrument -w com.example.reviewreminder.qa/com.example.reviewreminder.NativeSmokeTest
adb pull /sdcard/Android/data/com.example.reviewreminder.qa/files/qa/. tests/output/
```

原生布局截图以 360 dp、800 dp 宽度绘制，保存在 `tests/output`。截图不等于真实手机的动画帧率测试。真实手机相机调用、后台通知准点到达、重启后通知恢复、电池限制以及不同厂商系统兼容性需要后续实机验证。

## 第三方组件

见 `app/src/main/assets/THIRD_PARTY_NOTICES.txt` 和 `licenses/`。Lucide 图标从本机已有的官方 npm 包导出为 Android VectorDrawable，保留几何形状；`tools/import_icons.py` 可从随项目保留的 JSON 重新生成。

## 许可证

项目自有代码采用 [0BSD 许可证](LICENSE)，允许免费或商业使用、修改和再分发。第三方组件及素材仍遵守其各自的许可证；用户提供的教材照片及相关教材内容不属于此许可证的授权范围。
