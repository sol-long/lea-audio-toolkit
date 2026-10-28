# LEA Audio Toolkit

让 **第三方 LE Audio 耳机**（不走 OPPO/一加白名单）在 ColorOS 上同时获得：

- 🎧 **LC3 音频**（LE Audio unicast，48kHz / 高可靠）
- 📱 **系统设备卡片 + 电量显示**（来自同时保留的经典蓝牙链路）
- 🔧 **厂商 App 可用**（经典链路在，Roselink 等 App 仍能连）
- 🔁 **重启后自动恢复**（不用每次手动配对）

> 本项目在一加 Ace 3V（ColorOS 16 / Android 16）+ ROSE OpenFeel MK2（中科蓝讯 BT8952F）上开发并验证。
> 原理是通用的，但 hook 点与系统属性是 ColorOS 特有的，其它机型需要自行适配。

---

## 原理

ColorOS 只允许自家白名单耳机使用 LE Audio。第三方耳机配对后：

- 能看到 LE Audio 配对，但**连不上**（`device not a valid group member`）
- 或者能连上，但**只是 LE-only**：没有设备卡片、没有电量、App 连不上

本项目的做法是**两条链路并存**：

| 链路 | 作用 | 承载 |
| --- | --- | --- |
| 经典（BR/EDR） | 设备卡片、电量、厂商 App | A2DP（AAC） |
| LE Audio（LE） | 实际播放的音频 | LC3 unicast |

关键开关是系统属性 `persist.bluetooth.enable_dual_mode_audio=true`，让系统允许同一设备同时保持两条链路。

模块负责三件事：

1. **伪造 UUID 家族**：在 `DeviceProperties#setUuids` 注入完整 LE Audio UUID（含 CSIS `0x1846`，缺了这个会被 `checkIfGroupMember` 判定「不是组员」）
2. **绕过白名单判定**：hook `AdapterService#isLeAudioAllowed`
3. **自动拉起 LE Audio**：hook `AdapterProperties#onBluetoothReady`，每次蓝牙开启后主动 `LeAudioService#connect()`

---

## 仓库结构

```
module/          Xposed 模块（跑在蓝牙进程里）
  app/src/...    LeaEnabler / BatteryProbe / SppProbe
  tools/         build.py（aapt2→javac→d8→apksigner）、get_sdk.py

app/             调试 App「LEA Toolbox」（5 个 Tab）
  app/src/...    WizardPage / StatusPage / ActionsPage / ScanPage / DiagPage
  tools/         build.py、selftest.py

tools/           btsnoop 抓包分析（协议逆向用）
docs/            FINDINGS.md（技术记录）、USAGE.md（使用手册）
```

---

## 快速开始

### 1. 编译模块

```bash
cd module
python -X utf8 tools/get_sdk.py      # 首次：下载 aapt2 / android.jar（约 120 MB）
python -X utf8 tools/build.py        # 产出 dist/LEAEnabler.apk
```

### 2. 编译 App

```bash
cd app
python -X utf8 tools/build.py        # 复用 ../module/sdk，产出 dist/LEAToolbox.apk
```

### 3. 安装与配置

1. 安装模块 APK，在 LSPosed / Vector 里**启用**，作用域选「蓝牙」
2. 安装 App，打开 → **向导** Tab → 跟着 7 步走
3. 完成

详细步骤见 [docs/USAGE.md](docs/USAGE.md)。

---

## 前置条件

- Android 13+（LE Audio 需要），ColorOS / OxygenOS 已验证
- Root + LSPosed（或 Vector / 其它 libxposed API 102 兼容框架）
- 支持 LE Audio 的耳机
- 能切换 LC3/AAC 模式的厂商 App（如 Roselink）

---

## 这个方案解决什么、不解决什么

**解决**

- LC3 音频可用（48kHz、低延迟）
- 设备卡片 + 电量 + 厂商 App 同时保留
- 重启自动恢复

**不解决**

- **没有标准 BAS 电量**：这副耳机不提供标准电池服务，电量只能靠厂商 App 的通知
- **卡片上的编码器徽标仍显示 AAC**：系统 UI 反映的是经典链路，实际音频走 LC3
- **`-LEA` 身份出现在「可用设备」区**：ColorOS 对双模设备的显示怪癖

---

## 文档

| 文件 | 内容 |
| --- | --- |
| [docs/USAGE.md](docs/USAGE.md) | 首次配置、日常切换、7 类故障排查、配置速查 |
| [docs/FINDINGS.md](docs/FINDINGS.md) | 完整技术记录：白名单绕过、双模发现过程、广播通道、两次恢复出厂验证 |

> 文档中的 MAC 地址均为开发用示例设备，请替换为你自己的。

---

## 免责声明

- 本项目通过 hook 系统蓝牙栈实现，**属于非常规用法**，可能导致蓝牙异常、耗电增加
- 仅在你自己的设备上使用；因使用导致的任何问题由使用者自行承担
- 不包含任何厂商私有协议实现，不与耳机固件交互（只做系统侧适配）

## 许可

代码以 [MIT](LICENSE) 发布。
`module/libs/libxposed-api.jar` 来自 [libxposed/api](https://github.com/libxposed/api)（Apache-2.0）。
