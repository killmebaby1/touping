# LynkCast 深度定制说明（领克 09 / OSN 2.0 / Android 9 车机）

本文件记录对 Easycontrol_For_Car 工程做的"深度定制"代码修改，使其作为**领克车机投屏主控端**（车机横屏 + 息屏投屏 + 固定签名覆盖安装）。
基线版本：v1.6.10（versionCode 10610）→ 定制版本：v2.0.0（versionCode 20000）。
applicationId 保持 `top.eiyooooo.easycontrol.app` 不变，便于未来与上游版本对齐。

---

## 一、改动清单（每处文件 + 行级变更 + 理由）

### 1. `easycontrol/app/build.gradle`

| # | 位置 | 变更 | 理由 |
|---|------|------|------|
| 1 | `defaultConfig.versionCode` | `10610` → `20000` | 与上游版本号彻底拉开距离，避免车机应用市场/ADB 覆盖安装时与上游版冲突；20000 是车机专属基线起点。 |
| 2 | `defaultConfig.versionName` | `"1.6.10"` → `"2.0.0"` | 同步语义化版本号；应用内"关于"页显示新版名。 |
| 3 | `android{}` 块顶部新增 `signingConfigs.carcast` | 从 `rootProject.file("keystore/keystore.properties")` 读取 `storeFile/storePassword/keyAlias/keyPassword`；properties 文件不存在时 `hasKeystore=false`，`carcast` 配置留空。 | 固定签名是车机部署的硬要求：每次云端 CI 编译出来的 APK 必须**同签名**，否则车机 `pm install -r` 会因签名不一致拒绝覆盖安装，导致用户数据（设备列表、密钥、设置）被清空。 |
| 4 | `buildTypes.debug` | 新增 `signingConfig hasKeystore ? signingConfigs.carcast : signingConfigs.debug` | Debug 包也走 carcast 签名（若 properties 存在），这样日常调试包与 release 包同签名，车机上可互相覆盖。 |
| 5 | `buildTypes.release` | 新增 `signingConfig hasKeystore ? signingConfigs.carcast : signingConfigs.debug`；`minifyEnabled/shrinkResources/proguardFiles` 保持不动。 | Release 正式包固定签名；无 keystore 时回退 debug 签名，保证 CI 在 keystore 工作线尚未合入时仍能出包。 |

> **关键约束**：`keystore/keystore.properties` 由另一工作线生成到 `easycontrol/keystore/keystore.properties`，共 4 行：
> ```
> storeFile=keystore/carcast.jks
> storePassword=carcast1234
> keyAlias=carcast
> keyPassword=carcast1234
> ```
> 对应的 `carcast.jks` 也放在 `easycontrol/keystore/` 下。本工作线不生成 keystore 文件，只在 build.gradle 里做**条件加载**——文件缺失时 `hasKeystore=false`，编译仍能通过（回退 debug 签名），不会破坏 CI。

### 2. `easycontrol/app/src/main/res/values/strings.xml`

| # | 键 | 变更 | 理由 |
|---|----|------|------|
| 1 | `app_name` | `"易控车机版"` → `"领克投屏"` | 车机桌面/启动器里显示的应用名，替换为面向领克用户的品牌名。 |

### 3. `easycontrol/app/src/main/res/values-en/strings.xml`

| # | 键 | 变更 | 理由 |
|---|----|------|------|
| 1 | `app_name` | `"EasyControl"` → `"LynkCast"` | 英文 locale 下的品牌名；与中文"领克投屏"对应。 |

### 4. `easycontrol/app/src/main/java/top/eiyooooo/easycontrol/app/entity/Setting.java`

仅改三个 `getXxx()` 中 `getBoolean` 的**默认参数**，SharedPreferences 的 key 名、对应 setter 一律不动。

| # | 方法 | key | 默认值变更 | 理由 |
|---|------|-----|-----------|------|
| 1 | `getTurnOffScreenIfStart()` | `"TurnOffScreenIfStart"` | `false` → `true` | **息屏投屏核心卖点**：车机一连接手机，手机屏幕背光自动关闭（省电、防窥、夜间行车不刺眼），投屏流不中断。 |
| 2 | `getAlwaysFullMode()` | `"alwaysFullMode"` | `false` → `true` | **始终全屏**：车机横屏连接后直接进入全屏沉浸式页面并锁定横屏方向，不出现小窗/迷你窗，操作面最大化。 |
| 3 | `getDefaultShowNavBar()` | `"defaultShowNavBar"` | `true` → `false` | 全屏页默认隐藏底部导航条，进一步减少车机屏幕占用；悬浮按钮条已提供返回/Home/最近任务，用户不需要系统导航栏。 |

