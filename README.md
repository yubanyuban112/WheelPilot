# WheelPilot 轻窗

> 仿 Flyme 小窗的角落轮盘启动器 —— 为被厂商拦截 `startActivity` 的机型准备的替代通道。

一个仿 Flyme 小窗形态、用 Kotlin + Jetpack Compose 写的悬浮轮盘启动器：在屏幕左右下角向上滑动，
弹出环形应用轮盘，松手即启动。启动通道不依赖普通 `startActivity`，而是优先走特权 shell（`am start`），
用来绕开荣耀 iAware 一类对后台应用启动的拦截。

## 特性

- **角落热区**：左/右下角感应区，上滑呼出，把手轻点也可；横屏、游戏、输入法弹出、锁屏息屏时自动撤下。
- **环形轮盘**：最多 8 个应用，半径 / 图标 / 张角可调，弹簧式入场，图标底座按原图标轮廓自适应圆角。
- **更多面板**：应用与工具双页签、左右滑页、拼音首字母分组、最近使用、固定位、4 列网格。
- **快捷工具**：截屏、锁屏、手电筒、识屏、微信/支付宝/云闪付扫码付款等，可放进轮盘。
- **三种启动通道**：特权服务（ADB/Root 配对）→ Shizuku → 普通 Intent，逐级降级。
- **新拟态 UI**：全套自绘控件（凹槽轨道、浮起按钮、真实滑块），无 Material 默认观感。
- **包体约 3.9 MB**：R8 + 资源收缩 + ABI 过滤（arm64-v8a / armeabi-v7a）。

## 工作原理

```
PilotService（无障碍服务）
   ├── CornerHotspot   角落悬浮窗，监听上滑手势
   ├── WheelOverlay    轮盘绘制 / 命中测试 / 点击启动
   └── AppSheetActivity 更多面板（应用 + 工具）
          │
          └── AppLauncher
                 ├── shell 通道：priv-kit / Shizuku → am start -n <pkg>/<activity>
                 ├── intent 通道：startActivity（可能被厂商拦截）
                 └── 小窗降级：windowingMode 100（厂商）→ 5（AOSP freeform）→ 全屏
```

悬浮界面走 `TYPE_ACCESSIBILITY_OVERLAY`，因此不需要 `SYSTEM_ALERT_WINDOW`；
无障碍配置里 `canRetrieveWindowContent=false`，**不读取屏幕内容**。

## 环境要求

| 项 | 版本 |
|---|---|
| 运行 | Android 11（API 30）及以上 |
| 编译 | JDK 17、Android SDK 37、Gradle 9.6、AGP 9.5 |
| 特权功能 | Android 11+ 无线调试配对（或 Root） |

## 构建

```bash
# 1. 配好 SDK 路径
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2. 编译
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`（未签名）。

需要签名时在 `local.properties` 追加（该文件已被 `.gitignore` 排除）：

```properties
WHEEL_KEYSTORE_FILE=pilot-release.jks
WHEEL_KEYSTORE_PASSWORD=你的密码
WHEEL_KEY_ALIAS=pilot
```

没有填这几项也能正常构建，只是输出未签名 APK。

> 注意：`compileSdk` 需要 37，因为 priv-kit 0.21.0 的 AAR 声明了这个下限。
> 若你的 SDK 版本更低，可尝试降 priv-kit 版本再对齐 `compileSdk`。

## 使用

1. 安装 APK，打开「轻窗」。
2. 点「去开启」启用无障碍服务（部分系统会提示"受限设置"，需在应用信息里允许）。
3. 勾选最多 8 个应用放上轮盘（应用页顶部还可勾选快捷工具）。
4. 「特权服务」→ 配对：系统设置里打开*无线调试 → 使用配对码配对设备*，把六位码填进输入框（配对期间别关系统窗口）→ 启动服务。
5. 回桌面，从左下角或右下角向上滑动呼出轮盘。

跳过第 4 步也能用，但启动会退回普通 Intent，在拦截机型上可能失效。

诊断日志：

```bash
adb logcat -s WheelPilot:D
```

`launch <应用> -> shell-ok` 表示走通特权通道；`intent-ok` 表示已降级。

## 权限说明

| 权限 | 用途 |
|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | 绘制悬浮界面与注入手势 |
| `QUERY_ALL_PACKAGES` | 配置页列出本机应用 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 无线调试配对走本地网络 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | 特权服务常驻 |
| `POST_NOTIFICATIONS` | 配对码回填通知 |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | 可选，用于识别当前前台应用 |

**本项目不联网上报任何数据**，无统计、无账号、无广告。

## 已知限制

- 轮盘最多 8 个应用，热区固定在左右下角（不可拖动）。
- 只支持"直接点击图标启动"，不支持原版小窗助手的"滑到图标松手"。
- 部分厂商 ROM 会杀掉无障碍服务，需要手动加白名单 / 关电池优化。
- 特权服务依赖无线调试，重启手机后需重新启动服务。

## 免责声明

- 本项目仅供**个人学习与自用**。使用无障碍服务、`am start` 与厂商小窗模式属于利用系统公开能力，
  可能违反某些设备的使用条款，请自行评估。
- 作者不对因使用本软件导致的设备异常、数据丢失、账号风险承担责任。
- 项目行为参考了若干商业闭源小窗应用的用户体验，**未使用其任何代码或资源**；
  全部实现为独立编写。若相关权利方有异议，请开 Issue 联系。
- 请勿将本项目用于绕过任何付费、鉴权或安全机制。

## 参与

Issue / PR 都欢迎。提交前请保证：

- `./gradlew assembleRelease` 能通过；
- 不提交 `*.jks`、`local.properties`、`build/`、APK 产物；
- 新增依赖注明来源与许可证。

## 许可证

[Apache License 2.0](LICENSE)

## 致谢

- **灵感**：酷安 **@tinos** 与 **@Carry** 两位大佬的小窗项目，以及 Flyme 的小窗形态。
  也缅怀一下那个做出 Flyme 的珠海魅族。
- **技术**：[priv-kit](https://github.com/priv-kit) · [Shizuku](https://github.com/RikkaApps/Shizuku-API) ·
  [hiddenapibypass](https://github.com/LSPosed/AndroidHiddenApiBypass) · AndroidX / Jetpack Compose
