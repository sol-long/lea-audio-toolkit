"""L2CAP/SPP 层总览：通道清单、SDP 服务表、载荷 dump、字节串反查。

用法（python -X utf8）：
    python -X utf8 tools/parse_spp.py dbg/s6.cfa                # 通道 + SDP 服务表
    python -X utf8 tools/parse_spp.py dbg/s6.cfa --dump         # 打印每包 L2CAP 载荷
    python -X utf8 tools/parse_spp.py dbg/s6.cfa --needle 5d55  # 搜字节串
    python -X utf8 tools/parse_spp.py "dbg/s*.cfa" --dir-rx     # 只看耳机发来的

修复点：
  * PB 位 0b00 也算起始包（旧版只认 0b10 → 手机发出的包全丢）；
  * 按 (handle,方向) 分片重组（本期抓包 0 分片，但逻辑已就位）；
  * 通道类型自动判别，不再把 SDP/AVDTP 当 RFCOMM；
  * PSM 不再依赖建链时的信令包——直接用 SDP 响应里的 ProtocolDescriptorList
    反推 RFCOMM server channel，再换算 DLCI = channel << 2。
"""

import argparse
import collections
import glob
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import btsnoop_lib as B  # noqa: E402


def load(path):
    _dl, recs = B.read_records(path)
    conn = B.conn_map(recs)
    pdus = B.acl_pdus(recs)
    return recs, conn, pdus


def show_services(path, pdus):
    svcs = B.sdp_channels(pdus)
    print("\n  [SDP 服务表]（ProtocolDescriptorList → PSM / RFCOMM channel）")
    if not svcs:
        print("    （没抓到 SDP 响应，或响应里没有 ProtocolDescriptorList）")
        return
    seen = set()
    for uuids, psm, chan, transport in svcs:
        key = (tuple(uuids), psm, chan)
        if key in seen:
            continue
        seen.add(key)
        names = "/".join(B.uuid_name(u) for u in uuids) or "-"
        extra = ""
        if chan is not None:
            extra = "  →  RFCOMM ch%d  ⇒  DLCI %d" % (chan, chan * 2)
        elif psm is not None:
            extra = "  PSM 0x%04X" % psm
        print("    %-28s transport=%-6s%s" % (names, B.uuid_name(transport) if transport else "-", extra))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--addr", help="地址子串，如 74:51")
    ap.add_argument("--dump", action="store_true", help="打印每条 L2CAP 载荷")
    ap.add_argument("--dir-rx", action="store_true", help="只看 controller->host（耳机发来）")
    ap.add_argument("--dir-tx", action="store_true", help="只看 host->controller（手机发出）")
    ap.add_argument("--needle", help="十六进制字节串，如 5d5564")
    ap.add_argument("--limit", type=int, default=100)
    args = ap.parse_args()

    files = []
    for pat in args.paths:
        g = glob.glob(pat)
        files.extend(sorted(g) or [pat])

    needle = bytes.fromhex(args.needle) if args.needle else None

    for path in files:
        recs, conn, pdus = load(path)
        acl = [r for r in recs if r.pkt and r.pkt[0] == B.HCI_ACL]
        rx = sum(1 for r in acl if not r.from_host)
        print("=" * 96)
        print("%s  records=%d ACL=%d (controller->host=%d) L2CAP=%d"
              % (os.path.basename(path), len(recs), len(acl), rx, len(pdus)))
        print("  连接: %s" % {h: a for h, a in conn.items()})
        show_services(path, pdus)

        # CID × 方向 × 长度分布
        print("\n  [CID × 方向 × 长度分布]")
        agg = collections.OrderedDict()
        for p in pdus:
            key = (p.cid, p.from_host)
            agg.setdefault(key, []).append(len(p.payload))
        for (cid, fh), lens in sorted(agg.items()):
            c = collections.Counter(lens)
            print("    cid=0x%04x %-4s n=%-4d 长度: %s"
                  % (cid, "TX" if fh else "RX", len(lens),
                     ",".join("%d×%d" % (k, v) for k, v in sorted(c.items()))))

        if needle:
            print("\n  [搜索 %s]" % needle.hex(" "))
            hits = 0
            for p in pdus:
                idx = p.payload.find(needle)
                while idx >= 0:
                    lo = max(0, idx - 8)
                    hi = min(len(p.payload), idx + len(needle) + 8)
                    print("    HIT cid=0x%04x %-4s off=%d  %s | %s"
                          % (p.cid, "TX" if p.from_host else "RX", idx,
                             B.hexs(p.payload[lo:hi]), B.ascii_of(p.payload[lo:hi])))
                    hits += 1
                    idx = p.payload.find(needle, idx + 1)
            print("    命中 %d" % hits)
            continue

        if not args.dump:
            continue
        print("\n  [L2CAP 载荷]")
        shown = 0
        for p in pdus:
            if args.dir_rx and p.from_host:
                continue
            if args.dir_tx and not p.from_host:
                continue
            if args.addr and args.addr.upper() not in conn.get(p.handle, "").upper():
                continue
            print("    cid=0x%04x %-4s h=%d len=%-3d %s | %s"
                  % (p.cid, "TX" if p.from_host else "RX", p.handle, len(p.payload),
                     B.hexs(p.payload, 24), B.ascii_of(p.payload[:24])))
            shown += 1
            if shown >= args.limit:
                print("    ... 截断")
                break


if __name__ == "__main__":
    main()
