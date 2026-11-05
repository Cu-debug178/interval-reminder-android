# 间隔提醒 Android 0.4.1

[English documentation](README.en.md)

离线原生 Android 应用，保留通用任务提醒，并新增照片录词、逐卡校对和快速复习。

## 使用流程

1. 主页进入“照片录词 / 校对草稿”。拍照或选图后，可旋转、裁切并离线识别。
2. 生成单词卡后可选择“快速确认”或“逐项编辑”。快速确认逐张展示正常词卡；正确时点“没问题”进入下一张，有问题点“有问题，修改”，保存后回到该卡。逐项编辑保留原来的直接编辑、上一张/下一张校对方式。英文、中文释义和例句可分别修改，没有例句时保持空白。
3. 校对完成后命名词组并建立独立的复习任务。离开编辑页时会保存草稿。
4. 点击任务的“开始复习”，先回想再显示答案，选择“记得”或“再看一次”。错词隔几张再次出现，每张每轮最多出现三次，避免无限循环。
5. 全部看完后确认“完成本轮”，才推进提醒阶段；单次点击答案不会推进。

“打开 Lesson 1 待校对词组”提供从用户两页照片人工整理的 42 张卡，**不是 OCR 自动识别准确率的展示**。快速确认缩短逐卡校对操作，但不代表 OCR 结果已被证明无误；原照片可在校对时查看。首版不自动生成释义、例句或音标。

## 设置

默认间隔：`5 分钟、30 分钟、12 小时、1 天、2 天、4 天、7 天`，第 8 轮默认 `14 天`，默认关闭。间隔均从上一轮实际完成时间计算。

- “默认间隔”默认折叠，展开后可改动八个间隔，单位为分钟。
- “自定义间隔”默认折叠，可继续追加或删除轮次，支持分钟、小时、天，最多追加 100 轮。
- 快速复习支持英文优先、中文优先、随机正面，以及可选打乱词序。
- 首页显示待复习任务和可折叠的已完成任务，可按今天、近 7 天、近 14 天或 14 天以前筛选；已完成指整个计划完成。
- 日历按选中日期展示当前下一次计划和实际完成的轮次，不预测尚未生成的后续日期。
- “我的”展示近 12 周复习轮次热力图；统计不等于正确率，删除任务也会删除其历史记录。
- 可分别设置单词自动朗读、复习确认音效、触感和到期通知声音。中文先显示时，英文只在揭晓后朗读；设备须有可用的英文 TTS 引擎。
- 复习反馈使用 Kenney CC0 短音效，“记得”和“再看一次”可在声音设置中试听；静音模式下不播放。
- 到期声音是系统通知声音，不是持续响铃的闹钟。通知渠道、系统静音/免打扰和电池策略可能影响声音或到达时间；可在系统通知设置中进一步调整。
- 提醒时间改为开始/结束时间选择器，可添加多个时段并支持跨午夜；关闭“只在选择的时段内提醒”即全天提醒。
- 首次启动会询问方便复习的时间、时段外到期时的处理方式，并检查通知及精确提醒权限。
- 权限状态也可在设置页查看。当前 target SDK 28 版本不要求精确闹钟特殊授权；Android 13 及以上仍需在系统通知设置中开启通知。
- 间隔和时间窗的修改只影响新建任务，已有任务保留其快照；声音、朗读和交互偏好对之后的操作生效。
- 设置页切换标签或返回时，未保存修改会弹出保存确认。

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

正式 APK 不声明 INTERNET 权限。OCR、照片、任务、词卡及复习记录保存在应用私有目录，卸载或清除应用数据会删除它们。首版尚无数据导出功能。签名沿用 `build/release.keystore`，升级时不要卸载旧应用。

原生 OCR：Tesseract4Android 4.9.0，内置英语及简体中文模型。复杂排版、手写、音标和低清照片可能产生大量错字；裁切与逐卡校对不能省略。首次识别会复制模型到私有目录。

## 下载 APK

Android APK 和 SHA-256 校验值见 [GitHub Releases](https://github.com/Cu-debug178/interval-reminder-android/releases)，当前应用版本为 0.4.1。APK 使用本地签名密钥签名；请勿将它与其他来源的同包名应用混装，后续升级需使用相同签名密钥。

## 本机构建

```bash
./build_local.sh
```

产物：`build/interval-reminder-debug.apk`。脚本使用本机 Android Studio JBR、R8/D8，以及 `~/Android/local-tools` 中的 API 23/aapt/apksigner。也保留 Gradle 工程定义，但当前环境未验证在线 Gradle 构建。

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
