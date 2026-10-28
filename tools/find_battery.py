"""电量值反查：在所有 ACL 载荷里找 93/85/100（0x5D/0x55/0x64）及其组合。

用法（python -X utf8）：
    python -X utf8 tools/find_battery.py dbg/s2.cfa dbg/s3.cfa dbg/s4.cfa dbg/snoop.cfa
    python -X utf8 tools/find_battery.py "dbg/s*.cfa" --gap 2 --verbose

做的三件事：
  1. 单值扫描：0x5D / 0x55 / 0x64 各自出现在哪（含上下文）；
  2. 组合扫描：三个值「任意顺序、相邻两次命中允许穿插 1..N 字节」的窗口；
  3. 假阳性标注：落在 RFCOMM FCS 字节 / credit 字节 / 长度字节 / L2CAP 头
     上的命中会被标成 FCS/CREDIT/HDR —— 这批抓包里 0x5D/0x55/0x64 绝大多数
     就是 FCS 值（例如 UA 帧 `21 73 01 63` 的 0x63、`23 3F 01 C9` 的 0xC9），
     不是电量。

同时统计「十进制 ASCII」形式（"93"/"85"/"100"）作为补充。
"""

import argparse
import glob
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import btsnoop_lib as B  # noqa: E402

TARGETS = {0x5D: 93, 0x55: 85, 0x64: 100}


def frame_meta(pdus):
    """返回 {(cid,id(pdu)) -> 事件} 以及每个字节偏移的角色表。"""
    roles = {}   # (id(pdu), offset) -> 角色名
    for p in pdus:
        pass
    return roles


def build_roles(channels):
    """标注 RFCOMM 帧内各字节角色：ADDR/CTRL/LEN/CREDIT/INFO/FCS。"""
    roles = {}   # (cid, pdu_index, offset_in_payload) -> role
    for (h, cid, fh), info in channels.items():
        if info["kind"] != B.KIND_RFCOMM:
            continue
        for p, f in info["fps"]:
            raw = f.raw
            if not raw:
                continue
            # 反推该帧在其 PDU 内的偏移
            base = p.payload.find(raw[:4])
            off = p.index
            if base < 0:
                continue
            ln = f.info
            # 头：addr(1)+ctrl(1)+len(1或2)+credit(0或1)
            hdr = 2 + (1 if raw[2] & 1 else 2) + (1 if f.credits is not None else 0)
            roles[(cid, off, base + 0)] = "ADDR"
            roles[(cid, off, base + 1)] = "CTRL"
            for k in range(2, hdr):
                roles[(cid, off, base + k)] = "CREDIT" if f.credits is not None and k == hdr - 1 else "LEN"
            for k in range(hdr, hdr + len(ln)):
                roles[(cid, off, base + k)] = "INFO"
            if f.fcs is not None:
                roles[(cid, off, base + hdr + len(ln))] = "FCS"
    return roles