> **注意**：这三个默认值只影响**首次安装、SharedPreferences 里尚无该 key** 的场景。老用户升级后，其 SharedPreferences 里已有的旧值会保留——这是有意为之，避免升级时强行改变用户既有偏好。若想强制所有车机首次启动都生效，可在升级后清一次应用数据，或后续版本在 Application 里做一次迁移（本次不做）。

---

## 二、息屏投屏机制验证结论（为什么锁屏/息屏不断流）

### 2.1 完整链路（已读源码确认）

```
车机端 FullActivity 灯泡按钮
  └─ ControlPacket.sendLightEvent(mode)          // 协议消息 type=7
       └─ 经由 scrcpy 隧道下发到手机端 server
            └─ Server 端 Scrcpy.executeControlIn case 7
                 └─ Device.changeScreenPowerMode(mode)
                      └─ SurfaceControl.setDisplayPowerMode(token, mode)
```

启动时"连接后自动息屏"链路：

```
Client.startServer()
  └─ 读 Setting.getTurnOffScreenIfStart() 决定是否在 ScreenMode 位标志里置位
       └─ server 端 Options.TurnOffScreenIfStart 生效
            └─ 启动序列：先 setDisplayPowerMode(ON) 唤醒 → sleep 2 秒 → setDisplayPowerMode(OFF)
```

### 2.2 mode 值含义

| mode | SurfaceControl 常量 | 物理效果 |
|------|---------------------|----------|
| 0 | `POWER_MODE_OFF` | 关闭屏幕背光，屏幕变黑，但 SurfaceFlinger 仍在合成帧 |
| 2 | `POWER_MODE_ON` | 恢复正常背光 |

### 2.3 为什么关物理屏不锁屏、投屏不断

- `SurfaceControl.setDisplayPowerMode(OFF)` 只控制**背光/显示管线电源**，等价于 `adb shell svc power拃` 里"关屏"那一档，**并不触发 Keyguard 锁屏流程**，也不释放 `WakeLock`。
- scrcpy 的视频流走的是 **SurfaceFlinger 录屏缓冲**（`SurfaceControl.captureDisplay` / 镜像时通过 `DisplayContent` 直接取帧），不是 `MediaProjection` API。只要 `SurfaceFlinger` 还在合成（即使背光关了），帧就源源不断给到 encoder → H.264/H.265 流 → 车机端解码。
- 因此手机屏幕黑了，但**视频流、音频流、控制通道（触控/按键）全部保持**。这与"按电源键真锁屏"是两回事：真锁屏会走 `PowerManager.goToSleep()` → 触发 Keyguard → 部分机型会暂停录屏 surface；本方案**完全绕过**这条路径。

### 2.4 与 Android 17 / MediaProjection 锁屏断流问题的关系

- 市面上"用 MediaProjection 做投屏"的方案（例如安卓原生投屏、某些车机互联 App），在手机端升级到 Android 14+ 尤其是 Android 17 后，一旦锁屏或息屏，`MediaProjection` 的 `VirtualDisplay` 会被系统暂停甚至销毁，导致车机画面冻结。
- **本方案不经 MediaProjection**：车机是主控端，通过内置 libadb-android（TcpChannel + ADB 密钥认证）连到手机上的 `easycontrol_server.jar`，server 用 `shell` 级权限直接调 `SurfaceControl` 隐藏 API 录屏。这是 **ADB shell 权限驱动的录屏**，不受 MediaProjection 生命周期约束。
- 结论：手机端无论升级到 Android 14/15/16/17，只要 ADB 调试（无线调试）还能开，录屏流就不会因息屏/锁屏而断。这是本方案相对"车机 App 内置 MediaProjection"路线的**核心架构优势**，也是"息屏投屏"卖点成立的根因。

---

## 三、全屏与 Android 9 兼容性结论

### 3.1 minSdk / targetSdk

- `minSdk 21`（Android 5.0）：覆盖领克 09 OSN 2.0 的 Android 9（API 28）毫无压力，远高于下限。
- `targetSdk 34`：车机系统原生允许安装 targetSdk ≤ 系统版本 + 任意值的 App（Android 9 不会因 targetSdk 34 拒绝安装），运行时行为在 Android 9 上仍按 API 28 行为沙箱执行，新 API 若被调用会 `NoSuchMethodError`——所以下面要确认"车机端有没有调 API 28+ 独有 API"。

