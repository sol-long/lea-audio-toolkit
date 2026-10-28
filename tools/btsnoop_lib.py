"""btsnoop / HCI / L2CAP / RFCOMM / SDP 解析库（纯标准库）。

覆盖三层：
  H4 (datalink=1002) -> HCI ACL -> L2CAP -> { signalling | SDP | RFCOMM }

关键点（旧工具缺失的）：
  * HCI ACL 的 PB 位：0b00 和 0b10 都可能是「起始包」，0b01 是续包，
    旧代码只认 0b10，导致本项目的抓包里**所有手机发出的包被丢弃**；
  * 必须按 (handle,direction) 做分片重组，不能再假设「一包一 PDU」；
  * RFCOMM：地址字节 -> DLCI，控制字节 -> SABM/UA/DM/UIH，长度支持 1/2 字节，
    UIH 可选 credits 字节，末尾 FCS 用 CRC-8(poly 0x07 反射=0xE0, init 0xFF, xorout 0xFF) 校验；
  * DLCI 0 上的 UIH 是 MCC（PN/MSC/RLS/RPN/FCON/FCOFF/TEST/NSC），不是应用数据；
  * SDP 可以反推 RFCOMM server channel（PSM 0x0003 的第二个参数），
    这样即使 SPP 建链早于抓包开始也能识别通道。
"""

import struct

HCI_ACL = 0x02
HCI_EVENT = 0x04
HCI_CMD = 0x01
HCI_SCO = 0x03

# ---------------------------------------------------------------- btsnoop

class Rec:
    __slots__ = ("ts", "flags", "pkt")

    def __init__(self, ts, flags, pkt):
        self.ts = ts
        self.flags = flags
        self.pkt = pkt

    @property
    def from_host(self):
        """btsnoop flags bit0：0=host->controller(sent)，1=controller->host(received)"""
        return (self.flags & 1) == 0

    @property
    def side(self):
        return "TX" if self.from_host else "RX"


def read_records(path):
    data = open(path, "rb").read()
    if data[:8] != b"btsnoop\x00":
        raise ValueError("%s: not a btsnoop file" % path)
    ver, datalink = struct.unpack(">II", data[8:16])
    off, out = 16, []
    n = len(data)
    while off + 24 <= n:
        _orig, incl, flags, _drops, ts = struct.unpack(">IIIIQ", data[off:off + 24])
        off += 24
        out.append(Rec(ts, flags, data[off:off + incl]))
        off += incl
    return datalink, out


# ---------------------------------------------------------------- HCI

def conn_map(recs):
    """BR/EDR(0x03) + LE(0x3E/0x01) 连接建立 -> handle: addr（地址按可读顺序）"""
    m = {}
    for r in recs:
        p = r.pkt
        if not p or p[0] != HCI_EVENT or len(p) < 5:
            continue
        ev, body = p[1], p[3:]
        if ev == 0x03 and len(body) >= 11 and body[0] == 0:
            m[struct.unpack("<H", body[1:3])[0] & 0x0FFF] = fmt_addr(body[3:9])
        elif ev == 0x3E and len(body) >= 19 and body[0] == 0x01 and body[1] == 0:
            m[struct.unpack("<H", body[2:4])[0] & 0x0FFF] = fmt_addr(body[7:13])
    return m


def fmt_addr(b):
    return ":".join("%02X" % x for x in reversed(b))


def disconn_map(recs):
    """handle -> 断开时间戳（毫秒），用于标注通道生存期"""
    m = {}
    for r in recs:
        p = r.pkt
        if not p or p[0] != HCI_EVENT or len(p) < 7:
            continue
        if p[1] == 0x05:
            m[struct.unpack("<H", p[3:5])[0] & 0x0FFF] = r.ts
    return m


class Pdu:
    """一条完整的 L2CAP PDU"""
    __slots__ = ("ts", "from_host", "handle", "cid", "payload", "frags", "index")

    def __init__(self, ts, from_host, handle, cid, payload, frags, index):
        self.ts = ts
        self.from_host = from_host
        self.handle = handle
        self.cid = cid
        self.payload = payload
        self.frags = frags
        self.index = index

    @property
    def side(self):
        return "TX" if self.from_host else "RX"


