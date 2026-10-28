"""解析 btsnoop 里的 L2CAP/RFCOMM，按 DLCI 输出统计与带时间戳的收发序列。

用法（python -X utf8）：
    # 总览：通道清单 + 每 DLCI 帧统计
    python -X utf8 tools/parse_rfcomm.py dbg/s6.cfa

    # 多文件
    python -X utf8 tools/parse_rfcomm.py "dbg/s*.cfa"

    # 只看真正承载应用数据的 UIH 帧（排除 DLCI 0 的 MCC 控制）
    python -X utf8 tools/parse_rfcomm.py dbg/s6.cfa --data-only

    # 时间线
    python -X utf8 tools/parse_rfcomm.py dbg/s6.cfa --dlci 4,8,12 --timeline --max 200

相对旧版的修复：
  * PB 位 0b00 也当作起始包（旧版只认 0b10，导致手机发出的包全被丢弃）；
  * 按 (handle,方向) 做跨 ACL 分片重组；
  * 支持 2 字节长度、credits、FCS 校验并统计通过率；
  * 区分 MCC（DLCI 0 的 PN/MSC/RLS/RPN/FCON/FCOFF/TEST/NSC）与真正的 UIH 数据；
  * 通道类型自动判别（RFCOMM / SDP / AVDTP / AVCTP / L2CAP 信令），不把 SDP 当 RFCOMM；
  * --addr 用子串匹配（旧版 startswith 传 "74:51" 永远匹配不到）。
"""

import argparse
import collections
import glob
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import btsnoop_lib as B  # noqa: E402


def collect(path):
    _dl, recs = B.read_records(path)
    conn = B.conn_map(recs)
    pdus = B.acl_pdus(recs)
    groups = collections.OrderedDict()
    for p in pdus:
        if p.cid == 0x0001:
            continue
        groups.setdefault((p.handle, p.cid, p.from_host), []).append(p)
    channels = collections.OrderedDict()
    for key, ps in groups.items():
        kind = B.classify_channel(ps)
        buf = b"".join(x.payload for x in ps)
        if kind == B.KIND_RFCOMM:
            fps = B.rfcomm_frames_pdus(ps)
            frames = [f for _p, f in fps]
            ts_of = {id(f): p.ts for p, f in fps}
        else:
            frames, ts_of = [], {}
        channels[key] = dict(kind=kind, pdus=ps, bytes=len(buf),
                             frames=frames, ts_of=ts_of)
    return conn, pdus, channels, recs


def frame_label(f):
    if f.ctype == "UIH" and f.dlci == 0:
        name = B.mcc_decode(f.info)
        return name, B.mcc_detail(name, f.info)
    if f.ctype == "UIH":
        return "DATA", ""
    return f.ctype, ""


