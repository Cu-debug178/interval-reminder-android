# Interval Reminder for Android 0.7.4

[简体中文文档](README.md)

A native Android app for interval reminders, text/AI vocabulary import, card-by-card proofreading, quick review, and optional AI study tools (DeepSeek / Xiaomi MiMo / custom OpenAI-compatible endpoints).

## How It Works

1. On the home screen, open **Import / Review Draft**. Capture or pick a photo for cloud recognition (Xiaomi MiMo vision recommended), paste a list for AI structuring, use local parsing or manual entry, or open the built-in Lesson 1 set.
2. Choose **Quick confirmation** or **Detailed editing**, then name the group to create a review task.
3. During review, mark **Remembered** or **Forgotten**. Remembered raises mastery; forgotten words go to the Wrong Book and lower mastery.
4. After a round, optionally flip direction without advancing the schedule, or complete the round to advance reminders.
5. Under **Profile > AI**, add one or more configs (a display name, DeepSeek, Xiaomi MiMo, or any OpenAI-compatible base URL, plus an API key and model). A config is listed only after the connection check succeeds, and the page shows the model in use. Keys stay in the device keystore and are masked after saving. AI is off by default. Review tools can then fill examples, analyze mistakes, write scenarios, and recap a round. Sentence practice lives under Profile.

## Review Settings

The default schedule is 5 minutes, 30 minutes, 12 hours, 1 day, 2 days, 4 days, and 7 days. An eighth interval of 14 days is available and disabled by default. Each interval is measured from completion of the previous review session.

- **Default intervals** is collapsed initially. Expand it to edit the eight intervals, which are stored in minutes.
- **Custom intervals** is collapsed initially. Add or remove up to 100 additional rounds, using minutes, hours, or days.
- Quick review can show English first, Chinese first, or a randomized side first. Word order can also be shuffled.
- Home includes a collapsible list of fully completed tasks, filtered by today, the last 7 or 14 local calendar days, or older than 14 days.
- The calendar shows the current next due date and actual completed review rounds, not predictions of later stages.
- The profile page shows a 12-week heatmap of completed review rounds, not answer accuracy. Deleting a task removes its history from the statistics.
- **Wrong Book** lists missed words by task. Choosing **Forgotten** or misspelling a word records another error and the review round; repeated errors in one round increase the count, while that round is listed only once.
- After each review, choose to spell every word in the current task, only its words already in the Wrong Book, or none. The first choice is saved as the default. Change it under **Profile > Review & Spelling**, or enable asking after every round. If there are no wrong-book words to spell, the empty exercise is skipped.
- Under **Review & Spelling**, optionally add the current session's forgotten words to spelling even when the default is none. Another switch opens a skippable, word-by-word example editor after review for forgotten words that still have no example.
- Automatic English speech, confirmation sounds, haptic feedback, and reminder sounds have independent settings. English is spoken only after it is visible; a working English TTS engine is required.
- Choose and preview English voices offered by the system engine and select one of five speech rates (default 0.85x). Availability depends on the engine; some voices require a network, and unavailable voices fall back to the default. Reopen the dialog after changing system speech settings.
- Review feedback uses Kenney CC0 sound effects; preview the **Remembered** and **Forgotten** sounds in settings. The forgotten action has a more distinct haptic response. Haptics and sounds can be disabled separately, and silent mode suppresses audio.
- Audible reminders use system notification sounds, not a continuously ringing alarm. System notification channel, silent/Do Not Disturb, and battery settings can affect delivery and sound.
- Reminder windows use start/end time pickers. Add multiple windows, including windows that cross midnight. Turn off **Only remind during selected windows** to allow reminders at any time.
- On first launch, a setup guide asks when review is convenient, what to do when a review becomes due outside the selected windows, and shows notification-permission status.
- Permission status and system settings entry points are also available in Settings. This build targets SDK 28 and does not require special exact-alarm access. Android 13 and later still require notifications to be enabled in system settings.
- Interval and reminder-window changes apply to new tasks; existing tasks retain their saved snapshots. Sound, speech and interaction settings apply to subsequent interactions.
- Settings are grouped into categories and saved automatically when leaving a category or switching bottom-navigation pages. Returning to the settings directory and exiting the app shows a confirmation that can be suppressed.

## Motion and Accessibility

The UI uses native Android property animations, ripple feedback, and transitions. It does not load animation runtimes from the web.

| Interaction | Behavior |
| --- | --- |
| Buttons | Ripple feedback and a brief press scale: 70 ms down, 110 ms release |
| Page entry and return | Short translation and fade, 160-180 ms |
| Bottom navigation | 80 ms exit, 160 ms entry |
| Card changes | Short horizontal exit followed by entry; repeated taps are guarded |
| Reveal answer | Reveals in reserved layout space without moving the action buttons |
| Expand/collapse and row changes | ChangeBounds and Fade transitions, 170 ms |