def acl_pdus(recs):
    """按 (handle, direction) 重组 ACL 分片，产出完整 L2CAP PDU 列表。"""
    bufs = {}
    out = []
    for idx, r in enumerate(recs):
        p = r.pkt
        if not p or p[0] != HCI_ACL or len(p) < 5:
            continue
        hdr = struct.unpack("<H", p[1:3])[0]
        handle, pb = hdr & 0x0FFF, (hdr >> 12) & 0x3
        dlen = struct.unpack("<H", p[3:5])[0]
        body = p[5:5 + dlen]
        key = (handle, r.from_host)
        if pb in (0b00, 0b10):          # 起始包（flushable / non-flushable 都算）
            bufs[key] = [r.ts, bytearray(body), 1, idx]
        elif pb == 0b01 and key in bufs:  # 续包
            bufs[key][1] += body
            bufs[key][2] += 1
        else:
            continue
        buf = bufs[key]
        if len(buf[1]) < 4:
            continue
        l2len = struct.unpack("<H", bytes(buf[1][0:2]))[0]
        if len(buf[1]) < 4 + l2len:
            continue                      # 还没收齐
        cid = struct.unpack("<H", bytes(buf[1][2:4]))[0]
        out.append(Pdu(buf[0], r.from_host, handle, cid,
                       bytes(buf[1][4:4 + l2len]), buf[2], buf[3]))
        del bufs[key]
    return out


# ---------------------------------------------------------------- SDP

def _de_parse(b, i, end):
    """极简 SDP 数据元素解析，返回 (value, next_index)。value 为 bytes 或 list。"""
    if i >= end:
        return None, end
    t = b[i]
    dtype, dsize = (t >> 3) & 0x1F, t & 0x07
    i += 1
    if dsize == 0:
        n = 1
    elif dsize == 1:
        n = 2
    elif dsize == 2:
        n = 4
    elif dsize == 3:
        n = 8
    elif dsize == 4:
        n = 16
    elif dsize == 5:
        if i >= end:
            return None, end
        n = b[i]
        i += 1
    elif dsize == 6:
        n = struct.unpack(">H", b[i:i + 2])[0]
        i += 2
    else:
        n = struct.unpack(">I", b[i:i + 4])[0]
        i += 4
    raw = b[i:i + n]
    i += n
    if dtype == 6:      # 序列
        items, j = [], 0
        while j < len(raw):
            v, j = _de_parse(raw, j, len(raw))
            if v is None:
                break
            items.append(v)
        return items, i
    if dtype == 5:      # 布尔
        return None, i
    return raw, i


def sdp_pdu(payload):
    if len(payload) < 5:
        return None
    pid, tid, plen = payload[0], struct.unpack(">H", payload[1:3])[0], struct.unpack(">H", payload[3:5])[0]
    return pid, tid, payload[5:5 + plen]


def _attr_pairs(elems):
    """SDP 的 attribute list 有两种常见排布：
       A) 每个属性包在 `36 xx` 子序列里 → [[attr,val],[attr,val]]
       B) 展平成 attr,val,attr,val,... → 需要两两配对
    """
    if not isinstance(elems, list):
        return []
    while len(elems) == 1 and isinstance(elems[0], list):
        elems = elems[0]
    if elems and all(isinstance(x, list) and len(x) >= 2 for x in elems):
        return [(x[0], x[1]) for x in elems]
    out = []
    i = 0
    while i + 1 < len(elems):
        out.append((elems[i], elems[i + 1]))
        i += 2
    return out


