# LibreMessage

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

## Build

Two flavors:

| Flavor | Network | Anti-spam |
|---|---|---|
| `libre` | fully offline (no INTERNET permission) | none |
| `standard` | online | MIUI yellow-page number marks + icons |

```bash
# Release signing (optional, gitignored):
#   keystore.properties -> storeFile, storePassword, keyAlias, keyPassword
./gradlew :app:assembleLibreRelease     # offline build
./gradlew :app:assembleStandardRelease  # online build with number marking
```

APK output: `app/build/outputs/apk/<flavor>/release/app-<flavor>-release.apk`

> `standard` embeds a Kotlin port of the [mi-anti-spam](https://github.com/grill-glitch/mi-anti-spam)
> lookup (MIUI yellow-page caller-ID / number marking). Educational use only.

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
