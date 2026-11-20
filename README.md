# WheelPilot 轻窗

> 仿 Flyme 小窗的角落轮盘启动器 —— 为被厂商拦截 `startActivity` 的机型准备的替代通道。

一个仿 Flyme 小窗形态、用 Kotlin + Jetpack Compose 写的悬浮轮盘启动器：在屏幕左右下角向上滑动，
弹出环形应用轮盘，松手即启动。启动通道不依赖普通 `startActivity`，而是优先走特权 shell（`am start`），
用来绕开荣耀 iAware 一类对后台应用启动的拦截。

## 下载

最新版 **3.24.23（132）** 已发布为 GitHub Release，可直接下载签名 APK 安装：

- **APK 直链**：https://github.com/yubanyuban112/WheelPilot/releases/download/v3.24.23/WheelPilot-3.24.23-132.apk
- **Release 页面**：https://github.com/yubanyuban112/WheelPilot/releases/tag/v3.24.23
- SHA-256：`14d321e565076fe2305a2ab07b3196859eeebb407a465a7bed2cf49daa32f309`

## 特性

- **角落热区**：左/右下角感应区，上滑呼出，把手轻点也可；宽高、边缘内缩、离底高度可调，横屏、游戏、锁屏息屏时自动撤下；键盘弹出时热区整体让出触摸（打字时删除、大写键照常可点）。
- **环形轮盘**：单排 6–8 槽 / 双排 6–15 槽（两条同心弧），扇形与竖形两种形状；半径 / 图标 / 张角可调，弹簧式入场，图标底座按原图标轮廓自适应圆角；滑出即展开、落定后再点按选择，避免「只想滑出却误启动」。
- **双通道输入探测**：无障碍事件与 native evdev 双通道采集触摸，任一路失效自动切换（旁路触摸），断链后第一下上滑即可呼出。
- **更多面板**：应用与工具双页签、左右滑页、拼音首字母分组、最近使用、固定位、4 列网格。
- **快捷工具**：截屏、锁屏、手电筒、识屏、微信/支付宝/云闪付扫码付款等，可放进轮盘。
- **三种启动通道**：特权服务（ADB/Root 配对）→ Shizuku → 普通 Intent，逐级降级。
- **前台黑名单**：指定应用在前台时不呼出轮盘（需「使用情况访问」权限才生效）。
- **轻量打开**：通知横幅、应用跳转、登录授权三类场景下，目标应用以浮窗打开而不是全屏；生效范围默认限定在轮盘里的应用，避免误转支付与游戏。
- **小窗分层**：连续打开多个小窗时自动错开（默认 24 dp），超过台数上限（默认 3 台）把最早的一个转后台；横竖屏各记各的几何；「更多」面板提供小窗全屏 / 小窗缩小 / 收起小窗三个批量动作。
- **通知弹幕**：任意应用的通知都会飘弹幕（不限于横屏、不限于微信/QQ），点击弹幕即以小窗打开对应应用。
- **配置备份与规则导入**：槽位、外观、行为参数导出成一个文本文件，换机或调乱后一键恢复；支持 SmartLink 规则导入；日志可整份导出。
- **主题与动效**：经典拟物为主，另有「自动」（跟随系统明暗）与「Apple 暗色」两套；级联淡入、滚动视差、按压反馈等动效均支持减弱动效（`prefers-reduced-motion` 等价）。
- **包体约 5.7 MB**（签名 APK）：R8 + 资源收缩 + ABI 过滤（arm64-v8a / armeabi-v7a）。

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

悬浮界面走 `TYPE_ACCESSIBILITY_OVERLAY`，因此不需要 `SYSTEM_ALERT_WINDOW`。

无障碍配置里 `canRetrieveWindowContent=true` + `flagRetrieveInteractiveWindows`。这一项是必须的：
判断「屏幕上有没有输入法窗口」只能读 `getWindows()` 里的窗口类型（`TYPE_INPUT_METHOD`），
该列表在内容权限关闭时拿不到，会让键盘让位、轻量打开的输入法过滤、失焦判定全部静默失效。
**只读窗口类型与前台包名，不采集、不保存任何界面文本**，也不联网。

## 环境要求

| 项 | 版本 |
|---|---|
| 运行 | Android 11（API 30）及以上 |
| 编译 | JDK 17、Android SDK 37、Gradle 9.6、AGP 9.5.1、Kotlin 2.4.21 |
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

CI（`.github/workflows/android.yml`）会在每次推送后跑单测 + assembleDebug 并上传 debug APK。

## 使用

1. 安装 APK，打开「轻窗」。
2. 点「去开启」启用无障碍服务（部分系统会提示"受限设置"，需在应用信息里允许）。
3. 勾选最多 15 个应用放上轮盘（应用页顶部还可勾选快捷工具）。
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
| `BIND_NOTIFICATION_LISTENER_SERVICE` | 可选，识别通知（通知弹幕与轻量打开的通知横幅场景） |