### 3.2 车机端（主控端）调用面核查

车机端是 App 模块（`top.eiyooooo.easycontrol.app`），跑在领克 09 的 Android 9 上。关键全屏/沉浸式实现：

| 调用 | API level | Android 9 上是否可用 |
|------|-----------|---------------------|
| `View.setSystemUiVisibility(SYSTEM_UI_FLAG_IMMERSIVE_STICKY ...)` | API 19 | ✅ 完全可用 |
| `View.SYSTEM_UI_FLAG_HIDE_NAVIGATION / FULLSCREEN / LAYOUT_HIDE_NAVIGATION` | API 16/19 | ✅ |
| `View.setOnApplyWindowInsetsListener` 做异形屏（cutout）适配 | API 20 | ✅（cutout API 28，但本工程用 `SYSTEM_UI_FLAG_LAYOUT_STABLE` + 窗口布局兜底，不直接调 `DisplayCutout`） |
| `PublicTools.setFullScreen()` 封装的 immersive sticky | API 19 | ✅ |
| `AlwaysFullMode` 锁定横屏方向（`setRequestedOrientation(SCREEN_ORIENTATION_SENSOR_LANDSCAPE)`） | API 1 | ✅ |

**结论**：车机端没有任何 API 28+ 独有调用；沉浸式全屏、横屏锁定、悬浮按钮条在 Android 9 上行为与高版本一致。

### 3.3 无线连接方向

- **方向是"车机 → 手机"**：车机作为主控端主动发起 ADB 连接到手机。
- 车机端 Android 9 只需要开普通的 **ADB over TCP / 无线 ADB 客户端**能力，内置 libadb-android 已经封装好，不依赖车机系统的"无线调试"开关。
- **手机端才需要 Android 11+ 无线调试配对**（PairActivity 走的是手机端开发者选项里的"无线调试"配对码流程），手机是用户自己的安卓机（可达 Android 17），不受车机 Android 9 限制。
- 所以"车机 Android 9 + 手机 Android 17"这个组合是**架构上就兼容的**：新 API 全在手机端 server.jar 里（用 `--new-display` / `SurfaceControl` 隐藏 API 调，手机系统自己支持就行），车机端只是个解码渲染 + 触控转发的瘦客户端。

---

## 四、签名与构建说明

### 4.1 固定 keystore 的目的

- 车机部署流程是 `adb install -r LynkCast.apk`（覆盖安装）。Android 要求**新包与旧包签名一致**才能 `-r` 覆盖，否则报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，必须先 `pm uninstall` —— 一卸载，设备列表、ADB 密钥、用户设置全部丢失。
- Android Studio 默认 debug keystore 是 `~/.android/debug.keystore`，**每台开发者机器、每次 CI runner 都不一样**。如果直接用 debug 签名出包，下一次 CI 在另一台机器上跑出来的包就签名不同，车机无法覆盖升级。
- 因此本工程引入 `easycontrol/keystore/carcast.jks`（固定 keystore），提交到仓库（或由 CI secret 注入），保证**任何机器、任何时间编译出来的 APK 都是同一签名**。车机用户每次拿到新包，直接 `adb install -r` 覆盖即可，数据不丢。

### 4.2 keystore 文件清单（由另一工作线提供）

```
easycontrol/keystore/
├── carcast.jks              # 固定 keystore 二进制
└── keystore.properties      # 4 行明文配置（见上文 §1）
```

`.gitignore`（工程根）当前只忽略 `build/`、`.gradle/`、`.idea/`、`local.properties`、`easycontrol_server.jar`，**没有忽略 `keystore/`**——确认无需修改，keystore 文件可随仓库走（或由 CI secret 注入，二选一）。

### 4.3 云端编译命令

在 `easycontrol/` 目录下（含 `gradlew` 的那层）：

```bash
# Windows / Linux 通用，在 easycontrol/ 下
./gradlew :app:assembleDebug        # 日常调试包
./gradlew :app:assembleRelease      # 正式发布包
```

产物路径：
- Debug：`easycontrol/app/build/outputs/apk/debug/app-debug.apk`
- Release：`easycontrol/app/build/outputs/apk/release/app-release.apk`

> 本机无 Android SDK，**无法本地编译验证**；语法正确性最终由云端 CI 编译验证。本工作线只负责源码改动 + 自检（Select-String 复查关键行），不负责编译通过。

### 4.4 回退策略