def scan(path, args):
    _dl, recs = B.read_records(path)
    conn = B.conn_map(recs)
    pdus = B.acl_pdus(recs)
    groups = {}
    for p in pdus:
        if p.cid == 1:
            continue
        groups.setdefault((p.handle, p.cid, p.from_host), []).append(p)
    channels = {}
    for k, ps in groups.items():
        kind = B.classify_channel(ps)
        fps = B.rfcomm_frames_pdus(ps) if kind == B.KIND_RFCOMM else []
        channels[k] = dict(kind=kind, pdus=ps, fps=fps)
    roles = build_roles(channels)

    print("=" * 96)
    print("%s  ACL PDU=%d" % (os.path.basename(path), len(pdus)))

    # ---- 1) 单值扫描
    print("\n  [单值扫描 0x5D=93 / 0x55=85 / 0x64=100]")
    tally = {0x5D: 0, 0x55: 0, 0x64: 0}
    role_tally = {}
    samples = []
    for p in pdus:
        for off, b in enumerate(p.payload):
            if b in TARGETS:
                tally[b] += 1
                role = roles.get((p.cid, p.index, off), "L2CAP载荷")
                role_tally[(b, role)] = role_tally.get((b, role), 0) + 1
                if len(samples) < args.samples:
                    lo, hi = max(0, off - 6), min(len(p.payload), off + 7)
                    samples.append((p, off, b, role,
                                    B.hexs(p.payload[lo:hi]), B.ascii_of(p.payload[lo:hi])))
    for v in sorted(tally):
        print("    0x%02X (%3d) 出现 %d 次" % (v, TARGETS[v], tally[v]))
    print("    按字节角色拆分（关键：落在 FCS/CREDIT/LEN 的都不是应用数据）：")
    for (v, role), n in sorted(role_tally.items(), key=lambda x: -x[1]):
        print("      0x%02X  role=%-10s x%d" % (v, role, n))
    if samples:
        print("    样例：")
        for p, off, b, role, hx, asc in samples:
            print("      cid=0x%04x %-4s off=%-3d %-10s %s | %s"
                  % (p.cid, "TX" if p.from_host else "RX", off, role, hx, asc))

    # ---- 2) 组合扫描
    print("\n  [组合扫描：93/85/100 三者共存，相邻命中间隔 ≤ %d 字节，任意顺序]" % (args.gap + 1))
    combo = []
    for p in pdus:
        pay = p.payload
        for i in range(len(pay)):
            if pay[i] not in TARGETS:
                continue
            seq, pos = [(i, pay[i])], i
            for _ in range(2):
                nxt = None
                for j in range(pos + 1, min(len(pay), pos + args.gap + 2)):
                    if pay[j] in TARGETS and pay[j] not in {v for _x, v in seq}:
                        nxt = (j, pay[j])
                        break
                if not nxt:
                    break
                seq.append(nxt)
                pos = nxt[0]
            if len(seq) == 3:
                combo.append((p, seq))
    if not combo:
        print("    命中 0 —— 三个值从未在 %d 字节窗口内共存" % (args.gap * 2 + 3))
    else:
        print("    命中 %d 处" % len(combo))
        for p, seq in combo[:args.samples]:
            lo = max(0, seq[0][0] - 4)
            hi = min(len(p.payload), seq[-1][0] + 6)
            roles_of = [roles.get((p.cid, p.index, o), "L2CAP载荷") for o, _v in seq]
            print("      cid=0x%04x %-4s span=%d..%d roles=%s"
                  % (p.cid, "TX" if p.from_host else "RX", seq[0][0], seq[-1][0], roles_of))
            print("        %s | %s" % (B.hexs(p.payload[lo:hi]), B.ascii_of(p.payload[lo:hi])))

    # ---- 3) ASCII 十进制
    print("\n  [ASCII 十进制 '93'/'85'/'100']")
    ahits = 0
    for p in pdus:
        for key in (b"93", b"85", b"100"):
            idx = p.payload.find(key)
            while idx >= 0:
                ahits += 1
                if ahits <= args.samples:
                    lo, hi = max(0, idx - 6), min(len(p.payload), idx + 12)
                    print("      cid=0x%04x %-4s off=%d  %s | %s"
                          % (p.cid, "TX" if p.from_host else "RX", idx,
                             B.hexs(p.payload[lo:hi]), B.ascii_of(p.payload[lo:hi])))
                idx = p.payload.find(key, idx + 1)
    print("    共 %d 处（多在 AT 文本/号码里，需人工排除）" % ahits)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--gap", type=int, default=2, help="两个目标值之间允许穿插的字节数上限（默认 2）")
    ap.add_argument("--samples", type=int, default=12, help="每类最多打印多少条样例")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()
    files = []
    for pat in args.paths:
        g = glob.glob(pat)
        files.extend(sorted(g) or [pat])
    for f in files:
        scan(f, args)


if __name__ == "__main__":
    main()
