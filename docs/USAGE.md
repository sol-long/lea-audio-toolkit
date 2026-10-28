# LEA Toolbox 使用手册

> 适用：一加 Ace 3V / ColorOS 16 / ROSE OpenFeel MK2（中科蓝讯 BT8952F）
> 目标：让第三方 LE Audio 耳机在 ColorOS 上**同时**获得「系统卡片 + 电量 + LC3 音频 + App 可用」

---

## 一、原理（一句话）

ColorOS 默认只让自家白名单耳机用 LE Audio。这套方案做两件事：

1. **模块**（Xposed，跑在蓝牙进程里）伪造 UUID 家族，绕过白名单判定
2. **双模并存**：经典链路负责「卡片 / 电量 / App」，LE Audio 链路负责「LC3 音频」

两条链路同时在线，互不干扰。

---

## 二、首次配置（用 App 的「向导」Tab，7 步）

### 前置条件（向导第 0 步会自动检查）

| 项 | 要求 |
| --- | --- |
| Root | KernelSU / ReSukiSU 已授权给 LEA Toolbox |
| 模块 | `com.dsh.lea` 已启用（Vector / LSPosed） |
| 模块已注入 | 蓝牙日志里有 `[LeaEnabler]` 行（没有就重启一次蓝牙） |
| 双模开关 | `persist.bluetooth.enable_dual_mode_audio = true` |
| 白名单 | `persist.sys.oplus.bt.le_audio_white_list_enable = false` |
| 蓝牙 | 已开启 |
| Roselink | 已安装（用于切 LC3 / AAC 模式） |

点「一键修复」可自动补齐能自动改的项（模块启用 / 双模 / 白名单 / 开蓝牙 / 重启蓝牙）。

### 七步流程

| 步 | 你要做什么 | 自动判据 |
| --- | --- | --- |
| 1 | 确认耳机在 **AAC 模式**（Roselink 里看） | Bonded 里有 `[BR/EDR]` + 名字含关键字，且 A2DP CONNECTED |
| 2 | 蓝牙设置里配对 **`ROSE OpenFeel MK2`**（不带 -LEA） | Bonded 出现 `[BR/EDR]` |
| 3 | Roselink 切 **LC3**（耳机会重启，等 10 秒） | 日志出现 LE 广播（地址可能被打码，按尾两段匹配）或列表出现 `-LEA` |
| 4 | 蓝牙设置页点「刷新」→ 点 **`...-LEA`**（双耳两条都要） | `[LE]` + `-LEA` 数量 ≥1（双耳=2） |
| 5 | **播放一首歌** | 组 ≠ -1 + 媒体走 `ble_headset` + ASE Streaming + Lc3 + 经典仍 CONNECTED |
| 6 | 什么也不用做 | 自动写入 `auto_mac` / `target_macs` |

第 6 步完成后，以后**开蓝牙会自动连 LE Audio**，不用再手动配。

---

## 三、日常使用

### 正常状态应该是什么样

| 检查 | 期望值 |
| --- | --- |
| 蓝牙设置页 | `ROSE OpenFeel MK2` 显示「使用中，电量 xx%」 |
| 音频路由 | `dumpsys audio` 里 MUSIC → `ble_headset` |
| LE Audio 组 | `currentlyActiveGroupId: 1` |
| 编码 | 日志 `active configuration name` 含 `Lc3_48_2_...` |
| 经典链路 | `A2dpStateMachine: CONNECTED`（保留卡片和电量） |

### 在两种模式间切换

| 想用 | 操作 |
| --- | --- |
| **LC3（低延迟）** | Roselink 切 LC3 → 耳机重启 → 自动回连（模块 `onBluetoothReady` hook 会拉起） |
| **AAC（兼容/省心）** | Roselink 切 AAC → 耳机重启 → 经典链路自动回连 |

切换后如果没自动连：把耳机放回仓再取出，或在 App「操作」页点「触发连接」。

---

## 四、故障排查

### A. 配对卡住 / 一直失败

**症状**：点配对后等 30 秒没反应，或日志出现 `status: 9`（认证失败）

**原因**：多半是**僵尸配对**——耳机恢复出厂后，手机里还留着旧地址的配对，系统一直去连旧地址。

