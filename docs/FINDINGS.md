# OpenFeel MK2 × ColorOS 16 调研与实测记录

> 设备：OnePlus PJF110（一加 Ace 3 Pro）/ Android 16 / ColorOS 16
> 耳机：ROSESELSA 弱水时砂 OpenFeel MK2（中科蓝讯 BT8952F）
> 记录时间：2026-10-28

---

## 1. 结论速览

| 目标 | 状态 | 说明 |
|---|---|---|
| 让耳机走 LE Audio / LC3 | ✅ 已实现 | 需要配对 **`-LEA` 身份**，且不能保留经典配对 |
| 系统里显示"已连接/设备卡片" | ❌ 原生不支持 | LE-only 设备不出现在蓝牙列表，ColorOS 用产品目录渲染 |
| 连接弹窗 | ❌ 原生不支持 | 同上，需伪装成官方产品 |
| 电量显示 | ❌ 不可能走标准通道 | **耳机没有 Battery Service（0x180F）**，只能逆向厂商协议 |

---

## 2. 为什么一开始连不上：三个坑

### 坑 1：连错了身份

耳机有两套蓝牙身份，地址不同：

| 身份 | 地址 | 类型 |
|---|---|---|
| `ROSE OpenFeel MK2` | `84:1E:1A:0C:74:51` | 经典（BR/EDR） |
| `ROSE OpenFeel MK2-LEA` | `84:1E:1A:0C:74:04` | LE Audio |
| `ROSE OpenFeel MK2-LEA` | `84:1E:1A:0C:B1:7F` | LE Audio（另一只） |

强行把 LE 配对加到经典地址上 → 35 秒超时（`HCI 0x08 Connection Timeout`）。
**必须配 `-LEA` 那个。**

### 坑 2：白名单在原生层，而且分两道

1. `libbluetooth_jni.so` 里的 `OplusLeAudioWhiteList`（OPPO 自研产品表，硬编码 OPPO/一加型号）
2. 组身份校验：`client.cc` 的 `checkIfGroupMember` → 失败报
   `disconnectInvalidDevice: device not a valid group member`

### 坑 3：UUID 被剥，而且是一族

厂商只给白名单设备放行 LE Audio UUID。只补 `0x184E` 会在**组身份**这步被踢；
必须补整族：

| UUID | 作用 |
|---|---|
| `0x184E` ASCS | LE Audio 开关 / 传输策略 |
| `0x1846` CSIS | **组身份**（CSIP 建组的前提） |
| `0x1844` VCS | 音量控制 |
| `0x1850` PACS | 音频能力 |
| `0x184F` BASS | 广播音频 |

---

## 3. 最终可用的方案（LEA Enabler v0.8）

纯 Java 层 LSPosed 模块，作用域仅 `com.android.bluetooth`，不碰 `.so`、不写系统分区。

### 3.1 三个 hook

| hook 点 | 作用 |
|---|---|
| `RemoteDevices$DeviceProperties#setUuids` / `#getUuids` | 给 `-LEA` 身份补整族 UUID |
| `AdapterService#isLeAudioAllowed(BluetoothDevice)` | 强制返回 true |
| `AdapterService#createBond(BluetoothDevice,int,...)` | （可选）把配对 transport 改成 LE |

> 注意：本机 `DeviceProperties` 是**单字段 `mUuids`**（不是参考仓库那种
> `mUuidsBrEdr`/`mUuidsLe` 拆分），hook 点必须对着本机 ROM 核。

### 3.2 关键属性

```bash
persist.sys.oplus.bt.le_audio_white_list_enable = false
persist.bluetooth.leaudio.allow_list_extend = "ROSE OpenFeel MK2-LEA"
persist.sys.lea.match      = OpenFeel     # 名字包含
persist.sys.lea.suffix     = -LEA         # 只碰 LE 身份，别污染经典身份
persist.sys.lea.force_le_bond = 0         # 不再强改 transport
persist.sys.lea.connect_mac = 84:1E:1A:0C:74:04   # 一次性触发连接（见下）
```

### 3.3 连不上的真正原因：不会自动回连

