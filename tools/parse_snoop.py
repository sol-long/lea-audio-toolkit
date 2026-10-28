"""解析 btsnoop (.cfa) 抓包，输出 ATT 层的读写/通知，用于逆耳机电量协议。

用法：
    python -X utf8 tools/parse_snoop.py <file.cfa> [--addr xx] [--handles 0x4c-0x53]

btsnoop 格式：16 字节头 + 记录（24 字节头 + 载荷），datalink 1002 = H4。
"""

import argparse
import struct
import sys

ATT_OPCODES = {
    0x01: "ERROR_RSP",
    0x02: "MTU_REQ",
    0x03: "MTU_RSP",
    0x04: "FIND_INFO_REQ",
    0x05: "FIND_INFO_RSP",
    0x06: "FIND_BY_TYPE_REQ",
    0x07: "FIND_BY_TYPE_RSP",
    0x08: "READ_BY_TYPE_REQ",
    0x09: "READ_BY_TYPE_RSP",
    0x0A: "READ_REQ",
    0x0B: "READ_RSP",
    0x0C: "READ_BLOB_REQ",
    0x0D: "READ_BLOB_RSP",
    0x10: "READ_BY_GROUP_REQ",
    0x11: "READ_BY_GROUP_RSP",
    0x12: "WRITE_REQ",
    0x13: "WRITE_RSP",
    0x1B: "NOTIFY",
    0x1D: "INDICATE",
    0x1E: "INDICATE_CNF",
    0x52: "WRITE_CMD",
}


def parse(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[:8] != b"btsnoop\x00":
        sys.exit("not a btsnoop file")
    datalink = struct.unpack(">I", data[12:16])[0]
    off = 16
    records = []
    while off + 24 <= len(data):
        orig, incl, flags, drops, ts = struct.unpack(">IIIIQ", data[off:off + 24])
        off += 24
        payload = data[off:off + incl]
        off += incl
        records.append((ts, payload))
    return datalink, records


def le_conn_map(records):
    """HCI LE Connection Complete (0x3E subevent 0x01) → handle: addr"""
    m = {}
    for _, p in records:
        if len(p) < 3 or p[0] != 0x04:
            continue
        ev = p[1]
        body = p[3:]
        if ev == 0x3E and len(body) >= 19 and body[0] == 0x01:
            status, handle = body[1], struct.unpack("<H", body[2:4])[0]
            addr = ":".join("%02X" % b for b in reversed(body[7:13]))
            if status == 0:
                m[handle & 0x0FFF] = addr
        elif ev == 0x03 and len(body) >= 11:
            status, handle = body[0], struct.unpack("<H", body[1:3])[0]
            addr = ":".join("%02X" % b for b in reversed(body[3:9]))
            if status == 0:
                m[handle & 0x0FFF] = addr
    return m


def att_stream(records, conn):
    out = []
    for ts, p in records:
        if not p or p[0] != 0x02 or len(p) < 5:
            continue
        hdr = struct.unpack("<H", p[1:3])[0]
        handle = hdr & 0x0FFF
        pb = (hdr >> 12) & 0x3
        dlen = struct.unpack("<H", p[3:5])[0]
        body = p[5:5 + dlen]
        if pb != 0b10 or len(body) < 4:
            continue
        l2len, cid = struct.unpack("<HH", body[:4])
        if cid != 4:
            continue
        a = body[4:4 + l2len]
        if not a:
            continue
        op = a[0]
        name = ATT_OPCODES.get(op, "0x%02X" % op)
        if len(a) >= 3 and op in (0x12, 0x52, 0x0A, 0x0C, 0x08, 0x10, 0x06):
            ah = struct.unpack("<H", a[1:3])[0]
            val = a[3:]
            out.append((handle, conn.get(handle, "?"), name, ah, val))
        elif op in (0x1B, 0x1D) and len(a) >= 3:
            ah = struct.unpack("<H", a[1:3])[0]
            out.append((handle, conn.get(handle, "?"), name, ah, a[3:]))
        elif op == 0x0B and len(a) >= 1:
            out.append((handle, conn.get(handle, "?"), name, None, a[1:]))
        elif op == 0x13 and len(a) >= 1:
            out.append((handle, conn.get(handle, "?"), name, None, a[1:2]))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--addr", help="只看某台设备（地址前缀匹配）")
    ap.add_argument("--handles", help="只看 handle 范围，如 0x4c-0x53")
    ap.add_argument("--limit", type=int, default=400)
    args = ap.parse_args()

    datalink, records = parse(args.path)
    conn = le_conn_map(records)
    rows = att_stream(records, conn)

    lo = hi = None
    if args.handles:
        a, b = args.handles.split("-")
        lo, hi = int(a, 16), int(b, 16)

    print("datalink=%d records=%d att_ops=%d" % (datalink, len(records), len(rows)))
    shown = 0
    for handle, addr, name, ah, val in rows:
        if args.addr and not addr.upper().startswith(args.addr.upper()):
            continue
        if lo is not None and not (lo <= handle <= hi):
            continue
        hexval = " ".join("%02X" % b for b in val[:20])
        print("%-14s cid=0x%03x %-14s attr=0x%04x  %s" %
              (addr, handle, name, ah if ah is not None else 0, hexval))
        shown += 1
        if shown >= args.limit:
            break


if __name__ == "__main__":
    main()
