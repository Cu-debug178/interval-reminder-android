# Project Instructions

- All clickable controls must provide immediate feedback when activated. Use the existing `Motion.press(view)` helper for buttons, icon buttons, and clickable rows/cards so taps have a visible pressed state and ripple.
- Give meaningful review actions an action-appropriate response through `Feedback` (haptic and/or sound), respecting the user's sound and haptic settings. Do not add sound to every navigation tap; avoid noisy or repetitive feedback.
- When a control changes content or navigates, make the result visible promptly with the existing motion/state patterns. Errors and saved results must also be surfaced to the user.
- Prefer shared button helpers such as `WordUi.button(...)` and `MainActivity.button(...)`. Any directly constructed clickable control must explicitly receive the same press feedback.
- Keep accessibility and system settings respected: haptics use Android haptic feedback APIs and remain controlled by the in-app preference; sound remains controlled by its preference and system audio state.
- For UI or interaction changes, deploy the debug variant to the `IntervalReminder_API_35` emulator and capture the affected screens/flows for user review before producing a distributable APK. Do not create or publish a release APK until the user has reviewed the preview.