These durations are design choices, not scientifically established optimal values. Animations can be disabled in Settings and respect the system animation scale. Primary touch targets are at least 48-52 dp; bottom navigation items are approximately 80 x 60 dp.

References:
- [Android Views animation guide](https://developer.android.com/develop/ui/views/animations/reveal-or-hide-view)
- [Material Motion](https://m3.material.io/styles/motion/overview)
- [Android touch target guidance](https://developer.android.com/guide/topics/ui/accessibility/apps)

## Data and Permissions

The app declares `INTERNET` and only uses the network when AI is enabled and you trigger a request. API keys, tasks, cards, and review history stay in private app storage. Uninstalling or clearing app data removes them.

The APK is signed with a local development key. **Never commit `build/release.keystore` or share the signing key.** Keep the same key to upgrade an existing sideloaded installation; uninstalling the old app is not necessary and would erase private app data.

## Download APK

Android APKs and SHA-256 checksums are available under [GitHub Releases](https://github.com/Cu-debug178/interval-reminder-android/releases); the current app version is 0.7.4. The APK is signed with a local key; do not install it over another build with the same package name but a different signing certificate. Future upgrades must use the same signing key.

## Build Locally

Open the project root, `~/AndroidStudioProjects/IntervalReminder`, in Android Studio. The UI is built programmatically in Java, so the layout editor does not render the complete screens. Start the `IntervalReminder_API_35` virtual device in Device Manager and use Run to inspect the actual UI and interactions.

This machine's Android Studio uses JBR 25, while this project uses Android Gradle Plugin 8.6.1 with Gradle 8.7. In Android Studio, set `Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK` to JDK 21 at `/home/cudebug/.local/share/JetBrains/Toolbox/apps/intellij-idea-2/jbr`. Other development machines should use JDK 17 or 21.

```bash
./gradlew assembleDebug
```

Android Studio Run builds and deploys a debug variant for preview; it is not a release APK. Review the screens and flows on the emulator first, then create, sign, or publish a distributable APK after approval.

The legacy offline build script is retained:

```bash
./build_local.sh
```

The output is `build/interval-reminder-debug.apk`. The script uses Android Studio's JBR, R8/D8, and a local Android tools bundle laid out under `~/Android/local-tools`. It expects the Android 23 platform and the tools bundle's `aapt` and `apksigner` files.

Minimum Android version: 6.0 (API 23). The app currently targets SDK 28 for local sideloading; this should not be taken as evidence that it meets current Google Play publishing requirements. On the tested Waydroid image, hardware rendering can crash SurfaceFlinger during physical-display queries, so software rendering is used only when the device name contains `waydroid`; other devices enable hardware acceleration when creating windows.

The local build script can create a development signing key under the ignored `build/` directory if one is absent. Do not use that development key or its local default password for a production release.

## Tests

- `tests/ScheduleTest.java`: reminder intervals and windows that cross midnight.
- `tests/CoreTest.java`: interval validation, the 42-card lesson, text parsing, shuffling, and bounded retries.
- `tests/native/`: a separate QA package that does not read or overwrite production-app data.

```bash
BUILD_DIR="$PWD/build-qa" MANIFEST_FILE="$PWD/tests/native/AndroidManifest.xml" \
  TEST_JAVA_DIR="$PWD/tests/native/java" ./build_local.sh
adb install --no-incremental -r build-qa/interval-reminder-debug.apk
adb shell am instrument -w com.example.reviewreminder.qa/com.example.reviewreminder.NativeSmokeTest
adb pull /sdcard/Android/data/com.example.reviewreminder.qa/files/qa/. tests/output/
```

Native layout screenshots are drawn at 360 dp and 800 dp widths and written to `tests/output/`. Screenshots do not measure real-device animation frame rates. Camera integration, exact background-notification timing, reboot restoration, battery restrictions, and compatibility across device vendors still require physical-device testing.

## Third-Party Components

See `app/src/main/assets/THIRD_PARTY_NOTICES.txt` and the bundled `licenses/` directory. Lucide icons are exported as Android VectorDrawables from the official npm package; the source icon JSON is retained in `vendor/lucide/icons.json`, and `tools/import_icons.py` can regenerate them.

## License

Project-owned code is available under the [0BSD license](LICENSE), permitting use, modification, and redistribution for any purpose, including commercial use. Third-party components and assets remain subject to their own licenses; user-provided textbook photographs and related textbook content are not covered by this license.