**解决**：向导第 2/4 步 → 「僵尸配对清理」→ 勾选要删的（默认不勾）→ 删除。
删除走模块广播，**立刻生效，不用重启蓝牙**。

### B. 列表里找不到 `ROSE OpenFeel MK2`（只有 -LEA）

**原因**：耳机在 LC3 模式，此时**不广播经典**。

**解决**：Roselink 切回 AAC。如果 Roselink 连不上：两只耳机放回仓、开盖、长按仓键 10 秒复位，默认回 AAC。

### C. 只配到一条 -LEA（单耳）

**原因**：双耳是两条独立配对，只点了一条。

**解决**：回第 4 步，再点另一条 `-LEA`。向导会提示「双耳齐全（期望 2 条）」。

### D. 配好了但没声音 / 仍是 AAC

按顺序检查：

1. `currentlyActiveGroupId` 是否 = 1（App 状态页）
2. 媒体是否真的在播（ASE 只有推流时才 Streaming）
3. `dumpsys audio` 的 MUSIC 是否走 `ble_headset`
4. 蓝牙日志里 `active configuration name` 是否含 `Lc3`

四项都满足才算成功。

### E. 没有电量显示

**原因**：电量来自经典链路 + App 通知。

**解决**：
- 确认经典链路 CONNECTED（`A2dpStateMachine`）
- 打开 Roselink 让它推一次设备状态通知
- App 状态页第 5 项会显示 `左耳: xx% 右耳: xx% 充电仓: xx%`

### F. App 显示「未授权 / 只读模式」

**原因**：ReSukiSU 管理器没运行，su 授权弹窗弹不出来。

**解决**：先打开一次 ReSukiSU，再回 App 点「重新检测 ROOT」。

### G. 模块装了但没生效

**原因**：模块 hook 只在蓝牙进程启动时注入。

**解决**：App「操作」页点「重启蓝牙」，或手动：
```bash
svc bluetooth disable; sleep 2; kill -9 $(pidof com.android.bluetooth); svc bluetooth enable
```
向导第 0 步会检查「模块已注入（日志含 [LeaEnabler]）」。

---

## 五、配置项速查

### 模块属性（`persist.sys.lea.*`）

| 属性 | 作用 | 典型值 |
| --- | --- | --- |
| `auto_mac` | 每次开蓝牙自动连的 LE 地址 | `84:1E:1A:0C:74:04` |
| `target_macs` | 参与 UUID 注入的目标地址（逗号分隔） | `…74:04,…B1:7F` |
| `match` | 设备名关键字 | `OpenFeel` |
| `enable` | 模块总开关 | `1` |
| `forget_mac` | 一次性删配对（执行后自动清空） | `MAC1,MAC2` |
| `connect_mac` | 一次性触发连接（执行后自动清空） | `MAC` |
| `bond_mac` | 一次性发起 LE 配对（执行后自动清空） | `MAC` |

### 系统属性

| 属性 | 作用 | 值 |
| --- | --- | --- |
| `persist.bluetooth.enable_dual_mode_audio` | **双模共存开关（核心）** | `true` |
| `persist.sys.oplus.bt.le_audio_white_list_enable` | 厂商白名单 | `false` |

### 广播命令（免重启蓝牙）

```bash
# 探活
adb shell am broadcast -a com.dsh.lea.action.CMD --es cmd ping --es req t1

# 立即连接 LE Audio
adb shell am broadcast -a com.dsh.lea.action.CMD \
  --es cmd connect --es mac 84:1E:1A:0C:74:04 --es req c1

# 删除配对（可多个，逗号分隔）
adb shell am broadcast -a com.dsh.lea.action.CMD \
  --es cmd forget --es macs "84:1E:1A:0C:74:04,84:1E:1A:0C:B1:7F" --es req f1
```

---

## 六、已知限制

1. **卡片上的编码器徽标显示 AAC** —— 卡片反映经典链路，实际媒体走 LC3（系统 UI 能力所限）
2. **`-LEA` 显示在「可用设备」区但标「使用中」** —— ColorOS 对双模设备的显示怪癖
3. **重启手机后需要几秒** 才自动连上 LE Audio（`onBluetoothReady` + 6 秒延迟）
4. **模块更新后 Vector 会自动禁用** —— 每次更新 APK 要重新启用一次
5. **ColorOS OTA 可能导致 hook 失效** —— 升级后需要重新核对 hook 点