`bt_config.conf` 里 LE 身份是 `LeAudioAutoconnect = 0`，而 `-LEA` 设备**不出现在蓝牙列表里**，
用户点不到 → 永远连不上。

解决：模块提供 `persist.sys.lea.connect_mac`，在 BT 进程启动后调
`LeAudioService#getLeAudioService().connect(device)`，成功即清空该属性。
（`bt_config.conf` 里改 `LeAudioAutoconnect = 1` 实测无效。）

### 3.4 成功的标志

```
Profile: LeAudioService
  currentlyActiveGroupId: 1
  activeState: ACTIVE_STATE_ACTIVE
  group lead: XX:XX:XX:XX:74:04
dumpsys audio: Devices: ble_headset(20000000)
日志: active configuration name: Two-OneChan-SnkAse-Lc3_48_2_155octs_High_Reliability_2
```

= LC3、48 kHz、双声道、155 字节/帧。

---

## 4. 为什么 UI 什么都没有

ColorOS 的耳机体验由三个独立 UI 面组成，全部依赖 `com.oplus.melody` / `com.heytap.mydevices`
的**产品目录识别**（productId / 型号表）：

| UI 面 | 宿主 | 不在目录里的后果 |
|---|---|---|
| 快速连接弹窗 / 胶囊 | 系统蓝牙 + melody | 不出现 |
| "我的设备"设备空间卡片 | `com.heytap.mydevices` | 不出现 |
| 蓝牙详情页功能面板 | `com.oplus.melody` | 不出现 |

再加上纯 LE 配对没有经典 profile，蓝牙列表里连设备条目都不显示。

**这不是 bug，是产品目录机制。**

---

## 5. 电量：标准通道已确认不可行

### 5.1 实测证据

注入 `0x180F` 到 `BluetoothDevice#getUuids()` 后，系统真的去连了：

```
12:17:14.221  BatteryStateMachine: make for device 74:04     ← 以前这步都不发生
12:17:14.236  E BatteryStateMachine: No battery service       ← 连上发现真没有
```

（以前是 `Remote does not have Battery UUID`，连都不连，所以此前的"没有 BAS"结论不可信；
这次是**连上去确认**。）

### 5.2 GATT 服务清单（完整发现，694 条记录）

```
0x1800 GAP        0x1801 GATT
0x1850 PACS       0x1846 CSIS      0x1853 TBS
0x1844 VCS        0x1849 MCS       0x184C MCP
0xF000 厂商       0xAA15 厂商      0xBB15 厂商
0xAFAF 厂商       0xFCF1 厂商      0xFEF3 厂商
```

**`0000180f` 命中 0 次。**

经典侧 SDP：

```
1101 SPP / 110b A2DP / 110e AVRCP / 111e HFP / 0cf12d31 厂商
```

也没有 180F。**结论：耳机整体不实现 Battery Service。**

### 5.3 电量只能走厂商协议

App（Flutter，逻辑在 `libapp.so`）里出现的通道：

| UUID | 用途猜测 |
|---|---|
| `00001101` | SPP/RFCOMM |
| `0cf12d31-…` / `0cf12d32-…` | 厂商自定义（SDP 里也出现） |
| `0000ae00/ae01/ae02` | 厂商 GATT |

**下一步**：抓 btsnoop，看 App 读电量时走的是 SPP 还是 GATT 私有服务，解出帧格式。

---

## 6. 参考项目