- 若 `easycontrol/keystore/keystore.properties` 尚未就位，`hasKeystore=false`，build.gradle 会自动回退到 `signingConfigs.debug`，CI 仍能出包——只是签名是默认 debug 签名，不影响"能不能编译"，只影响"车机能不能覆盖升级"。
- keystore 就位后，**第一次**在车机上安装时建议先 `adb uninstall top.eiyooooo.easycontrol.app`（清掉之前 debug 签名的残留），再装 carcast 签名包；之后的所有升级就都能 `-r` 覆盖了。

---

## 五、未触碰的范围（明确边界）

本次定制**没有改**以下内容，保持与上游一致：

- `AndroidManifest.xml`（权限、组件声明、launcher 图标全部不动）
- `LICENSE` / `GPL` 声明 / `PRIVACY.md` / `README.md` / `HOW_TO_USE.md` / `HOW_TO_BUILD.md`
- `server` 模块（scrcpy server.jar 的 C/JNI 代码一律不动；仍由 `copyRelease` 任务拷成 `app/src/main/res/raw/easycontrol_server.jar`）
- `PairActivity` / `Client` / `FullActivity` / `MainActivity` / `ControlPacket` / `Device` 等业务逻辑
- 所有 `drawable/`、`layout/`、`mipmap/` 资源
- `applicationId` 仍为 `top.eiyooooo.easycontrol.app`（只为对齐上游，不改包名）

---

## 六、已知风险与建议

| # | 风险 | 说明 | 建议 |
|---|------|------|------|
| 1 | **三个默认值只对首次安装生效** | 老用户 SharedPreferences 里已有 `TurnOffScreenIfStart=false` 等旧值，升级后不会自动切到新默认。 | 若要全量强制，可在 `Application.onCreate` 里加一次性迁移逻辑（读旧 key → 写新默认 → 标记 migrationDone），本次未做，留待后续版本。 |
| 2 | **AlwaysFullMode 默认 true 与 `ClientView.changeToMini/changeToSmall` 的交互** | 代码里 `changeToMini`/`changeToSmall` 已有 `if (miniView == null) return;` 空保护，AlwaysFullMode=true 时小窗路径理论上不会被触发。但如果某些异常路径（例如屏幕旋转、配置变更）误触 `changeToSmall`，会因 `miniView==null` 直接 return，行为安全。 | 车机真机首测时重点验证：连接后是否稳定停在全屏横屏，无闪退、无黑边。 |
| 3 | **defaultShowNavBar=false 后，用户在全屏页怎么呼出导航** | 全屏页底部悬浮按钮条（旋转/返回/Home/最近任务/息屏/电源）已覆盖常用操作，且 2 秒自动隐藏、点屏幕呼出。 | 若车机用户反馈"找不到最近任务键"，可在悬浮条里加一个长按呼出系统导航的兜底（本次不动 UI）。 |
| 4 | **keystore.properties 未就位时的签名漂移** | keystore 工作线尚未合入前，CI 出的是 debug 签名包，不同 runner 之间签名可能不一致。 | keystore 合入前，不要在车机上反复升级测试；要么固定用一台机器/一个 runner 出包，要么等 keystore 就位。 |
| 5 | **minify + shrinkResources 在 release 下** | release 仍开着混淆和资源压缩；本次新增的 signingConfig 不影响混淆规则，但如果 carcast.jks 里的 keyAlias 名与 proguard 无关，无需调整 proguard-rules.pro。 | 云端 release 出包后，检查 APK 能在车机正常安装、悬浮窗权限流程不被混淆吞掉。 |
| 6 | **语法正确性未经本地编译** | 本机无 Android SDK，Groovy/AGP 8.1.3 语法是否被 Gradle 接受，要等云端 CI 第一次跑。 | 若 CI 报 build.gradle 语法错，优先检查 `FileInputStream` 是否需要显式 import（Groovy 默认 java.io 已导入，正常不需要）。 |

---

## 七、自检结果（本次工作线内已跑）

- [x] `app/build.gradle`：`versionCode 20000`、`versionName "2.0.0"`、`signingConfigs.carcast`、两处 `signingConfig hasKeystore ? ...` 均已落盘。
- [x] `values/strings.xml`：`app_name` = `领克投屏`。
- [x] `values-en/strings.xml`：`app_name` = `LynkCast`。
- [x] `Setting.java`：`getTurnOffScreenIfStart` 默认 `true`、`getAlwaysFullMode` 默认 `true`、`getDefaultShowNavBar` 默认 `false`。
- [x] `.gitignore`：未忽略 `keystore/`，确认无需修改。
- [ ] 本地编译：**不通过 / 无法执行**（本机无 Android SDK），由云端 CI 验证。