def sdp_channels(pdus):
    """从 SDP 响应里抽取 (service_uuids, psm, rfcomm_channel, transport_uuid)。

    有了 RFCOMM server channel 就能把 DLCI 反推回 DLCI = channel*2，
    从而在 SPP 建链早于抓包开始时也能认出通道。
    """
    found = []
    for p in pdus:
        if p.cid == 1:
            continue
        d = sdp_pdu(p.payload)
        if not d:
            continue
        pid, _tid, params = d
        if pid not in (0x05, 0x07):
            continue
        body = params[2:] if len(params) > 2 else params
        try:
            elems, _ = _de_parse(body, 0, len(body))
        except Exception:
            continue
        svc_uuids, psm, chan, transport = [], None, None, None
        for attr_id, val in _attr_pairs(elems):
            if not isinstance(attr_id, bytes) or len(attr_id) != 2:
                continue
            aid = struct.unpack(">H", attr_id)[0]
            if aid == 0x0001 and isinstance(val, list):
                for u in val:
                    if isinstance(u, bytes) and len(u) == 2:
                        svc_uuids.append(struct.unpack(">H", u)[0])
                    elif isinstance(u, bytes) and len(u) == 4:
                        svc_uuids.append(struct.unpack(">I", u)[0])
                    elif isinstance(u, bytes) and len(u) == 16:
                        svc_uuids.append("%08X-%04X-%04X-%s-%s" % (
                            struct.unpack(">I", u[0:4])[0],
                            struct.unpack(">H", u[4:6])[0],
                            struct.unpack(">H", u[6:8])[0],
                            u[8:10].hex().upper(), u[10:16].hex().upper()))
            if aid != 0x0004 or not isinstance(val, list):
                continue
            for proto in val:
                if not isinstance(proto, list) or not proto:
                    continue
                u = proto[0]
                if not isinstance(u, bytes):
                    continue
                if len(u) == 2:
                    uuid16 = struct.unpack(">H", u)[0]
                elif len(u) == 4:
                    uuid16 = struct.unpack(">I", u)[0]
                else:
                    continue
                param = None
                if len(proto) > 1 and isinstance(proto[1], bytes):
                    pb = proto[1]
                    param = pb[0] if len(pb) == 1 else struct.unpack(">H", pb[-2:])[0]
                if uuid16 == 0x0100:
                    psm = param
                elif uuid16 == 0x0003:
                    chan = param
                else:
                    transport = uuid16
        if psm is not None or chan is not None:
            found.append((svc_uuids, psm, chan, transport))
    return found



UUID_NAMES = {
    0x1101: "SPP", 0x1105: "OBEX-ObjectPush", 0x110A: "AudioSink", 0x110B: "AudioSource",
    0x1108: "Headset", 0x110E: "AVRCP", 0x111E: "Handsfree", 0x111F: "Handsfree-AG",
    0x1112: "Headset-AG", 0x1116: "AVRCP-Target", 0x1117: "AVRCP-Controller",
    0x1200: "PnP-Information", 0x0003: "RFCOMM", 0x0100: "L2CAP",
    0x0019: "AVDTP", 0x0017: "AVCTP", 0x000F: "BNEP",
}


def uuid_name(u):
    if isinstance(u, str):
        return u
    return UUID_NAMES.get(u, "0x%04X" % u)



# ---------------------------------------------------------------- RFCOMM

POLY, INIT, XOROUT = 0xE0, 0xFF, 0xFF


def rfcomm_fcs(data):
    crc = INIT
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ POLY if crc & 1 else crc >> 1
    return (crc ^ XOROUT) & 0xFF


CTRL_NAMES = {
    0x2F: "SABM", 0x63: "UA", 0x0F: "DM", 0x43: "DISC", 0x43 | 0x10: "DISC",
    0xEF: "UIH", 0x03: "UI", 0xFF: "UIH",
}
MCC_NAMES = {
    0x83: "PN-cmd", 0x81: "PN-rsp",
    0xE3: "MSC-cmd", 0xE1: "MSC-rsp",
    0x53: "RLS-cmd", 0x51: "RLS-rsp",
    0x93: "RPN-cmd", 0x91: "RPN-rsp",
    0x23: "TEST-cmd", 0x21: "TEST-rsp",
    0xA3: "FCON-cmd", 0xA1: "FCON-rsp",
    0x63: "FCOFF-cmd", 0x61: "FCOFF-rsp",
    0x13: "NSC-cmd", 0x11: "NSC-rsp",
}


def ctrl_name(c):
    base = c & ~0x10          # 去掉 P/F 位
    n = {0x2F: "SABM", 0x63: "UA", 0x0F: "DM", 0x43: "DISC", 0xEF: "UIH",
         0x03: "UI", 0xFB: "UI"}.get(base)
    return n or "0x%02X" % c