| 项目 | 价值 |
|---|---|
| [tosasitill/Melody.Az100_Impl](https://github.com/tosasitill/Melody.Az100_Impl) | 同机型同问题的完整逆向记录（替换 Melody 详情页自绘电量卡片，电量走 Airoha SPP） |
| [jun430/MelodyPlus_ng](https://github.com/jun430/MelodyPlus_ng) | 把第三方耳机伪装成 OPPO Enco X3 注入 melody 注册表 → 原生弹窗/胶囊/详情页 |
| [Andrea-lyz/MelodyCodecTweaker](https://github.com/Andrea-lyz/MelodyCodecTweaker) | 在 melody 面板注入编码器 / LE Audio 控制，含 melody↔settings↔bluetooth 的 IPC |
| [ByteJuno/HuaweiPods-ColorOS](https://github.com/ByteJuno/HuaweiPods-ColorOS) | 接全四个 ColorOS 接入点（弹窗、我的设备、电量通道、流体云） |
| [bqj6666/FxxkMoondrop](https://github.com/bqj6666/FxxkMoondrop) | GMS Fast Pair 半屏弹窗 + Settings 详情页注入的通用路线 |

**关键认知**：没有"通用第三方 LE Audio 耳机全 UI 接管"的现成模块，
所有方案都要为具体品牌写协议适配器。

---

## 7. 工具与命令

```bash
# Vector（LSPosed 2.x）CLI：装完 APK 会被自动禁用，用它重新启用
su -c '/data/adb/lspd/cli modules ls'
su -c '/data/adb/lspd/cli modules enable com.dsh.lea'

# 查看模块日志（写在蓝牙栈日志里，不在 logcat）
LOG=$(ls -t /data/misc/bluetooth/logs/bluetooth_*.log | head -1)
su -c "grep -a LeaEnabler $LOG | tail -20"

# 触发一次 LE Audio 连接
su -c 'setprop persist.sys.lea.connect_mac 84:1E:1A:0C:74:04'
# 然后重启蓝牙
```

---

## 8. 回归：模块曾把经典连接挤掉（v0.9 已修）

### 症状

- 蓝牙设置里点耳机没反应
- Roselink 显示连接成功，几秒后自己断开

### 证据

```
stack_disconnection_event_report: address=...74:51, reason=19        <- 远端主动断
port_rfc_closed: RFCOMM connection closed, reason=Peer connection failed[16], UUID=0x1101
```

### 根因

两个 hook 太激进：

| hook | 问题 |
| --- | --- |
| `AdapterService#isLeAudioAllowed` | 对**所有设备**强制返回 true -> 系统以为经典身份也支持 LE Audio，去抢 LE 链路 |
| `BluetoothDevice#getUuids` | 往目标设备注入 0x180F + LE 整族 -> BatteryService / LE Audio 都来抢链路 |

于是耳机侧把经典 ACL 断掉（reason 19），表现为“连上几秒就断”。

### 修复（v0.9）

- `isLeAudioAllowed`：默认**关闭**，且只对 `persist.sys.lea.target_macs` 里的地址生效
- `getUuids` 注入：默认**关闭**（`splice_bas=0`）
- `setUuids` 整族注入：只对名字含 `-LEA` 的身份生效，经典身份完全不碰
- `createBond` 改 transport：默认 0（关闭）

### 验证

```
A2dpStateMachine for 84:1E:1A:0C:74:51 (Active)
  mConnectionState: CONNECTED
  mCodecConfig: {AAC, 44100, 16bit, STEREO}

会话 12:28:52 起：连接完成 1 次（reason=0），断开事件 0 次，SPP 关闭 0 次
模块日志：setUuids ... name=ROSE OpenFeel MK2 target=false   <- 未干预
```

### 结论

模块现在**默认惰性**：不影响日常 AAC 使用。要用 LC3 时再显式打开：

```bash
su -c 'setprop persist.sys.lea.target_macs 84:1E:1A:0C:74:04,84:1E:1A:0C:B1:7F'
su -c 'setprop persist.sys.lea.connect_mac 84:1E:1A:0C:74:04'
```

---

## 9. 电量协议逆向（三轮子 agent，2026-10-28）

### 9.1 静态分析（APK, RoseLink 3.8.2）

- 控制协议在 **Dart** 侧（`roselink_bluetooth` 自研栈），Java/dex 里搜 `battery` 命中 0
- 载荷是 **LTV（Length-Type-Value）序列**，这一层**无 CRC**
- 传输与协议解耦，三条通道：`[connect][bt][send]`(SPP) / `[connect][ble][send]`(GATT) / `[connect][usb-hid][send]`
- GATT UUID：服务 `0xAE00`、写 `0xAE01`、通知 `0xAE02`；备选 `0cf12d31/0cf12d32`
- 电量模型：`_batteryRawValue` → `_parseBatteryPercent`；**1 字节与 2 字节并存**（按 len 分派）；
  `batteryCharging` 独立；左/右/仓靠 **type** 区分，不靠角色位
- release 版**不打协议日志**（`[commander][receiver]` 等字符串存在，但 logcat 里不输出）

### 9.2 抓包分析（btsnoop .cfa × 6）

| 项 | 结论 |
| --- | --- |
| App 的 SPP 通道 | SDP 记录：UUID128 `0cf12d31-…` + SPP + RFCOMM **server channel 6 → DLCI 12** |
| DLCI 12 数据 | **0 个 UIH 数据帧**（只有 SABM/UA 握手；s7 里静默 485 秒） |
| ATT / LE 连接 / LE 广播 | **全部为 0** → 这份厂商日志**只录 BR/EDR，不含 LE** |
| 跨 ACL 分片 | PB=0b01 续包数 = 0，无分片 |
| 电量字节反查 | `5D 55 64` 组合 0 命中；单独 0x5D 命中处均为 L2CAP identifier 或 UA 的 FCS |

> RFCOMM 帧格式确认：`Address | Control | Length(1或2B) | [Credit] | Information | FCS`。
> P/F 置位（0xFF）时多一个 Credit 字节——之前的“1 字节载荷”全是 credit 或 FCS。

### 9.3 芯片协议（公开资料）

高置信指向 **中科蓝讯 AB-Mate / AB-Link**：

```
SEQ  CMD  TYPE  FRAG  LEN  PAYLOAD
```

- `0x27` = 查设备信息（TLV），`0x28` = 设备主动上报
- info ID：`0x01`=电量、`0xFE`=能力集、`0xFF`=最大包长
- **必须先握手 `00 27 01 00 02 FF 00`（问 MTU），否则固件不响应任何指令**
- 官方说明：Android 走 SPP、iOS 走 GATT，帧格式相同
- 危险命令：`0x24` 恢复出厂、`0x2F` 清配对记录 —— 不要乱发

### 9.4 当前卡点

- App 走哪条通道、发了什么，**始终没被捕获到**：`.cfa` 不含 LE，SPP 通道无数据帧，release 版无日志
- 结论：要么拿到**含 LE 的完整 btsnoop**，要么**自己实现客户端**跑一遍协议

### 9.5 下一步（二选一）

**A. 自己实现 GATT/SPP 客户端**（推荐）
在模块里连 `AE00`，订阅 `AE02`，先发握手 `00 27 01 00 02 FF 00`，观察回包；
按 LTV 解析，拿到 `type=0x01` 即电量。完全自主，不依赖 App。

**B. 重新抓包**
必须先确认抓包能录到 LE（`.cfa` 不行，需要用开发者选项的完整 `btsnoop_hci.log`），
再「开抓包 → 杀 App → 启动 → 进出仓」重抓，判据是 `--dlci 12 --data-only` 帧数 > 0。

---

## 10. 自己写探针的实测结果（2026-10-28 下午）

### 10.1 BR/EDR GATT（经典模式）

`connectGatt(..., TRANSPORT_BREDR)` 能连上，但服务表几乎是空的：

```
services count=1
  SVC 00001801 (GATT)
    CHR 0000ff14 props=0x10   (NOTIFY)
```

→ 经典模式下 BR/EDR GATT 不暴露厂商服务。

### 10.2 LE GATT（LC3 模式）

`connectGatt(..., TRANSPORT_LE)` 连上后拿到 10 个服务：

| 服务 | 内容 |
| --- | --- |
| `0000f000` | **f001(READ) / f002(READ) / f003(READ+WRITE_NR+NOTIFY)** ← 唯一可写+通知 |
| `000000fe` | f1(0xc) / f2(NOTIFY) / ff20(WRITE) |
| `0000ff12` | ff14(NOTIFY) / ff15(READ+WRITE) |
| `00001800/184e/1850/1846/1853/1844/1855` | GAP + LE Audio（ASCS/PACS/CSIS/TBS/VCS） |

读取结果：

```
f002 -> 03 00
f001 -> 42 06     ← 0x0642 = 1602 = 制造商 ID（与 bt_config 里的 Manufacturer 一致）
ff15 -> 空
```

订阅 `f003` 成功（CCCD status=0），写蓝讯握手 `00 27 01 00 02 FF 00` 成功（rc=0），
但**没有任何通知返回** → 说明这套 GATT 通道不是蓝讯 AB-Mate 格式。

### 10.3 SPP 通道（经典模式）

自己实现 SPP 客户端，用 SDP 里的 UUID `0cf12d31-fac3-4553-bd80-d6832e7b3931` 连接**成功**：

```
tx handshake -> 00 27 01 00 02 FF 00
rx          -> DD 00 02 12 01 F2 AA      ← 设备回应了，但不是蓝讯格式
tx queryBattery -> 00 27 01 00 02 01 00
rx          -> 无回应
```

`DD 00 02 12 01 F2 AA` 更像自研帧：**头 0xDD + 长度 0x0002 + 数据 12 01 + 校验 F2 AA**。
协议未解，暂时不再盲试（避免误触发 0x24 恢复出厂 / 0x2F 清配对）。

### 10.4 电量其实有现成来源 ⭐

RoseLink 自己把电量推到了常驻通知（`channel=device_status`，id=1001）：

```
android.title = OpenFeel MK2
android.text  = 左耳: 87% 右耳: 84% 充电仓: 100%
```

数值会随耳机电量更新（曾观察到 93→90→87），**可以用 `dumpsys notification --noredact` 直接读**。

### 10.5 结论

- 系统级电量（BAS）不存在，无法走标准通道
- 厂商协议（SPP `0xDD` 帧 / GATT `F000`）尚未解开，继续逆需更多迭代，且有误发危险命令的风险
- **最省事的电量来源 = 解析 App 的通知**，无需逆协议

### 10.6 后续可选

1. 写一个轻量桥接：读通知 → 把电量塞进系统 UI（需 hook SystemUI / melody）
2. 继续逆 `0xDD` 帧协议（需更多样本，风险自负）
3. 就用 App 的通知（已有，零成本）

---

## 11. 方案 1 成功：双模共存（LC3 音频 + 经典链路）⭐

**日期**：2026-10-28 14:40-14:47
**结论**：这副耳机支持「经典 A2DP + LE Audio」同时在线，Android 侧打开 dual-mode 后两者共存，
媒体音频走 LE Audio/LC3，经典链路继续承担 App/电量/设备卡片。

### 11.1 配置

```bash
# 双模开关（关键）
setprop persist.bluetooth.enable_dual_mode_audio true

# 白名单（之前已设）
setprop persist.sys.oplus.bt.le_audio_white_list_enable false

# 模块自动拉起 LE Audio（每次蓝牙开启）
setprop persist.sys.lea.auto_mac 84:1E:1A:0C:74:04
```

配对状态：**两条都要**

```
84:1E:1A:0C:74:51 [BR/EDR] ROSE OpenFeel MK2       ← 经典（App/电量/卡片）
84:1E:1A:0C:74:04 [  LE  ] ROSE OpenFeel MK2-LEA   ← LE Audio（LC3 音频）
```

### 11.2 实测证据

```
LeAudioService:  currentlyActiveGroupId: 1
                 mActiveAudioOutDevice: 74:04
                 isDualModeAudioEnabled: true

A2dpStateMachine for 74:51: CONNECTED (AAC 44.1k)

MUSIC 流 → Devices: ble_headset(20000000)          ← 媒体走 LE Audio

LC3: Two-OneChan-SnkAse-Lc3_48_2_155octs_High_Reliability_2
ASE: Streaming (0x4)

蓝牙界面: ROSE OpenFeel MK2 [AAC] — 使用中，电量: 100%
```

### 11.3 关键发现

1. **耳机支持双链路并存**：在经典连着的同时，LE 侧（74:04）一直在广播，
   `btm_ble_process_adv_addr: bda=74:04` 可观测（需先触发扫描）
2. **`LeAudioAutoconnect=1` 无效**，必须程序化 `LeAudioService#connect()`
3. **自动重连实现**：hook `AdapterProperties#onBluetoothReady()`，
   每次蓝牙开启后 6 秒调用 connect —— 实测蓝牙开关后 `currentlyActiveGroupId` 自动回到 1
4. **LEA 身份必须手动配对一次**（`createBond(TRANSPORT_LE)` 或设置里点它），之后保持

### 11.4 遗留小问题（纯 UI，不影响功能）

- 设备卡片上的编码器徽标显示 **AAC**（因为卡片反映经典链路），实际媒体走 LC3
- `ROSE OpenFeel MK2-LEA` 出现在「可用设备」区但标着「使用中」（ColorOS 双模显示怪癖）

### 11.5 待验证

- 重启手机后两条链路是否都能自动恢复（`auto_mac` 应保证 LE Audio 起来）
- 长时间播放的稳定性（断连/单耳/杂音）

---

## 12. 模块命令通道（广播，2026-10-28）

### 12.1 为什么要做

属性通道（`persist.sys.lea.*`）只有在**蓝牙进程启动时**才会被模块读取，
所以 App 改完配置必须重启蓝牙（约 10 秒、会断音频）才生效。
广播通道让命令**立刻执行**，并能把结果回传给 App。

### 12.2 协议

**App → 模块**
```java
Intent i = new Intent("com.dsh.lea.action.CMD");
i.putExtra("cmd", "ping|forget|connect|bond");
i.putExtra("mac",  "84:1E:1A:0C:74:04");  // connect / bond
i.putExtra("macs", "A,B");                // forget
i.putExtra("req",  "任意请求 id");
sendBroadcast(i);
```

**模块 → App**
```java
// action = com.dsh.lea.action.RESULT，setPackage("com.dsh.leatool")
// extras: req（回显）、msg（"pong" / "connect=true" / "AA:BB:..=false;"）
```

实测返回值：
| 命令 | 返回 |
| --- | --- |
| ping | `pong` |
| connect | `connect=true` |
| forget（不存在的 MAC） | `AA:BB:CC:DD:EE:FF=false;` |

### 12.3 实现要点（踩过的坑）

1. **`ActivityThread.currentApplication()` 在蓝牙进程里是 null**
   → 必须降级到 `currentActivityThread().getSystemContext()`，否则注册接收器直接失败
2. **模块早期日志丢失**：`onPackageLoaded` 执行时机早于蓝牙日志文件创建，
   那些 `log()` 不会出现在 `bluetooth_*.log`。排查时用 `setprop` 打标记更可靠
3. **`getSendingUid()` 不可用**（隐藏 API 且在蓝牙进程里拿不到真实发送方），
   因此发送方校验只能记录、不能拦截。当前策略：**不校验**，仅记日志。
   如需收紧，可改为「命令带一次性 token」

### 12.4 后续

向导页（App 第 5 个 Tab）将使用这套通道实现：
删除僵尸配对、立即连接、立即配对，全部无需重启蓝牙。


---

## 13. 交付清单

| 产物 | 路径 | 说明 |
| --- | --- | --- |
| **使用手册** | `docs/USAGE.md` | 首次配置 / 日常使用 / 故障排查 / 配置速查 |
| 模块 APK | `dist/LEAEnabler.apk`（29 KB） | UUID 注入 + 自动连接 + 广播命令通道 |
| 工具 App | `../lea-toolbox-java/dist/LEAToolbox.apk`（76.5 KB） | 5 个 Tab：状态 / 操作 / 探测 / 诊断 / 向导 |
| 抓包工具 | `tools/parse_rfcomm.py` 等 | btsnoop 全链路解析（含打码地址） |
| 探针 | `BatteryProbe` / `SppProbe` | 电量协议逆向用（默认不触发） |

### 当前验证状态

- 双模共存（LC3 音频 + 经典链路）重启后自动恢复
- 恢复出厂全流程两次验证通过
- 向导 0→6 步真机跑通，自动写入 auto_mac/target_macs
- 广播命令通道（ping/connect/forget）
- App 五个 Tab 全部可用
- 电量只能靠 App 通知（厂商协议未解）
