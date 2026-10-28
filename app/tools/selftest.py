#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""解析逻辑自测（纯 JVM，不需要设备）。

把 app/src 里的纯逻辑类（Parsers / Shell / Snapshot / StatusCollector）单独编译后跑
tools/selftest/ParseSelfTest.java，用 lea-enabler/dbg 下的真实 dump 做输入。

    python -X utf8 tools/selftest.py
"""

import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "com", "dsh", "leatool")
OUT = os.path.join(ROOT, "build", "selftest")
TEST = os.path.join(ROOT, "tools", "selftest", "ParseSelfTest.java")

JDK = os.environ.get("JAVA_HOME") or r"D:\Softwares\JAVA\JDK21"
JAVAC = os.path.join(JDK, "bin", "javac.exe")
JAVA = os.path.join(JDK, "bin", "java.exe")

PURE = ["Parsers.java", "Shell.java", "Snapshot.java", "StatusCollector.java",
        "WizardLogic.java"]


def main():
    for exe in (JAVAC, JAVA):
        if not os.path.exists(exe):
            exe2 = shutil.which(os.path.basename(exe).replace(".exe", ""))
            if not exe2:
                raise SystemExit("找不到 JDK: " + exe)
    if not os.path.exists(TEST):
        raise SystemExit("找不到自测源码: " + TEST)

    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT, exist_ok=True)

    files = [os.path.join(SRC, f) for f in PURE] + [TEST]
    r = subprocess.run([JAVAC, "-encoding", "UTF-8", "-nowarn",
                        "-J-Duser.language=en", "-J-Duser.country=US",
                        "-d", OUT] + files,
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0:
        sys.stderr.write(r.stdout or "")
        sys.stderr.write(r.stderr or "")
        raise SystemExit("javac 失败")
    if (r.stdout or "").strip():
        print(r.stdout.strip())

    log = os.path.join(ROOT, "build", "selftest.log")
    os.makedirs(os.path.dirname(log), exist_ok=True)
    r = subprocess.run([JAVA, "-Dfile.encoding=UTF-8",
                        "-Dout=" + log,
                        "-Ddbg=" + os.path.join(os.path.dirname(ROOT), "lea-enabler", "dbg"),
                        "-Dfixtures=" + ROOT,
                        "-cp", OUT, "com.dsh.leatool.ParseSelfTest"],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    # 控制台只打印 ASCII 摘要（Java 直接以 UTF-8 写报告文件）
    summary = [ln for ln in (r.stdout or "").splitlines() if ln.startswith("RESULT")]
    print("selftest report ->", log)
    print(summary[0] if summary else "（没有结果行，见报告）")
    if r.stderr.strip():
        print(r.stderr.strip()[:2000])
    raise SystemExit(r.returncode)


if __name__ == "__main__":
    main()
