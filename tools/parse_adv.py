import struct
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "capture.cfa"
data = open(path, "rb").read()
off = 16
seen = {}
while off + 24 <= len(data):
    _o, incl, _f, _d, ts = struct.unpack(">IIIIQ", data[off:off + 24])
    off += 24
    p = data[off:off + incl]
    off += incl
    if not p or p[0] != 0x04 or len(p) < 6:
        continue
    ev = p[1]
    body = p[3:]
    if ev != 0x3E or not body or body[0] != 0x02:
        continue
    # LE Advertising Report
    num = body[1]
    i = 2
    for _ in range(num):
        if i + 10 > len(body):
            break
        etype = body[i]
        atype = body[i + 1]
        addr = ":".join("%02X" % b for b in reversed(body[i + 2:i + 8]))
        dlen = body[i + 8]
        adv = body[i + 9:i + 9 + dlen]
        i += 9 + dlen
        if addr.upper().startswith(("84:1E:1A",)):
            key = (addr, adv.hex())
            if key not in seen:
                seen[key] = ts
print("unique adv payloads for 84:1E:1A:* =", len(seen))
for (addr, hexs), ts in list(seen.items())[:40]:
    b = bytes.fromhex(hexs)
    asc = "".join(chr(c) if 32 <= c < 127 else "." for c in b)
    print("%-18s %-60s | %s" % (addr, hexs, asc))
val = [90, 85, 100]
print("\n-- 找电量三元组 --")
for (addr, hexs), ts in seen.items():
    b = bytes.fromhex(hexs)
    for order in ([90, 85, 100], [100, 85, 90]):
        pat = bytes(order)
        if pat in b:
            print("HIT", addr, order, hexs)
