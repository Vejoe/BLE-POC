# BLE-POC

一个基于 SwiftUI + CoreBluetooth 的蓝牙低功耗（BLE）概念验证 App。同一台设备即可同时扮演 **外设（Peripheral）** 和 **中心（Central）** 两种角色，用于验证 BLE 的广播、扫描、连接、写入与通知等核心流程，并附带一个 **Web Bluetooth** 页面用于跨端联调。

## 功能特性

- **Broadcast（外设 / Peripheral）**
  - 以自定义 Service UUID 进行广播，广播名中携带配对码（`BLE-POC-<pairCode>`）
  - 支持写入 / 通知 / 读取特性（当写与通知 UUID 相同时自动合并为一个特性）
  - 显示是否有中心设备已订阅（连接状态）

- **Central（中心 / Central）**
  - 按 Service UUID 过滤扫描设备，列表展示并支持连接 / 断开
  - 发现远端特性并订阅通知
  - 向远端写入数据（发送消息）

- **消息与日志**
  - 两个标签页共享一个简单的消息输入框与日志面板
  - 收到 / 发出的消息以不同颜色区分，支持双向文本收发

- **可配置的配对参数**
  - Broadcast 与 Central 各自持有一套独立的 Service / Write / Notify UUID
  - 支持一键生成随机 UUID、一键复制
  - 配对码（Pair Code）持久化存储（`UserDefaults`），其余 UUID 每次启动恢复默认值

## 目录结构

```
BLE-POC/
├── BLE-POC-Web.html        # Web Bluetooth 调试页面（Chrome / Edge）
├── BLE-POC/
│   ├── BLE_POCApp.swift    # App 入口
│   ├── BLEManager.swift    # CoreBluetooth 封装（外设 + 中心 + 日志）
│   ├── ContentView.swift   # SwiftUI 界面（Broadcast / Central 两个标签页）
│   └── Assets.xcassets/    # 资源
└── BLE-POC.xcodeproj/      # Xcode 工程
```

## 环境要求

- macOS（装有 Xcode 26+）
- iOS 26.5+ 的真机（蓝牙功能需在真机上验证）
- Apple ID（免费账号即可，用于签名）

## 默认 UUID

| 项目 | 值 |
| --- | --- |
| Service UUID | `8f6d7f58-9ed7-4a95-8e09-5b4b8b8d1f10` |
| Write Characteristic | `4a38a38b-b6d9-43cc-8c02-44d4605dd002` |
| Notify Characteristic | `4a38a38b-b6d9-43cc-8c02-44d4605dd002` |
| Pair Code | `123456` |

> 两台设备互相通信时，双方的 UUID 必须保持一致；默认配置下写与通知使用同一 UUID，App 会自动合并为一个读写/通知特性。

## 构建与运行

1. 用 Xcode 打开 `BLE-POC.xcodeproj`。
2. 在 Signing & Capabilities 中选择你的开发团队（免费 Personal Team 即可）。
3. 选择一台已开启开发者模式的 iPhone 真机，运行 App。

### 命令行构建 + 装机

```bash
# 查看设备 UDID
xcrun devicectl list devices

# 构建
xcodebuild -project BLE-POC.xcodeproj -scheme BLE-POC \
  -configuration Debug \
  -destination 'platform=iOS,id=<UDID>' \
  -allowProvisioningUpdates build

# 安装并启动
xcrun devicectl device install app "<path-to-BLE-POC.app>" --device <UDID>
xcrun devicectl device process launch --device <UDID> vejoe.BLE-POC
```

> 首次安装后需在手机「设置 → 通用 → VPN 与设备管理」中信任开发者证书。

## 使用方法

### 两台 iPhone 互发消息

1. 设备 A：切到 **Broadcast** 标签页，打开「Advertise Service」开关，开始广播。
2. 设备 B：切到 **Central** 标签页，打开「Scan for Devices」开关，在列表中找到设备 A 并点击「Connect」。
3. 连接成功后，双方在「Message」输入框发送文本即可互收消息（可在「Log」中查看收发记录）。
4. 如需自定义 UUID，在对应标签页的「Pairing Config」中修改，并重启广播 / 扫描使其生效。

### 与 Web Bluetooth 页面联调

1. 在 iPhone 上打开 **Broadcast** 标签页并开始广播。
2. 在桌面 Chrome / Edge 中通过 `https://` 或 `localhost` 打开 `BLE-POC-Web.html`。
3. 页面中的 Service / Characteristic UUID 保持与手机一致，点击「Scan & Connect」选择设备。
4. 连接后可读、订阅通知并向手机发送文本。

> Web Bluetooth 仅支持 Chrome / Edge 等 Chromium 内核浏览器，Safari 与 Firefox 不支持。

## 权限说明

App 在 Info.plist 中声明了以下蓝牙用途（由 `GENERATE_INFOPLIST_FILE` 生成）：

- `NSBluetoothAlwaysUsageDescription`：用于扫描并连接附近的蓝牙设备
- `NSBluetoothPeripheralUsageDescription`：用于向附近设备广播蓝牙服务

## 注意事项

- 蓝牙功能无法在模拟器中完整验证，请使用真机。
- 免费开发者账号签名的 App 有效期约 7 天，过期后需重新安装。
- 外设的广播名与配对码（`BLE-POC-<pairCode>`）用于人工区分设备，不提供真正的配对加密。
