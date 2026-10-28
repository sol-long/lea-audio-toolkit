# App：LEA Toolbox

调试与配置 App，5 个 Tab：

| Tab | 作用 |
| --- | --- |
| **状态** | 实时看 LC3 是否生效：双模开关、LE Audio 组、媒体路由、LC3 配置名、ASE 状态、电量、模块状态（8 秒自动刷新） |
| **操作** | 12 个写操作（双模 / 白名单 / auto_mac / target_macs / 触发连接 / 触发配对 / 重启蓝牙 / 模块启停），每个都有二次确认并显示将执行的完整命令 |
| **探测** | BLE 扫描（8 秒），按名称 / MAC 前缀 / 显示全部 三种方式过滤，可直接「设为 auto_mac」 |
| **诊断** | 属性快照（153+ 条）、复制、清空 `persist.sys.lea.*`、日志最后 200 行 |
| **向导** | 7 步一键配置，自动检测 + 一键修复 + 僵尸配对清理 |

## 构建

```bash
# 复用 ../module/sdk（首次需先在 module 里跑 get_sdk.py）
python -X utf8 tools/build.py

# 或指定其它 SDK
LEA_SDK=/path/to/sdk python -X utf8 tools/build.py
```

产出 `dist/LEAToolbox.apk`。

## 自测

```bash
python -X utf8 tools/selftest.py     # 解析与判据的纯 JVM 单测（200+ 用例）
```

## 向导的 7 步

| 步 | 你要做的 | 自动判据 |
| --- | --- | --- |
| 0 | — | 7 项前置检查（root / 模块启用 / 模块已注入 / 双模 / 白名单 / 蓝牙 / Roselink） |
| 1 | 确认耳机在 AAC 模式 | `[BR/EDR]` 配对存在 + A2DP CONNECTED |
| 2 | 配对经典身份 | Bonded 出现 `[BR/EDR]` |
| 3 | Roselink 切 LC3（**人工确认复选框**） | 自动佐证：EIR 含 0x184E / 有 LE 广播 / 已有 `-LEA` |
| 4 | 配对 `-LEA` 身份 | Bonded 里 `[LE]` + `-LEA` 数量 ≥1（双耳=2） |
| 5 | 播放一首歌 | 组≠-1 + 媒体走 `ble_headset` + ASE Streaming + Lc3 + 经典仍连接 |
| 6 | — | 自动写入 `auto_mac` / `target_macs` |

> 第 3 步为什么是「人工确认」：切换 LC3 发生在耳机侧，配对前手机拿不到可靠的自动信号
> （未配对设备名不在 `dumpsys` 里、LE 地址被打码、日志里 `0x184E` 有两种来源容易误判）。
> 真正的硬验证在第 4 步。

## 权限

- `BLUETOOTH_SCAN`（`neverForLocation`）、`BLUETOOTH_CONNECT`
- Root（`su`）：读写系统属性、读 `dumpsys` 与蓝牙日志、调用 LSPosed CLI
- 无 root 时降级为只读，不会崩溃

## 主题

纯代码 UI（无 XML 布局），Material Light，中文。`targetSdk 35`，`minSdk 29`。
