#!/usr/bin/env python3
"""Run the SDK instrumentation runner; adb's exit code alone is not a test result."""
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
ADB = os.environ.get("ADB", "adb")
OUT = ROOT / "out" / "android-test"
OUT.mkdir(parents=True, exist_ok=True)


def adb(*args, timeout=60):
    return subprocess.run([ADB, *args], text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=timeout, check=True).stdout


def main():
    try:
        for apk in ("apk/debug/app-debug.apk", "apk/androidTest/debug/app-debug-androidTest.apk"):
            print(adb("install", "-r", "-g", str(ROOT / "app/build/outputs" / apk)))
        adb("logcat", "-c")
        result = adb("shell", "am", "instrument", "-w", "-r", *(["-e", "network", "true"] if "--network" in sys.argv else []),
                     "io.github.aksulightning.flyby.test/io.github.aksulightning.flyby.VmInstrumentation",
                     timeout=1020)
        (OUT / "instrumentation.txt").write_text(result)
        print(result)
        if "INSTRUMENTATION_CODE: -1" not in result or "PASS:" not in result or "FAIL:" in result:
            raise RuntimeError("Android runtime acceptance failed; see instrumentation/logcat output")
    finally:
        try:
            logs = adb("logcat", "-d", "-v", "threadtime")
            (OUT / "logcat.txt").write_text(logs)
            print("\n".join(line for line in logs.splitlines()
                            if any(tag in line for tag in ("FlybyVM", "AndroidRuntime", "FATAL", "libc    "))))
        except (subprocess.SubprocessError, OSError) as failure:
            print(f"Could not collect logcat: {failure}", file=sys.stderr)


if __name__ == "__main__":
    main()
