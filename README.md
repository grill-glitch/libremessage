# <img src="docs/screenshots/app-icon.svg" width="28" height="28" alt=""> LibreMessage

**English** · [中文](README.zh-CN.md)

An open-source reimplementation of the classic **AOSP Messaging** app
(`com.android.messaging`), rebuilt from scratch in Kotlin + Jetpack
Compose. A clean, modern SMS client with no Google proprietary code.

> **Disclaimer:** This is an independent, clean-room-style reimplementation
> based on the *observable behavior* of the original app. It is not
> affiliated with or endorsed by Google. "Messaging" and "Google" are
> trademarks of their respective owners.

## Features

- **SMS** — read, send, receive; built-in verification-code auto-copy and
  ad-message silencing
- **Smart categories** — conversations are auto-sorted into verification
  codes, package pickup, ads and normal threads
- **Conversation UI** — Material 3, message bubbles, avatar initials,
  long-press multi-select for batch copy / share / delete
- **New conversation** — FAB opens an in-thread new-session mode with a
  contact picker filtered by number input
- **Default SMS app capable** — full role qualification (SMS_DELIVER,
  WAP_PUSH, RESPOND_VIA_MESSAGE)
- **Contact actions** — add the sender to contacts or block the number
  from the conversation's overflow menu
- **i18n** — UI ships in English and Simplified Chinese, follows the
  system language
- **MMS notification** — incoming MMS is surfaced as a notification
  (viewing MMS content is not supported yet)
- **Anti verification-code bombing** — mute code-message notifications,
  hide them from the home list (the smart banner stays) and temporarily
  allow codes through for 1 minute with a live countdown
- **Home-screen widgets** — a verification-code card (2×1 / 2×3) and a
  recent-conversations list (4×2) with contact names, colored avatars and
  tap-to-open; the code card refreshes instantly when a new code arrives

## Screenshots

| 主界面 Main | 会话小组件 Widget |
|---|---|
| <img src="docs/screenshots/home-main.png" width="280" alt="Main screen"> | <img src="docs/screenshots/widget-list.png" width="280" alt="Conversations widget"> |

Main screen with the smart verification-code banner, category chips and
conversation list — next to the home-screen conversations widget.

## Build

```bash
# Release signing (optional, gitignored):
#   keystore.properties -> storeFile, storePassword, keyAlias, keyPassword
./gradlew :app:assembleRelease
```

APK output: `app/build/outputs/apk/release/app-release.apk`

## ROM integration (Soong)

LibreMessage can be built into a custom ROM (crDroid / LineageOS / AOSP)
as a prebuilt system app. The app itself is a Jetpack Compose project, and
Soong has no Compose/Coil build support — so the ROM consumes the signed
release APK via `android_app_import`, exactly like other Compose system
apps. The integration lives at the **repo root**: `Android.bp` +
`LibreMessage.apk` (the signed release APK, currently v1.3.5).

### Add to the ROM tree

1. **local manifest** — checkout this repo into `packages/apps/LibreMessage`:

   ```xml
   <!-- .repo/local_manifests/libremessage.xml -->
   <manifest>
     <project name="grill-glitch/libremessage"
              path="packages/apps/LibreMessage"
              remote="github"
              revision="main" />
   </manifest>
   ```

   then `repo sync -c --force-sync --no-tags -j4 packages/apps/LibreMessage`

2. **Product packages** — in `device/<oem>/<device>/device.mk`:

   ```makefile
   # LibreMessage — default SMS application
   PRODUCT_PACKAGES += \
       LibreMessage
   ```

3. **Build** (example for crDroid 16, Redmi 12C / earth):

   ```bash
   source build/envsetup.sh
   lunch lineage_earth-bp4a-userdebug
   m LibreMessage        # single module; `m bacon` builds the full ROM
   ```

   Output: `out/target/product/<device>/system/app/LibreMessage/`

### Notes

- **Signature**: the APK ships with the developer's release signature
  (`presigned: true` + `preprocessed: true`). Devices that already run the
  release APK upgrade to the ROM build without wiping data, and the app
  stays updatable as a regular app. The `androidx.window.*` uses-library
  tags pulled in by Compose are handled via `optional_uses_libs`.
- **Default SMS app**: Android 16 removed the `config_defaultSmsApp`
  mechanism, so the role is not pre-assigned at first boot. Set it once in
  Settings → Default apps → SMS app (or answer the system prompt on the
  first message).
- **MMS PDU code reuse**: the `mms/pdu/` sources are a frozen subset of
  the AOSP `com.google.android.mms.pdu` library, which ships inside crDroid /
  LineageOS at `frameworks/base/telephony/common/com/google/android/mms/pdu`
  (identical logic; only the framework's `@UnsupportedAppUsage` annotations
  are stripped). The prebuilt APK carries its own compiled copy because
  the framework library is hidden API, so keep the local snapshot in sync
  with the ROM tree when bumping AOSP versions.
- **Updating the prebuilt**: build a new release
  (`./gradlew :app:assembleRelease`), copy the APK over `LibreMessage.apk`
  in the repo root, commit, push, then `repo sync` in the ROM tree.

## Credits

Matching rules in `SmsParser` draw on two open-source projects:

- [otphelper](https://github.com/jd1378/otphelper) — multilingual
  verification-code keyword tables (code/OTP/验证码/コード/인증번호/код …)
  and digit-normalization tricks (Arabic-Indic, Persian, full-width)
- [deliveries](https://github.com/itsvic-dev/deliveries) — per-carrier
  tracking-number formats (EMS, DHL, UPS, 4PX, InPost, SF Express …) used
  to recognize parcel messages

The MMS PDU encoder under `mms/pdu/` is the AOSP
`com.google.android.mms.pdu` library (Apache-2.0, © The Android Open
Source Project), copied verbatim with the original license headers.

## License

GPL-3.0 — see [LICENSE](LICENSE).
