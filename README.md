# 车机投屏（双端）

免费、开源的安卓手机 → 安卓车机无线投屏。手机用自研发送端采集屏幕、H.264 硬编后经 TCP 推给车机；车机接收端硬解并**强制全屏（1920×1080）**显示。

## 两个 App

| 模块 | 包名 | 装在哪 | 作用 |
|---|---|---|---|
| `receiver` | `com.carcast.receiver` | 车机 | TCP 服务端，MediaCodec 硬解 H.264，全屏显示，开机自启 |
| `sender` | `com.carcast.sender` | 手机 | MediaProjection 采集 + H.264 硬编 + TCP 推流 |

## 原理

- 传输协议：自定义 TCP，端口 `2026`。
- 视频：H.264（Annex-B 裸流），手机端 `MediaCodec` 硬件编码，车机端硬件解码。
- 编码默认 `1920×1080@30fps，8Mbps`，可在发送端界面修改分辨率。
- 车机端强制横屏 + 沉浸式全屏。

## 云端编译（推荐）

工程已内置 GitHub Actions 工作流 `.github/workflows/build.yml`，无需本地装 Android 工具链：

1. 把整个 `carcast/` 目录推到一个 GitHub 仓库（含 `.github`）。
2. 在仓库 **Actions** 页点 **Run workflow**（或直接 push 到 main）。
3. 编译完成后在 **Actions → carcast-apks** 里下载两个 APK。

也可以本地编译：Android Studio 打开 `carcast/`，执行
`./gradlew :receiver:assembleDebug :sender:assembleDebug`。
（需要 JDK 17 + Android SDK。）

## 使用方法

1. **车机开热点**（或手机开热点、车机连该热点），记下车机热点的网关 IP（通常是 `192.168.43.1`）。
2. 车机装并打开 `receiver`：进入全屏等待界面，服务常驻监听 2026 端口。
3. 手机装并打开 `sender`：填车机 IP（如 `192.168.43.1`）、端口 `2026`、分辨率 `1920x1080`，点“开始投屏”。
4. 授权“屏幕录制/投屏”，画面即实时显示到车机全屏。

## 说明

- 车机端需允许安装未知来源 APK；不同车机需手动授予自启动/后台常驻权限（通常在系统设置里允许该 App 自启动）。
- 手机与车机须在同一局域网（同一热点/路由器）。
- 这是教学/自用项目，GPLv3 许可。代码仅供参考，请勿用于任何收费或商业分发。
