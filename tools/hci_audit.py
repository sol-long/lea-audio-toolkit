"""真实性质检：FCS 通过率 / 分片 / HCI 组成 / 时间戳。"""
import sys, os, struct, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import btsnoop_lib as B

_TBL = """0C03 Reset|0C01 SetEventMask|0C52 ReadBufSize|0C33 HostBufSize|0C31 FlowSpec
0C6D WriteLESupport|0C6C WriteDefaultErroneousData|0C56 WriteLEHostSup|0C14 ReadLocalVer
0C09 ReadBDAddr|0C23 ReadClassOfDev|0C24 ReadLocalName|0C45 ReadInqScanAct|0C46 ReadInqScanTyp
0C1A WriteScanEnable|0C3D WriteInquiryScanAct|0C3E WriteInquiryScanTyp|0C0C AddSCO|0C1C RemoveSCO
0C42 SetConnEncrypt|0C13 WriteLocalName|0C05 CreateConn|0C04 Inquiry|0C3B WriteCurrentIAC
0C25 ReadRemoteName|0C1D WritePageTimeout|0C16 WritePageScanAct|0C18 WritePageScanTyp
0C27 ReadRemoteClass|0C2B WriteClassOfDev|0C3F SetVoiceSetting|0C26 WriteHoldModeAct
0C29 WriteSniffMode|0C0A ReadRemoteFeatures|0C08 PINReqReply|0C07 LinkKeyReqReply
0C0B ReadRemoteExtFeatures|0C2E ReadLocalExtFeatures|0C4D ReadLocalOOBData
0C63 SetEventMaskPage2|0C57 ReadLocalName2|0C5B WriteLEHostSupport"""
OPCODES = {}
for line in _TBL.splitlines():
    for part in line.split("|"):
        part = part.strip()
        if part:
            a, b = part.split(" ", 1)
            OPCODES[int(a, 16)] = b

for path in sys.argv[1:]:
    dl, recs = B.read_records(path)
    print("=" * 70)
    print(os.path.basename(path), "recs=%d" % len(recs))
    kinds = collections.Counter()
    cmds = collections.Counter()
    evs = collections.Counter()
    for r in recs:
        p = r.pkt
        if not p:
            continue
        t = p[0]
        kinds[(t, r.side)] += 1
        if t == 0x01 and len(p) >= 4:
            op = struct.unpack("<H", p[1:3])[0]
            cmds[op] += 1
        elif t == 0x04 and len(p) >= 3:
            evs[p[1]] += 1
    print("  kinds:", dict(kinds))
    print("  HCI cmds(%d):" % sum(cmds.values()))
    for op, c in cmds.most_common():
        print("     0x%04X %-28s x%d" % (op, OPCODES.get(op, "?"), c))
    print("  HCI events(%d):" % sum(evs.values()))
    for ev, c in evs.most_common():
        print("     0x%02X x%d" % (ev, c))
    # 分片统计 + L2CAP 长度一致性
    pdus = B.acl_pdus(recs)
    frag = collections.Counter(p.frags for p in pdus)
    print("  pdus=%d 分片数分布=%s" % (len(pdus), dict(frag)))
    # 时间戳间隔
    ts = [r.ts for r in recs]
    d = [ts[i+1]-ts[i] for i in range(len(ts)-1)]
    if d:
        print("  ts delta: min=%d max=%d 中位=%d (µs?)" % (min(d), max(d), sorted(d)[len(d)//2]))