class Frame:
    __slots__ = ("addr", "dlci", "cr", "ctrl", "ctype", "info", "fcs", "fcs_ok",
                 "credits", "raw", "off", "bad_len")

    def __init__(self, **kw):
        for k in self.__slots__:
            setattr(self, k, kw.get(k))


def rfcomm_parse(stream, require_fcs=False):
    """把一条 L2CAP 通道上的字节流切成 RFCOMM 帧。

    帧格式（ETSI TS 07.10 / RFCOMM 1.1）：
        Address | Control | Length(1或2字节) | [Credit] | Information | FCS
    * Length 的 EA 位（bit0）=1 -> 1 字节，=0 -> 2 字节；
    * **Control 带 P/F 位（0xFF 这种 UIH）时多一个 Credit 字节** ——
      这正是本项目中「0x0A / 0x63 / 0xC9 一字节载荷」的真相：
      要么是 credit 字节，要么是 FCS 字节，都不是应用数据；
    * FCS = CRC-8 (poly 0x07 反射 0xE0, init 0xFF, xorout 0xFF)，覆盖 Address..Information。

    require_fcs=False 时按长度前进（厂商日志常把 UIH 的 FCS 写成占位值，
    硬性要求 FCS 会把整条流丢掉）；fcs_ok 只作为数据质量指标。
    """
    frames = []
    i, n = 0, len(stream)
    while i + 3 <= n:
        addr = stream[i]
        if not (addr & 0x01):
            i += 1
            continue
        ctrl = stream[i + 1]
        base = ctrl & ~0x10
        if base not in (0x2F, 0x63, 0x0F, 0x43, 0xEF, 0x03, 0xFB):
            i += 1
            continue
        ln = stream[i + 2]
        if ln & 0x01:
            L, hdr = ln >> 1, 3
        else:
            if i + 3 >= n:
                break
            L, hdr = (ln >> 1) | (stream[i + 3] << 7), 4
        credit = None
        if base == 0xEF and (ctrl & 0x10):   # 只有 UIH 带 P/F 位时才多一个 credit 字节
            if i + hdr >= n:
                break
            credit = stream[i + hdr]
            hdr += 1
        body_start = i + hdr
        body_end = body_start + L
        if body_end > n:
            frames.append(Frame(addr=addr, dlci=addr >> 2, cr=(addr >> 1) & 1,
                                ctrl=ctrl, ctype=ctrl_name(ctrl),
                                info=stream[body_start:n], fcs=None, fcs_ok=None,
                                credits=credit, raw=stream[i:n], off=i, bad_len=True))
            break
        info = stream[body_start:body_end]
        fcs = stream[body_end] if body_end < n else None
        ok = None
        if fcs is not None:
            ok = rfcomm_fcs(stream[i:body_end]) == fcs
        frames.append(Frame(addr=addr, dlci=addr >> 2, cr=(addr >> 1) & 1,
                            ctrl=ctrl, ctype=ctrl_name(ctrl), info=info,
                            fcs=fcs, fcs_ok=ok, credits=credit,
                            raw=stream[i:body_end + 1], off=i, bad_len=False))
        i = body_end + 1
    return frames


def mcc_decode(info):
    """DLCI 0 上的 UIH：解出 MCC 类型名。"""
    if not info:
        return "MCC-empty"
    t = info[0]
    name = MCC_NAMES.get(t)
    if name:
        return name
    return "MCC-0x%02X" % t


def mcc_detail(name, info):
    """对 PN/MSC/RLS 给出人类可读细节。"""
    try:
        if name.startswith("PN") and len(info) >= 10:
            # type, dlci, frame_type, convergence, ack_timer(2), mfs(2), max_retr, credits
            ack = struct.unpack("<H", info[4:6])[0]
            mfs = struct.unpack("<H", info[6:8])[0]
            return "dlci=%d ft=0x%X cl=%d ack=%d mfs=%d retr=%d credit=%d" % (
                info[1] >> 2, info[2] & 0x0F, info[3] >> 6, ack, mfs, info[8], info[9])
        if name.startswith("MSC") and len(info) >= 2:
            return "dlci=%d fc=%d sig=0x%02X" % (info[1] >> 2, info[1] & 1, info[2])
        if name.startswith("RLS") and len(info) >= 2:
            return "dlci=%d status=0x%02X" % (info[1] >> 2, info[2])
    except Exception:
        pass
    return ""


