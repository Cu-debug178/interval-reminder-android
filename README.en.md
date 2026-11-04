# Interval Reminder for Android 0.3.1

[简体中文文档](README.md)

An offline, native Android app for interval-based task reminders, photo-based vocabulary entry, card-by-card proofreading, and quick review.

## How It Works

1. On the home screen, open **Photo Import / Review Draft**. Take a photo or choose an image, then rotate, crop, and run offline OCR.
2. Choose **Quick confirmation** or **Detailed editing** after creating the cards. Quick confirmation presents one normal vocabulary card at a time: confirm a correct card to continue, or choose **Has an issue** to edit it and return to that card after saving. Detailed editing retains the original direct-edit and previous/next workflow. English, Chinese definitions, and examples can be edited independently; examples may be blank.
3. Name the group after proofreading to create a separate review task. Drafts are saved when leaving the editor.
4. Start a review session, recall the answer before revealing it, then mark the card **Remembered** or **Review again**. Missed cards return after a few cards, with at most three appearances per card in one session.
5. Confirm **Complete this session** after reviewing all cards to advance the task to its next reminder stage. Revealing an answer alone does not advance the schedule.

The built-in **Lesson 1** draft contains 42 cards manually transcribed from two user-provided photos. It is not an OCR accuracy demonstration. Quick confirmation reduces the number of editing steps; it does not establish that OCR output is error-free. Original photos are available during proofreading. The initial version does not generate definitions, examples, or phonetic transcriptions automatically.

## Review Settings

The default schedule is 5 minutes, 30 minutes, 12 hours, 1 day, 2 days, 4 days, and 7 days. An eighth interval of 14 days is available and disabled by default. Each interval is measured from completion of the previous review session.

- **Default intervals** is collapsed initially. Expand it to edit the eight intervals, which are stored in minutes.
- **Custom intervals** is collapsed initially. Add or remove up to 100 additional rounds, using minutes, hours, or days.
- Quick review can show English first, Chinese first, or a randomized side first. Word order can also be shuffled.
- Reminder windows use start/end time pickers. Add multiple windows, including windows that cross midnight. Turn off **Only remind during selected windows** to allow reminders at any time.
- On first launch, a setup guide asks when review is convenient, what to do when a review becomes due outside the selected windows, and shows notification-permission status.
- Permission status and system settings entry points are also available in Settings. This build targets SDK 28 and does not require special exact-alarm access. Android 13 and later still require notifications to be enabled in system settings.
- Settings changes apply to newly created tasks. Existing tasks retain their saved schedule and reminder-window snapshot.
- Leaving Settings with unsaved changes prompts for confirmation.

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

The release manifest does not request the `INTERNET` permission. OCR, photos, tasks, cards, and review history are stored in the app's private directory. Uninstalling the app or clearing its data removes them. The initial version does not include data export.

OCR uses Tesseract4Android 4.9.0 with bundled English and Simplified Chinese models. Handwriting, complex layouts, phonetic symbols, and low-resolution images can produce recognition errors; cropping and manual proofreading remain necessary. Models are copied to private storage on first use.

The APK is signed with a local development key. **Never commit `build/release.keystore` or share the signing key.** Keep the same key to upgrade an existing sideloaded installation; uninstalling the old app is not necessary and would erase private app data.

## Download APK

The packaged Android APK and its SHA-256 checksum are published under [GitHub Releases](https://github.com/Cu-debug178/interval-reminder-android/releases). The current source version is 0.3.1. The APK is signed with a local key; do not install it over another build with the same package name but a different signing certificate. Future upgrades must use the same signing key.

## Build Locally

```bash
./build_local.sh
```

The output is `build/interval-reminder-debug.apk`. The script uses Android Studio's JBR, R8/D8, and a local Android tools bundle laid out under `~/Android/local-tools`. The Gradle project is also included, but an online Gradle build has not been verified. The local script is environment-specific and expects the Android 23 platform and the tools bundle's `aapt` and `apksigner` files.

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
