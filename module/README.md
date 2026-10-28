# 模块：LEA Enabler

Xposed 模块，注入蓝牙进程（`com.android.bluetooth`），负责三件事：

1. **UUID 家族注入** —— 让系统认为这台设备支持完整的 LE Audio profile
2. **白名单绕过** —— 让 `isLeAudioAllowed` 返回 true
3. **自动连接 + 命令通道** —— 蓝牙开启后自动拉起 LE Audio；App 可通过广播即时下发命令

## 构建

```bash
python -X utf8 tools/get_sdk.py    # 首次：下载 aapt2 / android.jar
python -X utf8 tools/build.py      # 产出 dist/LEAEnabler.apk
```

产物会自动用 `build/lea.keystore` 签名（首次构建时自动生成）。

## Hook 点

| 类 / 方法 | 作用 |
| --- | --- |
| `RemoteDevices$DeviceProperties#setUuids` / `#getUuids` | 注入完整 LE Audio UUID 家族 |
| `AdapterService#isLeAudioAllowed(BluetoothDevice)` | 绕过厂商白名单 |
| `AdapterService#createBond(...)` | 记录配对来源（可选强制 LE 传输） |
| `BluetoothDevice#getUuids` | 公共 API 侧补 UUID |
| `AdapterProperties#onBluetoothReady` | 蓝牙就绪后自动连 LE Audio |
| —（广播接收器） | `com.dsh.lea.action.CMD` 命令通道 |

## 命令通道

```bash
# 探活
adb shell am broadcast -a com.dsh.lea.action.CMD --es cmd ping --es req t1

# 立即连接 LE Audio
adb shell am broadcast -a com.dsh.lea.action.CMD \
  --es cmd connect --es mac 84:1E:1A:0C:74:04 --es req c1

# 删除配对（僵尸配对清理）
adb shell am broadcast -a com.dsh.lea.action.CMD \
  --es cmd forget --es macs "MAC1,MAC2" --es req f1
```

结果通过 `com.dsh.lea.action.RESULT` 广播回传（extras：`req`、`msg`）。

> ⚠️ 命令通道**不做发送方鉴权**（蓝牙进程里拿不到可靠的 sender uid）。
> 任何能发广播的 App 都可以触发；影响限于删除蓝牙配对。自用设备可接受，公共场景请自行加 token 校验。

## 属性

| 属性 | 作用 |
| --- | --- |
| `persist.sys.lea.enable` | 模块总开关 |
| `persist.sys.lea.match` | 设备名关键字（默认 `OpenFeel`） |
| `persist.sys.lea.auto_mac` | 每次蓝牙开启自动连接的 LE 地址 |
| `persist.sys.lea.target_macs` | 参与 UUID 注入的目标地址（逗号分隔） |
| `persist.sys.lea.forget_mac` / `connect_mac` / `bond_mac` | 一次性动作（执行后自动清空） |

## 安全边界

模块**只做系统侧适配**：改属性、调用蓝牙栈 Java API、删配对。
不发送任何厂商私有协议命令，不与耳机固件交互。
