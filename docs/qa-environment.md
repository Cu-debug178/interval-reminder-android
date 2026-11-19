# QA 测试环境与复现命令

日期：2026-11-19  
适用范围：Windows 本机、`IntervalReminder_API_35` 模拟器、Debug APK 与 `P2SmokeTest`。

## 1. 工具位置

| 工具 | 路径 |
| --- | --- |
| Android SDK | `C:\Android\Sdk` |
| adb | `C:\Android\Sdk\platform-tools\adb.exe` |
| emulator | `C:\Android\Sdk\emulator\emulator.exe` |
| build-tools | `C:\Android\Sdk\build-tools\35.0.0` |
| JDK | `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot` |
| AVD | `C:\Users\LEGION\.android\avd\IntervalReminder_API_35.avd` |
| Debug APK | `app\build\outputs\apk\debug\app-debug.apk` |
| QA APK | `build-qa-win\qa.apk` |

模拟器为 Pixel 6、API 35 Google APIs、x86_64，AEHD 服务 `aehd` 处于 RUNNING。

## 2. 启动与设备参数

```powershell
Get-Service aehd | Select-Object Status,Name
& 'C:\Android\Sdk\emulator\emulator.exe' -avd IntervalReminder_API_35
& 'C:\Android\Sdk\platform-tools\adb.exe' devices -l
```

360dp 验收配置：

```powershell
$adb='C:\Android\Sdk\platform-tools\adb.exe'
& $adb -e shell wm size 1080x2280
& $adb -e shell wm density 480
& $adb -e shell settings put global animator_duration_scale 0
& $adb -e shell settings put global transition_animation_scale 0
& $adb -e shell settings put global window_animation_scale 0
```

字体倍率：

```powershell
& $adb -e shell settings put system font_scale 1.3   # 放大验收
& $adb -e shell settings put system font_scale 1.0   # 恢复默认
```

横屏验收：

```powershell
& $adb -e shell settings put system accelerometer_rotation 0
& $adb -e shell settings put system user_rotation 1   # 横屏
& $adb -e shell settings put system user_rotation 0   # 恢复竖屏
```

## 3. JVM 回归

```powershell
$root='C:\cu\interval-reminder-android'
$out=Join-Path $root 'build-core-win'
New-Item -ItemType Directory -Force $out | Out-Null
$jdk='C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\javac.exe'
& $jdk -encoding UTF-8 -d $out `
  "$root\app\src\main\java\com\example\reviewreminder\IntervalPlan.java" `
  "$root\app\src\main\java\com\example\reviewreminder\WordParser.java" `
  "$root\app\src\main\java\com\example\reviewreminder\ReviewSession.java" `
  "$root\app\src\main\java\com\example\reviewreminder\SpellingPlan.java" `
  "$root\app\src\main\java\com\example\reviewreminder\SpellingSession.java" `
  "$root\app\src\main\java\com\example\reviewreminder\Base64Compat.java" `
  "$root\app\src\main\java\com\example\reviewreminder\Schedule.java" `
  "$root\tests\CoreTest.java" "$root\tests\ScheduleTest.java"
& 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe' -cp $out CoreTest
& 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin\java.exe' -cp $out ScheduleTest
```

期望：`CoreTest: 98 checks passed`、`ScheduleTest: 8 checks passed`。

## 4. Debug 构建与安装

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
& 'C:\cu\interval-reminder-android\gradlew.bat' -p 'C:\cu\interval-reminder-android' assembleDebug --console=plain
& 'C:\Android\Sdk\platform-tools\adb.exe' -e install -r 'C:\cu\interval-reminder-android\app\build\outputs\apk\debug\app-debug.apk'
```

启动主界面：

```powershell
& 'C:\Android\Sdk\platform-tools\adb.exe' -e shell monkey -p com.example.reviewreminder -c android.intent.category.LAUNCHER 1
```

## 5. QA APK 构建与运行

QA APK 已脚本化，等价于手工执行 `aapt2 compile/link → javac --release 8 → d8 → classes.dex 回填 → zipalign → apksigner`：

```powershell
& 'C:\cu\interval-reminder-android\tools\build-qa.ps1'
```

然后安装并运行唯一注册的设备套件：

```powershell
$adb='C:\Android\Sdk\platform-tools\adb.exe'
& $adb -e install -r 'C:\cu\interval-reminder-android\build-qa-win\qa.apk'
& $adb -e shell am instrument -w -r com.example.reviewreminder.qa/com.example.reviewreminder.P2SmokeTest
```

期望：58 行 `PASS`、`ALL PASSED`、`INSTRUMENTATION_CODE: -1`。测试日志在：

```text
/sdcard/Android/data/com.example.reviewreminder.qa/files/qa/p2-results.txt
```

`NativeSmokeTest` 是 0.6 之前的旧 UI 套件，断言已与当前 `SpeechManager`、练习模式行为不一致；它保留在源码中作历史参考，但已从 `tests/native/AndroidManifest.xml` 注销，不再参与 `am instrument`。

## 6. 测试夹具与入口限制

- 首次设置向导每次冷启动可能出现；跳过坐标在 1080x2280 下约为 `(650,1713)`（1.0 字体）或 `(580,1820)`（1.3 字体）。
- 非导出 Activity 不能用 `adb am start -n`，会报 `Permission Denial ... not exported`；必须从应用 UI 入口进入。
- 写应用私有目录使用 `run-as`，先确保 `files` 目录存在：

```powershell
& $adb -e shell run-as com.example.reviewreminder mkdir -p files
& $adb -e shell run-as com.example.reviewreminder cp /data/local/tmp/draft.json files/word-draft.json
```

- 长内容草稿夹具：`docs\qa-screens\draft-long.json`；3 词连接图草稿：`docs\qa-screens\draft.json`。
- 连接图私有目录：`files/connection-images`。
- 参考音频目录：`files/voices`。
- SharedPreferences 名称：`review_data`。

## 7. 网络与付费边界

- `P2SmokeTest` 不发起真实 AI/语音网络请求，全部使用本地夹具和假响应。
- 真实 DeepSeek / MiMo / 参考音频验收未执行；需要可用 Key 放在 `getExternalFilesDir()/interval-ai-keys.env`，并显式触发请求，否则不产生费用。
- 备份导出文件默认不含 API Key；参考音频默认不导出，连接图默认包含。
- 不生成 Release APK；UI 改动需先在模拟器截图审阅。