def rfcomm_frames_pdus(pdus):
    """优先「一个 L2CAP PDU = 一条完整 RFCOMM 帧」逐包解析（本项目抓包即如此，
    零分片）。个别 PDU 不是整帧时再回退到跨 PDU 字节流重组。

    返回 [(pdu_first, frame)]，便于带时间戳/方向输出。
    """
    out = []
    carry = bytearray()
    carry_pdu = None
    for p in pdus:
        payload = p.payload
        if carry:
            payload = bytes(carry) + payload
        frames = rfcomm_parse(payload)
        if not frames:
            carry, carry_pdu = bytearray(payload), carry_pdu or p
            continue
        consumed = frames[-1].off + len(frames[-1].raw)
        if frames[0].off == 0 and consumed == len(payload) and not frames[-1].bad_len:
            for f in frames:
                out.append((p, f))
            carry, carry_pdu = bytearray(), None
        elif carry_pdu is not None:
            # 拼包后仍未对齐：把能确认的帧收下，剩下的留到下一包
            for f in frames:
                out.append((carry_pdu, f))
            carry = bytearray(payload[consumed:])
            carry_pdu = None
        else:
            for f in frames:
                out.append((p, f))
            carry, carry_pdu = bytearray(payload[consumed:]), None
    return out


def ascii_of(b):
    return "".join(chr(c) if 32 <= c < 127 else "." for c in b)


def hexs(b, limit=None):
    if limit is not None:
        b = b[:limit]
    return " ".join("%02X" % x for x in b)


# ---------------------------------------------------------------- 通道分类

KIND_SIG = "L2CAP-sig"
KIND_SDP = "SDP"
KIND_ATT = "ATT"
KIND_RFCOMM = "RFCOMM"
KIND_AVDTP = "AVDTP"
KIND_AVCTP = "AVCTP"
KIND_BNEP = "BNEP"
KIND_UNKNOWN = "?"


def looks_like_sdp(payload):
    if len(payload) < 5:
        return False
    pid = payload[0]
    if not (1 <= pid <= 7):
        return False
    plen = struct.unpack(">H", payload[3:5])[0]
    return 5 + plen == len(payload)


def looks_like_rfcomm(payload):
    if len(payload) < 3:
        return False
    if not (payload[0] & 1):
        return False
    return (payload[1] & ~0x10) in (0x2F, 0x63, 0x0F, 0x43, 0xEF, 0x03, 0xFB)


def looks_like_avdtp(payload):
    if len(payload) < 2:
        return False
    return 0x01 <= payload[1] <= 0x0E and (payload[0] & 0x03) <= 2


def looks_like_avctp(payload):
    return len(payload) >= 3 and payload[1] == 0x11 and payload[2] in (0x0E, 0x0C)


def classify_channel(pdus):
    """给一条 L2CAP 通道（同一 cid + 方向）判类型。"""
    if not pdus:
        return KIND_UNKNOWN
    cid = pdus[0].cid
    if cid == 0x0001:
        return KIND_SIG
    if cid in (0x0004, 0x0005):
        return KIND_ATT
    if cid == 0x000F:
        return KIND_BNEP
    head = [p.payload for p in pdus[:4] if p.payload]
    if head and all(looks_like_sdp(x) for x in head):
        return KIND_SDP
    if head and all(looks_like_rfcomm(x) for x in head):
        return KIND_RFCOMM
    if head and all(looks_like_avctp(x) for x in head):
        return KIND_AVCTP
    if head and all(looks_like_avdtp(x) for x in head):
        return KIND_AVDTP
    # 兜底：整条流能否切出结构合法的 RFCOMM 帧
    buf = b"".join(head)
    fr = rfcomm_parse(buf)
    if fr and all(f.fcs_ok for f in fr if f.fcs is not None):
        return KIND_RFCOMM
    return KIND_UNKNOWN
