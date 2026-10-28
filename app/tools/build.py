#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LEA Toolbox — 纯命令行构建（aapt2 -> javac -> d8 -> zipalign -> apksigner）。

不依赖 Gradle / Android Studio，只使用极简 Android SDK。

用法:
    python -X utf8 tools/build.py            # 完整构建 + 校验
    python -X utf8 tools/build.py --no-clean # 保留 build/ 中间产物
"""

import os
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 极简 SDK（默认复用 ../module/sdk，可用环境变量 LEA_SDK 覆盖）
SDK = os.environ.get("LEA_SDK") or os.path.join(
    os.path.dirname(ROOT), "module", "sdk")
BT = os.path.join(SDK, "build-tools", "35.0.0")
ANDROID_JAR = os.path.join(SDK, "platforms", "android-35", "android.jar")

AAPT2 = os.path.join(BT, "aapt2.exe")
ZIPALIGN = os.path.join(BT, "zipalign.exe")
APKSIGNER = os.path.join(BT, "apksigner.bat")
D8 = os.path.join(BT, "d8.bat")
LAMBDA_STUBS = os.path.join(BT, "core-lambda-stubs.jar")

APP = os.path.join(ROOT, "app")
BUILD = os.path.join(ROOT, "build")
DIST = os.path.join(ROOT, "dist")
KEYSTORE_DIR = os.path.join(ROOT, "keystore")
KEYSTORE = os.path.join(KEYSTORE_DIR, "lea-toolbox.keystore")

MIN_SDK = "29"
TARGET_SDK = "35"
APK_NAME = "LEAToolbox.apk"

JDK_CANDIDATES = [
    os.environ.get("JAVA_HOME", ""),
    r"D:\Softwares\JAVA\JDK21",
]


def find_tool(name):
    """优先 JAVA_HOME / 已知 JDK 目录，其次 PATH。"""
    exe = name + (".exe" if os.name == "nt" else "")
    for base in JDK_CANDIDATES:
        if base:
            p = os.path.join(base, "bin", exe)
            if os.path.exists(p):
                return p
    p = shutil.which(name)
    if p:
        return p
    raise SystemExit("找不到工具: %s（请设置 JAVA_HOME）" % name)


JAVAC = find_tool("javac")
JAR = find_tool("jar")
KEYTOOL = find_tool("keytool")
JAVA = find_tool("java")
JDK_BIN = os.path.dirname(JAVA)


def env():
    e = dict(os.environ)
    e["JAVA_HOME"] = os.path.dirname(JDK_BIN)
    e["PATH"] = JDK_BIN + os.pathsep + e.get("PATH", "")
    e["PYTHONUTF8"] = "1"
    return e


def run(args, quiet=False):
    shown = " ".join([os.path.basename(args[0])] + list(args[1:]))
    if not quiet:
        print("$ " + shown)
    r = subprocess.run(args, capture_output=True, text=True,
                       encoding="utf-8", errors="replace", env=env())
    if r.returncode != 0:
        sys.stderr.write("\n[stdout]\n" + (r.stdout or ""))
        sys.stderr.write("\n[stderr]\n" + (r.stderr or ""))
        raise SystemExit("FAILED (%d): %s" % (r.returncode, shown))
    if not quiet:
        out = (r.stdout or "").strip()
        if out:
            print(out)
        err = (r.stderr or "").strip()
        # javac 的 "warning: [options] ..." 提示只在真出错时才有意义，静默丢弃
        if err and "--show-warnings" in sys.argv:
            print(err)
    return r


def java_files(base):
    out = []
    for dirpath, _, names in os.walk(base):
        for n in names:
            if n.endswith(".java"):
                out.append(os.path.join(dirpath, n))
    return sorted(out)


def check_env():
    for f in (AAPT2, ZIPALIGN, APKSIGNER, D8, ANDROID_JAR, LAMBDA_STUBS):
        if not os.path.exists(f):
            raise SystemExit("缺少 SDK 组件: " + f)
    src = java_files(os.path.join(APP, "src"))
    if not src:
        raise SystemExit("没有找到任何 .java 源文件")
    return src


def build():
    src = check_env()
    if "--no-clean" not in sys.argv:
        shutil.rmtree(BUILD, ignore_errors=True)
    os.makedirs(BUILD, exist_ok=True)
    os.makedirs(DIST, exist_ok=True)
    os.makedirs(KEYSTORE_DIR, exist_ok=True)

    res_zip = os.path.join(BUILD, "res.zip")
    base_apk = os.path.join(BUILD, "base.apk")
    gen = os.path.join(BUILD, "gen")
    classes = os.path.join(BUILD, "classes")
    dexdir = os.path.join(BUILD, "dex")
    for d in (gen, classes, dexdir):
        os.makedirs(d, exist_ok=True)

    print("=== 1/7 编译资源 (aapt2 compile)")
    run([AAPT2, "compile", "--dir", os.path.join(APP, "res"), "-o", res_zip])

    print("=== 2/7 链接清单与资源 (aapt2 link)")
    run([AAPT2, "link", "-o", base_apk,
         "-I", ANDROID_JAR,
         "--manifest", os.path.join(APP, "AndroidManifest.xml"),
         "--java", gen,
         "--min-sdk-version", MIN_SDK,
         "--target-sdk-version", TARGET_SDK,
         "--auto-add-overlay",
         res_zip])

    print("=== 3/7 编译 Java (javac, 无 XML 布局)")
    bcp = ANDROID_JAR + os.pathsep + LAMBDA_STUBS
    run([JAVAC, "-nowarn", "-Xlint:-options",
         "-J-Duser.language=en", "-J-Duser.country=US",
         "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-bootclasspath", bcp,
         "-classpath", gen,
         "-d", classes]
        + src + java_files(gen))

    print("=== 4/7 打包 classes.jar")
    jar = os.path.join(BUILD, "classes.jar")
    run([JAR, "cf", jar, "-C", classes, "."])

    print("=== 5/7 dex (d8)")
    run([D8, "--release", "--min-api", MIN_SDK, "--lib", ANDROID_JAR,
         "--output", dexdir, jar])

    dex = os.path.join(dexdir, "classes.dex")
    if not os.path.exists(dex):
        raise SystemExit("d8 未产出 classes.dex")

    print("=== 6/7 塞入 dex 并对齐 (zipalign)")
    with zipfile.ZipFile(base_apk, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(dex, "classes.dex")
    aligned = os.path.join(BUILD, "aligned.apk")
    run([ZIPALIGN, "-f", "-p", "4", base_apk, aligned])

    print("=== 7/7 签名 (apksigner)")
    if not os.path.exists(KEYSTORE):
        run([KEYTOOL, "-genkeypair", "-keystore", KEYSTORE, "-alias", "leatool",
             "-storepass", "android", "-keypass", "android",
             "-dname", "CN=LEA Toolbox,O=DSH", "-keyalg", "RSA",
             "-keysize", "2048", "-validity", "10000"])
    out = os.path.join(DIST, APK_NAME)
    if os.path.exists(out):
        os.remove(out)
    # 默认启用 v1(JAR)+v2+v3，兼容性最好；v4 关掉，避免 dist 里多出 .idsig
    run([APKSIGNER, "sign",
         "--ks", KEYSTORE, "--ks-pass", "pass:android",
         "--key-pass", "pass:android",
         "--min-sdk-version", MIN_SDK,
         "--v4-signing-enabled", "false",
         "--out", out, aligned])
    run([APKSIGNER, "verify", "--min-sdk-version", MIN_SDK, out])

    size = os.path.getsize(out)
    print()
    print("=== aapt2 dump badging 校验")
    r = run([AAPT2, "dump", "badging", out], quiet=True)
    for line in (r.stdout or "").splitlines():
        if line.startswith(("package:", "launchable-activity:", "uses-permission:",
                            "application-label", "sdkVersion", "targetSdkVersion")):
            print("  " + line)

    print()
    print("OK -> %s  (%d bytes / %.1f KB)" % (out, size, size / 1024.0))
    return out


if __name__ == "__main__":
    build()
