import io
import os
import re
import shutil
import sys
import urllib.request
import zipfile

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SDK = os.path.join(BASE, "sdk")
CACHE = os.path.join(BASE, "tools", "cache")
REPO = "https://dl.google.com/android/repository/"

WANT = [
    ("build-tools", "35.0.0"),
    ("platforms", "android-35"),
]


def fetch(url, dest=None):
    req = urllib.request.Request(url, headers={"User-Agent": "dsh/1.0"})
    with urllib.request.urlopen(req, timeout=120) as r:
        if dest:
            with open(dest, "wb") as f:
                while True:
                    chunk = r.read(1 << 20)
                    if not chunk:
                        break
                    f.write(chunk)
            return dest
        return r.read()


def main():
    os.makedirs(CACHE, exist_ok=True)
    os.makedirs(SDK, exist_ok=True)
    print("fetching repository index ...")
    xml = fetch(REPO + "repository2-3.xml").decode("utf-8", "replace")

    pkgs = {}
    for m in re.finditer(r'<remotePackage path="([^"]+)">(.*?)</remotePackage>', xml, re.S):
        path, body = m.group(1), m.group(2)
        for a in re.finditer(r'<archive>(.*?)</archive>', body, re.S):
            ab = a.group(1)
            host = re.search(r"<host-os>([^<]+)</host-os>", ab)
            url = re.search(r"<url>([^<]+)</url>", ab)
            if not url:
                continue
            if host and host.group(1) != "windows":
                continue
            pkgs.setdefault(path, url.group(1))
            break

    ok = 0
    for kind, ver in WANT:
        key = "%s;%s" % (kind, ver)
        url = pkgs.get(key)
        if not url:
            print("MISSING package:", key)
            continue
        name = url.rsplit("/", 1)[-1]
        dest = os.path.join(CACHE, name)
        if not os.path.exists(dest) or os.path.getsize(dest) == 0:
            print("downloading", name, "...")
            fetch(REPO + url, dest)
        print("have", name, os.path.getsize(dest), "bytes")
        with zipfile.ZipFile(dest) as z:
            tops = {n.split("/")[0] for n in z.namelist() if "/" in n}
            z.extractall(SDK)
        # Google 的包解压出来的顶层目录名不一定是 <ver>（build-tools 35 解压是 android-15），
        # 统一挪到 build-tools/35.0.0 与 platforms/android-35 这种标准布局
        target = os.path.join(SDK, kind, ver)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        if not os.path.isdir(target):
            for t in sorted(tops):
                src = os.path.join(SDK, t)
                if os.path.isdir(src):
                    shutil.move(src, target)
                    print("  moved", t, "->", os.path.relpath(target, SDK))
                    break
        ok += 1
    print("done, extracted", ok, "packages into", SDK)


if __name__ == "__main__":
    main()
