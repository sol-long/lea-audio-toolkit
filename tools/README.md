# 抓包分析工具

逆向蓝牙协议时写的 btsnoop / HCI 解析脚本，**只读本地抓包文件**，不需要手机。

## 用法

```bash
# Android 抓包位置（root）
#   /data/misc/bluetooth/logs/bluetooth_*.log
#   /data/misc/bluetooth/cached_hci/btsnoop_hci_*.cfa

python -X utf8 parse_rfcomm.py capture.cfa              # RFCOMM 层（DLCI / MCC / UIH）
python -X utf8 parse_rfcomm.py capture.cfa --dlci 12    # 只看某条 DLCI
python -X utf8 parse_adv.py capture.cfa                 # LE 广播
python -X utf8 parse_snoop.py capture.cfa               # ATT / GATT
python -X utf8 find_battery.py capture.cfa              # 搜电量相关字节
python -X utf8 hci_audit.py capture.cfa                 # HCI 事件统计
```

`btsnoop_lib.py` 是共用库（HCI → L2CAP → RFCOMM / ATT 全链路，含 credit / FCS / 分片重组）。

## 已知坑

| 坑 | 说明 |
| --- | --- |
| 方向位不可信 | 部分厂商日志的 ACL 方向位取反，`TX/RX` 只能当相对标识 |
| MAC 被打码 | ColorOS 把日志地址打成 `xx:xx:xx:xx:74:04`，只能按尾两段匹配 |
| RFCOMM credit 字节 | `Control` 带 P/F 位时，length 之后多一个 credit 字节，少算会把数据当成空帧 |
| `0x184E` 有两种来源 | `btif_dm_search_devices_evt`（搜索 EIR）vs `bta_gattc_search_service`（GATT 发现），后者与模式无关 |
