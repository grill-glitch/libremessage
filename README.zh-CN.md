# <img src="docs/screenshots/app-icon.svg" width="28" height="28" alt=""> LibreMessage(短信)

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
- **防验证码轰炸** — 静音验证码通知、不在首页列表显示(智能 banner 保留),
  可临时放行 1 分钟并带倒计时
- **桌面小组件** — 验证码卡片(2×1 / 2×3)与最近会话列表(4×2,带联系人
  名字、彩色头像、点击直达聊天);收到新验证码实时刷新

## 截图

| 主界面 | 会话小组件 |
|---|---|
| <img src="docs/screenshots/home-main.png" width="280" alt="主界面"> | <img src="docs/screenshots/widget-list.png" width="280" alt="会话小组件"> |

主界面(智能验证码 banner、分类标签与会话列表)与主屏会话小组件。

## 构建

```bash
# Release 签名(可选,已 gitignore):
#   keystore.properties -> storeFile, storePassword, keyAlias, keyPassword
./gradlew :app:assembleRelease
```

APK 输出:`app/build/outputs/apk/release/app-release.apk`

## ROM 集成(Soong)

LibreMessage 可以预装进自定义 ROM(crDroid / LineageOS / AOSP)作为系统
应用。应用本体是 Jetpack Compose 项目,而 Soong 没有 Compose/Coil 的构建
支持 —— 因此 ROM 通过 `android_app_import` 直接消费签名后的 release APK,
与其他 Compose 系统应用的做法一致。集成文件位于**仓库根目录**:
`Android.bp` + `LibreMessage.apk`(签名 release APK,当前 v1.3.6)。

### 加入 ROM 树

1. **local manifest** — 把本仓库 checkout 到 `packages/apps/LibreMessage`:

   ```xml
   <!-- .repo/local_manifests/libremessage.xml -->
   <manifest>
     <project name="grill-glitch/libremessage"
              path="packages/apps/LibreMessage"
              remote="github"
              revision="main" />
   </manifest>
   ```

   然后执行 `repo sync -c --force-sync --no-tags -j4 packages/apps/LibreMessage`

2. **产品包** — 在 `device/<厂商>/<设备>/device.mk` 中添加:

   ```makefile
   # LibreMessage — 默认短信应用
   PRODUCT_PACKAGES += \
       LibreMessage
   ```

3. **构建**(示例:crDroid 16,Redmi 12C / earth):

   ```bash
   source build/envsetup.sh
   lunch lineage_earth-bp4a-userdebug
   m LibreMessage        # 单模块;`m bacon` 构建完整 ROM
   ```

   产物:`out/target/product/<设备>/system/app/LibreMessage/`

### 说明

- **签名**:APK 使用开发者 release 签名(`presigned: true` +
  `preprocessed: true`)。已安装 release 版的设备升级 ROM 无需清除数据,
  app 仍可独立更新。Compose 带入的 `androidx.window.*` uses-library
  标记通过 `optional_uses_libs` 处理。
- **默认短信应用**:Android 16 已移除 `config_defaultSmsApp` 机制,首次
  开机不会自动分配角色。需在 设置 → 默认应用 → 短信应用 手动选择一次
  (或首次收发短信时按系统提示选择)。
- **MMS PDU 代码复用**:`mms/pdu/` 源码是 AOSP `com.google.android.mms.pdu`
  库的冻结子集,crDroid / LineageOS 树中同源实现在
  `frameworks/base/telephony/common/com/google/android/mms/pdu`(逻辑完全
  一致,仅去掉了框架的 `@UnsupportedAppUsage` 注解)。prebuilt APK 自带
  编译副本是因为框架库是 hidden API;升级 AOSP 版本时保持本地快照与
  ROM 树同步即可。
- **更新预装包**:构建新版本(`./gradlew :app:assembleRelease`)后把 APK
  复制覆盖仓库根的 `LibreMessage.apk`,提交推送,然后在 ROM 树中
  `repo sync` 即可。

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
