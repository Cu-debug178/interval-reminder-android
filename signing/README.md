# 温故 / Interval Reminder — public app signing material

This archive contains the **intentionally published** signing keystore for the Android app **温故** (Interval Reminder).

- Package name: `com.example.reviewreminder`
- Keystore file: `signing/release.keystore`
- Key alias: `reviewreminder`
- Certificate DN: `CN=Interval Reminder, O=Local`
- Certificate SHA-256: `559718e1d45fda007dd6ebd9f43a019ea9104f75b16895d4718ad018ff5457bc`

The store password and key password are the same values already written in this project's existing `build_local.sh`. They are not repeated in this README.

## Warning — impersonation / malicious overlay risk

Anyone who has this keystore can sign an APK that Android will accept as an **upgrade** over an existing official install of 温故, without uninstalling the app and without wiping user data. That includes a **malicious update** that looks like a normal upgrade.

This file was published at the original author's explicit request, so that other people may build compatible overlay-install APKs. Treat it as a **public signing key**, not as a secret that protects users from impersonation.

If you redistribute or use this key, say clearly that the resulting APK is signed with the published official key, and do not hide modifications.

This archive contains only the 温故 app signing keystore. It does not include SSH, GPG, or any other private keys.

## In this repository

`signing/release.keystore` is the only signing file intentionally tracked. It is public app signing material for 温故 / Interval Reminder (`com.example.reviewreminder`, alias `reviewreminder`), not a general credential and not an SSH, GPG, or API secret.

`.gitignore` still ignores `*.keystore` and `*.jks`. The only exception is `signing/release.keystore`. Do not commit `build/release.keystore` or any other keystore.

The store password and key password are the values already used in `build_local.sh`. They are not printed in this file.
