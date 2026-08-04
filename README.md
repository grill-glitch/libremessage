<p align="center">
  <img src="docs/icon.png" width="96" alt="LibreMessage icon" />
</p>

# LibreMessage (短信)

An open-source reimplementation of the classic **AOSP Messaging** app
(`com.android.messaging`), rebuilt from scratch in Kotlin + Jetpack
Compose. A clean, modern SMS client with no Google proprietary code.

> **Disclaimer:** This is an independent, clean-room-style reimplementation
> based on the *observable behavior* of the original app. It is not
> affiliated with or endorsed by Google. "Messaging" and "Google" are
> trademarks of their respective owners.

## Features

- **SMS only** — read, send, receive; verification-code auto-copy and
  ad-sms silencing built in
- **Categories** — conversations are auto-sorted into 验证码 (verification),
  取件码 (package pickup), 广告 (ads) and normal threads
- **Conversation UI** — Material 3, message bubbles, avatar initials,
  long-press multi-select for batch copy / share / delete
- **New conversation** — FAB opens an in-thread new-session mode with a
  contact picker filtered by number input
- **Default SMS app capable** — full role qualification (SMS_DELIVER,
  WAP_PUSH, RESPOND_VIA_MESSAGE)
- **MMS notification** — incoming MMS is surfaced as a notification
  (viewing MMS content is not supported yet)

## Screenshot

<img src="docs/screenshot.png" width="320" alt="LibreMessage conversation list" />

## Build

```bash
# Release signing (optional, gitignored):
#   keystore.properties -> storeFile, storePassword, keyAlias, keyPassword
./gradlew :app:assembleRelease
```

APK output: `app/build/outputs/apk/release/app-release.apk`

## License

GPL-3.0 — see [LICENSE](LICENSE).
