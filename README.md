# 领克投屏（车机端）

基于开源项目 **Easycontrol_For_Car**（易控车机版，GPL-3.0）深度定制，适配 **领克09 / OSN 2.0 安卓9 车机** 的无线投屏方案。

## 核心能力

- **手机 → 车机无线投屏**（车机端为主控，通过内置 ADB 连接手机）
- **游戏全屏**：连接后自动全屏横屏、沉浸式隐藏系统栏、无黑边
- **息屏投屏**：投屏时手机屏幕自动熄灭（只关背光、不锁屏），投屏不断流——不经 MediaProjection，安卓 15+ 锁屏也不会中断投屏
- **低延迟**：基于 scrcpy 协议（H.264 硬编硬解）
- 支持反向操控、蓝牙手柄玩游戏

## 工程结构

```
easycontrol_car/
  easycontrol/
    app/    # 车机端主控 App（编译产物即车机端 APK）
    server/ # 手机端服务端（编译后封装进车机端 App 的 res/raw，连接时自动推送）
```

## 云端编译

内置 GitHub Actions 工作流 `.github/workflows/easycontrol-car-build.yml`，push 到 `easycontrol-car` 分支自动编译。

编译产物在 **Actions → carcast-car-apk** 下载，或从 **Releases** 下载 `carcast-car.apk`。

## 使用方法（简版）

1. **手机**：开发者选项 → 打开 USB 调试 + 无线调试，记下 IP:端口 和配对码。
2. **车机**：安装 `carcast-car.apk` → 授权悬浮窗 → 添加设备，填手机 IP:端口 → 输配对码连接。
3. **游戏**：手机蓝牙连手柄，游戏画面全屏横屏上大屏；手机可息屏省电。

详见 `easycontrol_car/使用说明.md`。

## 协议许可

本工程基于 GPL-3.0 开源项目 Easycontrol_For_Car（https://github.com/eiyooooo/Easycontrol_For_Car）改造，遵循 GPL-3.0 许可。对外分发时须保留开源声明。