**本项目不联网上报任何数据**，无统计、无账号、无广告。

## 已知限制

- 轮盘单排 6–8 槽、双排 6–15 槽；热区固定在左右下角，只能调宽高、内缩与离底高度，不能拖到屏幕其它位置。
- 前台黑名单依赖「使用情况访问」权限，未授予时识别不到当前应用，黑名单不生效。
- 部分厂商 ROM 会杀掉无障碍服务，需要手动加白名单 / 关电池优化。
- 特权服务依赖无线调试，重启手机后需重新启动服务。
- 小窗分层依赖特权通道的 `am task` 命令：没有特权时只能错开启动位置，不能改已开窗口的几何。
  部分 ROM 没有 `am task move-to-back`，此时「超限驱逐」与「收起小窗」退化为仅记日志，不会销毁窗口。
- 键盘在屏期间热区不接收触摸（设置 → 场景暂停 可关）。这是有意的：热区层级高于输入法，
  拦截后即使注入手势也送不到键盘，只能从源头不拦。

### 轻量打开的限制（重要）

- **是「事后转窗」不是「拦截」**：通知横幅、跨应用跳转、登录授权都由发起方直接投递
  Intent/PendingIntent，第三方没有 hook 点。目标应用会先以全屏出现约 200–400 ms，再被转成浮窗。
- **登录授权与应用跳转在系统层面不可区分**，二者共用一条判据：打开其中任意一个，另一个会一起打开。
- **从轮盘、快捷工具、「更多」面板点图标启动的应用不会被转窗**：这些入口在发起启动时会打一个时间戳，
  保护期内（4 秒，同一个包放宽到 8 秒以覆盖冷启动慢的应用）一律按"用户自己点的"处理。
  否则轮盘是悬浮窗、不在前台栈里，无障碍看到的变化与"在 A 里点开了 B"完全同形，必然误转。
- **登录授权只做「改几何」，不重投 Intent**：系统里没有把已存在的全屏 task 就地变成浮窗的公开命令，
  能用的只有 `am task resize`（不动栈）和重新 `am start --windowingMode 5`（会重投 Intent）。
  授权靠 `resultTo` 链回传结果，重投一次流程就断了 —— 所以这类场景只用 resize，resize 不成宁可直接保持全屏。
- **通知的判定不强制「通知栏展开过」**：锁屏点通知、点横幅、从桌面直接点都不经过通知栏。现在只要
  「该应用的通知在 12 秒内到达」且前台变成了它就算。
- **「只对轮盘里的应用生效」不限制通知/横幅**：这类用户在系统界面里点的就是某个应用的通知，
  再拿轮盘名单卡它只会让功能看起来是坏的（典型：微信不在轮盘里，点通知仍是全屏）。
- **已在前台跑着的全屏应用，转窗成功率天然低**：跨窗口模式的 `am task resize` 多数 ROM 不支持，
  而 `am start` 对已存在的 task 不会改模式。执行器为此加了「转后台再以浮窗拉起」的兜底，
  但微信这类单实例应用可能回到首页。通知/跳转的冷启动（task 不存在）一定以浮窗打开；
  授权冷启动则按上面的规则等 task 出现后只改几何，改不了就全屏 —— 保证授权能用优先。
- **输入法窗口不参与判定**：键盘是与宿主无关的独立窗口，弹出时会报一条包名为输入法的窗口事件，
  与「应用内跳转」在事件流里同形。现在按三层拦：启用输入法包名表 → 包名/类名特征 → 屏幕上的
  输入法窗口类型；键盘在屏期间一律不转窗。宁可少转一次，也不能把键盘转成浮窗导致无法打字。
- 应用跳转需要特权通道（或 Shizuku）才能可靠转窗；无特权时退回 ActivityOptions 通道，成功率下降。
- 判断条件依赖通知横幅/控制中心等系统界面的窗口类名，不同 ROM 类名不同，可能需要按机型增补。

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
- `./gradlew testDebugUnitTest` 能通过（CI 同样会跑：`.github/workflows/android.yml`）；
- 不提交 `*.jks`、`local.properties`、`build/`、APK 产物；
- 新增依赖注明来源与许可证。

## 许可证

[Apache License 2.0](LICENSE)

## 致谢

- **灵感**：酷安 **@tinos** 与 **@Carry** 两位大佬的小窗项目，以及 Flyme 的小窗形态。
  也缅怀一下那个做出 Flyme 的珠海魅族。
- **技术**：[priv-kit](https://github.com/priv-kit) · [Shizuku](https://github.com/RikkaApps/Shizuku-API) ·
  [hiddenapibypass](https://github.com/LSPosed/AndroidHiddenApiBypass) · AndroidX / Jetpack Compose