def summarize(path, args):
    conn, pdus, channels, recs = collect(path)
    base = os.path.basename(path)
    acl = [r for r in recs if r.pkt and r.pkt[0] == B.HCI_ACL]
    tx = sum(1 for r in acl if r.from_host)
    rx = len(acl) - tx

    print("=" * 100)
    print("%s  records=%d  ACL=%d (controller->host=%d / host->controller=%d)  L2CAP PDU=%d"
          % (base, len(recs), len(acl), rx, tx, len(pdus)))
    frag = collections.Counter(p.frags for p in pdus)
    cont = sum(1 for r in recs if r.pkt and r.pkt[0] == B.HCI_ACL
               and ((int.from_bytes(r.pkt[1:3], "little") >> 12) & 3) == 0b01)
    print("  连接: %s" % {h: a for h, a in conn.items()})
    print("  ACL 分片: 每 PDU 分片数 %s ；PB=0b01 续包数=%d" % (dict(frag), cont))
    print("  ! 注意：方向按 btsnoop flags bit0 直译；但本厂商日志的 ACL 方向位疑似取反"
          "（AT 指令 / SDP 请求本应来自手机，却标在 controller->host），"
          "下面 TX/RX 只作相对标识。")

    print("\n  [通道清单]")
    print("  %-3s %-6s %-4s %-9s %-5s %-6s %-16s %s"
          % ("h", "cid", "dir", "type", "pdus", "bytes", "DLCI", "备注"))
    for (h, cid, fh), info in channels.items():
        dlcis = sorted({f.dlci for f in info["frames"]})
        note = ""
        if info["kind"] == B.KIND_RFCOMM:
            d = [x for x in dlcis if x != 0]
            note = "RFCOMM MUX" + (" + DLCI %s" % d if d else "（仅 MUX 控制）")
        elif info["kind"] == B.KIND_SDP:
            note = "服务发现"
        elif info["kind"] == B.KIND_AVDTP:
            note = "A2DP 信令"
        elif info["kind"] == B.KIND_AVCTP:
            note = "AVRCP"
        print("  %-3d 0x%04x %-4s %-9s %-5d %-6d %-16s %s"
              % (h, cid, "TX" if fh else "RX", info["kind"], len(info["pdus"]),
                 info["bytes"], dlcis or "-", note))

    rf = [(k, v) for k, v in channels.items() if v["kind"] == B.KIND_RFCOMM]
    if rf:
        print("\n  [RFCOMM 每 DLCI 统计]  MCC=DLCI0 上的 PN/MSC/RLS 等控制命令；UIH数据=真正的应用层帧")
        stat = collections.defaultdict(lambda: dict(mcc=0, data=0, ctrl=0, data_len=[],
                                                    fcs_ok=0, fcs_n=0, dirs=set()))
        for (h, cid, fh), info in rf:
            for f in info["frames"]:
                s = stat[f.dlci]
                s["dirs"].add("TX" if fh else "RX")
                if f.fcs is not None:
                    s["fcs_n"] += 1
                    s["fcs_ok"] += 1 if f.fcs_ok else 0
                if f.ctype == "UIH" and f.dlci != 0:
                    s["data"] += 1
                    s["data_len"].append(len(f.info))
                elif f.ctype == "UIH":
                    s["mcc"] += 1
                else:
                    s["ctrl"] += 1
        print("  %-5s %-6s %-8s %-7s %-7s %-22s %s"
              % ("DLCI", "MCC", "UIH数据", "控制帧", "方向", "数据帧长度分布", "FCS通过"))
        for d in sorted(stat):
            s = stat[d]
            dist = collections.Counter(s["data_len"])
            dists = ",".join("%d×%d" % (k, v) for k, v in sorted(dist.items())) or "-"
            print("  %-5d %-6d %-8d %-7d %-7s %-22s %d/%d"
                  % (d, s["mcc"], s["data"], s["ctrl"],
                     "/".join(sorted(s["dirs"])), dists, s["fcs_ok"], s["fcs_n"]))
        total_data = sum(s["data"] for s in stat.values() if s is not stat[0]) if stat else 0
        print("  => 全通道 UIH 应用数据帧合计: %d" % total_data)

    if args.timeline:
        want = {int(x) for x in str(args.dlci).split(",")} if args.dlci else None
        print("\n  [时间线]  t=相对首帧毫秒")
        rows = []
        for (h, cid, fh), info in rf:
            for f in info["frames"]:
                if want is not None and f.dlci not in want:
                    continue
                if args.data_only and not (f.ctype == "UIH" and f.dlci != 0):
                    continue
                rows.append((info["ts_of"].get(id(f), info["pdus"][0].ts), h, cid, fh, f))
        rows.sort(key=lambda r: r[0])
        if not rows:
            print("  （无匹配帧）")
        t0 = rows[0][0] if rows else 0
        shown = 0
        for ts, h, cid, fh, f in rows:
            label, detail = frame_label(f)
            print("  t=%9.3fms h=%d cid=%04x %s dlci=%-2d %-9s L=%-3d %-44s %s"
                  % ((ts - t0) / 1000.0, h, cid, "TX" if fh else "RX", f.dlci,
                     label, len(f.info), B.hexs(f.info, 20), B.ascii_of(f.info[:20])))
            if detail:
                print("  %24s %s" % ("", detail))
            shown += 1
            if args.max and shown >= args.max:
                print("  ... 截断")
                break
        print("  共 %d 帧" % len(rows))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--addr", help="设备地址子串，如 74:51")
    ap.add_argument("--dlci", help="只看这些 DLCI，逗号分隔，如 4,8,12")
    ap.add_argument("--timeline", action="store_true", help="打印收发时间线")
    ap.add_argument("--data-only", action="store_true", help="只显示真正的 UIH 数据帧（排除 MCC）")
    ap.add_argument("--max", type=int, default=0, help="时间线最多打印多少帧")
    args = ap.parse_args()

    files = []
    for pat in args.paths:
        g = glob.glob(pat)
        files.extend(sorted(g) or [pat])
    for f in files:
        summarize(f, args)


if __name__ == "__main__":
    main()
