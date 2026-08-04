# LibreMessage(短信)

[English](README.md) · **中文**

一个对经典 **AOSP Messaging** 应用(`com.android.messaging`)的开源重新实现,
使用 Kotlin + Jetpack Compose 从零编写。简洁、现代的短信客户端,不含任何
Google 专有代码。

> **声明:** 本项目是基于原应用*可观察行为*的独立重实现,与 Google 无任何
> 关联或背书。"Messaging" 与 "Google" 均为其各自所有者的商标。

## 功能

- **短信收发** — 读取、发送、接收;内置验证码自动复制与广告短信静音
- **智能分类** — 会话自动归类为验证码、取件码(包裹)、广告与普通会话
- **会话界面** — Material 3 设计,消息气泡、首字母头像、长按多选批量
  复制 / 分享 / 删除
- **新建会话** — FAB 进入新会话模式,内置按号码过滤的联系人选择器
- **默认短信应用资格** — 完整角色认证(SMS_DELIVER、WAP_PUSH、
  RESPOND_VIA_MESSAGE)
- **联系人操作** — 从会话右上角菜单可将发件人添加到通讯录或屏蔽号码
- **国际化** — 界面内置英文与简体中文,随系统语言切换
- **彩信通知** — 收到彩信时弹出通知(暂不支持查看彩信内容)

## 构建

两个风味:

| 风味 | 联网 | 号码标记 |
|---|---|---|
| `libre` | 完全离线(无 INTERNET 权限) | 无 |
| `standard` | 联网 | MIUI 黄页号码标记 + 图标 |

```bash
# Release 签名(可选,已 gitignore):
#   keystore.properties -> storeFile, storePassword, keyAlias, keyPassword
./gradlew :app:assembleLibreRelease     # 离线版
./gradlew :app:assembleStandardRelease  # 联网版,含号码标记
```

APK 输出:`app/build/outputs/apk/<flavor>/release/app-<flavor>-release.apk`

> `standard` 内置 [mi-anti-spam](https://github.com/grill-glitch/mi-anti-spam)
> 的 Kotlin 移植(MIUI 黄页来电识别 / 号码标记查询),仅供学习交流使用。

## 致谢

`SmsParser` 中的匹配规则参考了两个开源项目:

- [otphelper](https://github.com/jd1378/otphelper) — 多语言验证码关键词表
  (code/OTP/验证码/コード/인증번호/код …)与数字归一化技巧
  (阿拉伯-印度数字、波斯数字、全角数字)
- [deliveries](https://github.com/itsvic-dev/deliveries) — 各快递公司
  运单号格式(EMS、DHL、UPS、4PX、InPost、顺丰 …),用于识别快递短信

`mms/pdu/` 下的 MMS PDU 编码器来自 AOSP `com.google.android.mms.pdu`
库(Apache-2.0,© The Android Open Source Project),原样复制并保留
原始许可头。

## 许可证

GPL-3.0 — 见 [LICENSE](LICENSE)。
