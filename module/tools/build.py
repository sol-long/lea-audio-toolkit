import os
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SDK = os.path.join(ROOT, "sdk")
BT = os.path.join(SDK, "build-tools", "35.0.0")
ANDROID_JAR = os.path.join(SDK, "platforms", "android-35", "android.jar")
AAPT2 = os.path.join(BT, "aapt2.exe")
ZIPALIGN = os.path.join(BT, "zipalign.exe")
APKSIGNER = os.path.join(BT, "apksigner.bat")
D8 = os.path.join(BT, "d8.bat")

APP = os.path.join(ROOT, "app")
BUILD = os.path.join(ROOT, "build")
DIST = os.path.join(ROOT, "dist")
LIBXPOSED = os.path.join(ROOT, "libs", "libxposed-api.jar")


def run(args, **kw):
    print("$", " ".join(os.path.basename(a) if i == 0 else a for i, a in enumerate(args)))
    r = subprocess.run(args, capture_output=True, text=True,
                       encoding="utf-8", errors="replace", **kw)
    if r.returncode != 0:
        sys.stderr.write(r.stdout or "")
        sys.stderr.write(r.stderr or "")
        raise SystemExit("FAILED: %s" % args[0])
    if r.stdout.strip():
        print(r.stdout.strip())
    return r


def java_files(base):
    out = []
    for dirpath, _, names in os.walk(base):
        for n in names:
            if n.endswith(".java"):
                out.append(os.path.join(dirpath, n))
    return out


def main():
    for p in (BUILD, DIST):
        shutil.rmtree(p, ignore_errors=True)
        os.makedirs(p, exist_ok=True)
    for f in (AAPT2, ZIPALIGN, APKSIGNER, D8, ANDROID_JAR, LIBXPOSED):
        if not os.path.exists(f):
            raise SystemExit("missing: " + f)

    res_zip = os.path.join(BUILD, "res.zip")
    base_apk = os.path.join(BUILD, "base.apk")
    gen = os.path.join(BUILD, "gen")

    # 1) resources
    run([AAPT2, "compile", "--dir", os.path.join(APP, "res"), "-o", res_zip])

    # 2) link -> binary manifest + resources
    run([AAPT2, "link", "-o", base_apk,
         "-I", ANDROID_JAR,
         "--manifest", os.path.join(APP, "AndroidManifest.xml"),
         "--java", gen,
         "--min-sdk-version", "29",
         "--target-sdk-version", "35",
         res_zip])

    # 3) module classes (libxposed api is compile-only, provided by framework at runtime)
    classes = os.path.join(BUILD, "classes")
    os.makedirs(classes, exist_ok=True)
    run(["javac", "-nowarn", "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-bootclasspath", ANDROID_JAR + os.pathsep + os.path.join(BT, "core-lambda-stubs.jar"),
         "-classpath", LIBXPOSED + os.pathsep + gen,
         "-d", classes]
        + java_files(os.path.join(APP, "src"))
        + java_files(gen))

    # 4) dex
    jar = os.path.join(BUILD, "classes.jar")
    run(["jar", "cf", jar, "-C", classes, "."])
    dexdir = os.path.join(BUILD, "dex")
    os.makedirs(dexdir, exist_ok=True)
    run([D8, "--release", "--min-api", "29", "--lib", ANDROID_JAR,
         "--output", dexdir, jar])

    # 5) pack classes.dex + META-INF/xposed/* into the apk
    with zipfile.ZipFile(base_apk, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(dexdir, "classes.dex"), "classes.dex")
        meta = os.path.join(APP, "meta")
        for dirpath, _, names in os.walk(meta):
            for n in names:
                full = os.path.join(dirpath, n)
                arc = os.path.relpath(full, meta).replace(os.sep, "/")
                z.write(full, arc)

    # 6) align
    aligned = os.path.join(BUILD, "aligned.apk")
    run([ZIPALIGN, "-f", "-p", "4", base_apk, aligned])

    # 7) sign
    ks = os.path.join(BUILD, "lea.keystore")
    if not os.path.exists(ks):
        run(["keytool", "-genkeypair", "-keystore", ks, "-alias", "lea",
             "-storepass", "android", "-keypass", "android",
             "-dname", "CN=LEA Enabler,O=DSH", "-keyalg", "RSA",
             "-keysize", "2048", "-validity", "10000"])
    out = os.path.join(DIST, "LEAEnabler.apk")
    run([APKSIGNER, "sign", "--ks", ks, "--ks-pass", "pass:android",
         "--key-pass", "pass:android", "--out", out, aligned])
    run([APKSIGNER, "verify", out])

    print()
    print("OK ->", out, os.path.getsize(out), "bytes")


if __name__ == "__main__":
    main()
